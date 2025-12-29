#!/usr/bin/env python3
"""
MICROFS.PY - Herramienta para gestionar MicroFS en tarjetas SD

Uso:
    python microfs.py <dispositivo> format              - Formatear SD
    python microfs.py <dispositivo> list                - Listar archivos
    python microfs.py <dispositivo> read <archivo>      - Leer archivo
    python microfs.py <dispositivo> write <archivo> [datos]  - Escribir archivo
    python microfs.py <dispositivo> delete <archivo>    - Eliminar archivo
    python microfs.py <dispositivo> import <archivo> <local>  - Importar desde PC
    python microfs.py <dispositivo> export <archivo> <local>  - Exportar a PC

Ejemplo Windows:
    python microfs.py \\\\.\\PhysicalDrive2 list
    python microfs.py \\\\.\\PhysicalDrive2 import TEST.TXT C:\\datos\\test.txt
    
Ejemplo Linux:
    python microfs.py /dev/sdc list
"""

import sys
import struct
import os

MFS_MAGIC = 0x4D46      # "MF"
MFS_MAX_FILES = 16
MFS_NAME_LEN = 12
SECTOR_SIZE = 512

class MicroFS:
    def __init__(self, device):
        self.device = device
        self.files = []
        self.num_files = 0
        self.next_sector = 1
        
    def open_device(self, mode='rb'):
        """Abrir dispositivo SD"""
        return open(self.device, mode + 'b', buffering=0)
    
    def read_sector(self, sector):
        """Leer un sector"""
        with self.open_device('r') as f:
            f.seek(sector * SECTOR_SIZE)
            return f.read(SECTOR_SIZE)
    
    def write_sector(self, sector, data):
        """Escribir un sector"""
        if len(data) < SECTOR_SIZE:
            data = data + bytes(SECTOR_SIZE - len(data))
        with self.open_device('r+') as f:
            f.seek(sector * SECTOR_SIZE)
            f.write(data[:SECTOR_SIZE])
    
    def load_superblock(self):
        """Cargar superbloque"""
        data = self.read_sector(0)
        
        magic, version, num_files, next_sector = struct.unpack('<HHHH', data[:8])
        
        if magic != MFS_MAGIC:
            raise ValueError("No es un sistema MicroFS válido")
        
        self.num_files = num_files
        self.next_sector = next_sector
        self.files = []
        
        # Leer entradas (offset 16, 32 bytes cada una)
        for i in range(MFS_MAX_FILES):
            offset = 16 + i * 32
            entry_data = data[offset:offset+32]
            
            name = entry_data[:MFS_NAME_LEN].rstrip(b'\x00').decode('ascii', errors='ignore')
            start_sector, size, sectors, flags = struct.unpack('<HHHB', entry_data[12:19])
            
            self.files.append({
                'name': name,
                'start_sector': start_sector,
                'size': size,
                'sectors': sectors,
                'flags': flags
            })
    
    def save_superblock(self):
        """Guardar superbloque"""
        data = bytearray(SECTOR_SIZE)
        
        # Header
        struct.pack_into('<HHHH', data, 0, MFS_MAGIC, 1, self.num_files, self.next_sector)
        
        # Entradas
        for i, f in enumerate(self.files):
            offset = 16 + i * 32
            name_bytes = f['name'].encode('ascii')[:MFS_NAME_LEN-1].ljust(MFS_NAME_LEN, b'\x00')
            data[offset:offset+MFS_NAME_LEN] = name_bytes
            struct.pack_into('<HHHB', data, offset+12, 
                           f['start_sector'], f['size'], f['sectors'], f['flags'])
        
        self.write_sector(0, bytes(data))
    
    def format(self):
        """Formatear SD con MicroFS"""
        self.num_files = 0
        self.next_sector = 1
        self.files = [{'name': '', 'start_sector': 0, 'size': 0, 'sectors': 0, 'flags': 0} 
                      for _ in range(MFS_MAX_FILES)]
        self.save_superblock()
        print("SD formateada con MicroFS")
    
    def list_files(self):
        """Listar archivos"""
        self.load_superblock()
        
        print(f"\nMicroFS - {self.num_files} archivo(s)")
        print("-" * 40)
        
        for i, f in enumerate(self.files):
            if f['name']:
                print(f"  {i:2d}: {f['name']:12s}  {f['size']:6d} bytes  (sector {f['start_sector']})")
        
        print("-" * 40)
        print(f"Próximo sector libre: {self.next_sector}")
    
    def find_file(self, name):
        """Buscar archivo por nombre"""
        name = name.upper()
        for i, f in enumerate(self.files):
            if f['name'].upper() == name:
                return i
        return -1
    
    def read_file(self, name):
        """Leer contenido de archivo"""
        self.load_superblock()
        
        idx = self.find_file(name)
        if idx < 0:
            print(f"Error: Archivo '{name}' no encontrado")
            return None
        
        f = self.files[idx]
        data = bytearray()
        
        for s in range(f['sectors']):
            sector_data = self.read_sector(f['start_sector'] + s)
            data.extend(sector_data)
        
        return bytes(data[:f['size']])
    
    def write_file(self, name, content):
        """Escribir/crear archivo"""
        self.load_superblock()
        
        if isinstance(content, str):
            content = content.encode('utf-8')
        
        idx = self.find_file(name)
        
        if idx >= 0:
            # Archivo existe - verificar que cabe
            f = self.files[idx]
            if len(content) > f['sectors'] * SECTOR_SIZE:
                print(f"Error: Contenido muy grande para el archivo existente")
                return False
            
            # Escribir datos
            for s in range(f['sectors']):
                start = s * SECTOR_SIZE
                end = start + SECTOR_SIZE
                sector_data = content[start:end] if start < len(content) else b''
                self.write_sector(f['start_sector'] + s, sector_data)
            
            # Actualizar tamaño
            f['size'] = len(content)
            self.save_superblock()
            print(f"Archivo '{name}' actualizado ({len(content)} bytes)")
            
        else:
            # Crear nuevo archivo
            slot = -1
            for i, f in enumerate(self.files):
                if not f['name']:
                    slot = i
                    break
            
            if slot < 0:
                print("Error: No hay espacio para más archivos")
                return False
            
            sectors = (len(content) + SECTOR_SIZE - 1) // SECTOR_SIZE
            if sectors == 0:
                sectors = 1
            
            # Escribir datos
            for s in range(sectors):
                start = s * SECTOR_SIZE
                end = start + SECTOR_SIZE
                sector_data = content[start:end] if start < len(content) else b''
                self.write_sector(self.next_sector + s, sector_data)
            
            # Crear entrada
            self.files[slot] = {
                'name': name.upper()[:MFS_NAME_LEN-1],
                'start_sector': self.next_sector,
                'size': len(content),
                'sectors': sectors,
                'flags': 0
            }
            
            self.next_sector += sectors
            self.num_files += 1
            self.save_superblock()
            print(f"Archivo '{name}' creado ({len(content)} bytes, {sectors} sector(es))")
        
        return True
    
    def delete_file(self, name):
        """Eliminar archivo"""
        self.load_superblock()
        
        idx = self.find_file(name)
        if idx < 0:
            print(f"Error: Archivo '{name}' no encontrado")
            return False
        
        self.files[idx] = {'name': '', 'start_sector': 0, 'size': 0, 'sectors': 0, 'flags': 0}
        self.num_files -= 1
        self.save_superblock()
        print(f"Archivo '{name}' eliminado")
        return True
    
    def import_file(self, mfs_name, local_path):
        """Importar archivo desde PC"""
        with open(local_path, 'rb') as f:
            content = f.read()
        return self.write_file(mfs_name, content)
    
    def export_file(self, mfs_name, local_path):
        """Exportar archivo a PC"""
        content = self.read_file(mfs_name)
        if content is None:
            return False
        with open(local_path, 'wb') as f:
            f.write(content)
        print(f"Exportado a '{local_path}'")
        return True


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    
    device = sys.argv[1]
    command = sys.argv[2].lower()
    
    mfs = MicroFS(device)
    
    try:
        if command == 'format':
            mfs.format()
            
        elif command == 'list':
            mfs.list_files()
            
        elif command == 'read':
            if len(sys.argv) < 4:
                print("Uso: microfs.py <dev> read <archivo>")
                sys.exit(1)
            content = mfs.read_file(sys.argv[3])
            if content:
                print(content.decode('utf-8', errors='replace'))
                
        elif command == 'write':
            if len(sys.argv) < 4:
                print("Uso: microfs.py <dev> write <archivo> [contenido]")
                sys.exit(1)
            content = ' '.join(sys.argv[4:]) if len(sys.argv) > 4 else ''
            mfs.write_file(sys.argv[3], content)
            
        elif command == 'delete':
            if len(sys.argv) < 4:
                print("Uso: microfs.py <dev> delete <archivo>")
                sys.exit(1)
            mfs.delete_file(sys.argv[3])
            
        elif command == 'import':
            if len(sys.argv) < 5:
                print("Uso: microfs.py <dev> import <nombre_mfs> <archivo_local>")
                sys.exit(1)
            mfs.import_file(sys.argv[3], sys.argv[4])
            
        elif command == 'export':
            if len(sys.argv) < 5:
                print("Uso: microfs.py <dev> export <nombre_mfs> <archivo_local>")
                sys.exit(1)
            mfs.export_file(sys.argv[3], sys.argv[4])
            
        else:
            print(f"Comando desconocido: {command}")
            print(__doc__)
            sys.exit(1)
            
    except PermissionError:
        print(f"Error: Sin permisos para acceder a {device}")
        print("En Windows, ejecuta como Administrador")
        print("En Linux, usa sudo o agrega tu usuario al grupo 'disk'")
        sys.exit(1)
    except FileNotFoundError:
        print(f"Error: Dispositivo {device} no encontrado")
        sys.exit(1)
    except ValueError as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
