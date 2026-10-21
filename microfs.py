#!/usr/bin/env python3
"""
MICROFS.PY - Herramienta de linea de comandos para MicroFS (microfs-6502-cc65)

El formato es el del driver en C del 6502:
    Sector 0 : tabla de archivos (firma "FM" en los bytes 0-1, next_sector en 6-7,
               15 entradas de 32 bytes desde el offset 16)
    Sector 1+: datos de los archivos, contiguos

Uso:
    python microfs.py <imagen> [--offset N] list
    python microfs.py <imagen> [--offset N] read <archivo>
    python microfs.py <imagen> [--offset N] export <archivo> <local>
    python microfs.py <imagen> [--offset N] import <archivo> <local>
    python microfs.py <imagen> [--offset N] delete <archivo>
    python microfs.py <imagen> [--offset N] rename <archivo> <nuevo_nombre>
    python microfs.py <imagen> [--offset N] format

Ejemplos:
    python microfs.py sd.img list
    python microfs.py sd.img --offset 1048576 export HELLO.TXT C:\\datos\\hello.txt
    python microfs.py \\\\.\\PhysicalDrive2 list          (Windows, requiere administrador)
    python microfs.py /dev/sdb list                       (Linux, requiere root)

Nota: sobre un disco fisico en Windows solo se puede montar sin escribir; para escribir,
desmonta la unidad (quita la letra) o trabaja con una imagen .img.
"""

import argparse
import struct
import sys

MFS_MAX_FILES = 16
MFS_NAME_LEN = 12
MAX_NAME_LEN = MFS_NAME_LEN - 1
SECTOR_SIZE = 512
TABLE_OFFSET = 16
ENTRY_SIZE = 32
MAX_ENTRIES = (SECTOR_SIZE - TABLE_OFFSET) // ENTRY_SIZE  # 15: la 16a no cabe en el sector
MAX_FILE_SIZE = 65535


class MicroFS:
    def __init__(self, image, offset=0, writable=False):
        self.image = image
        self.offset = offset
        self.writable = writable
        self.files = []          # lista de dicts en memoria
        self.next_sector = 1
        self.table = bytearray(SECTOR_SIZE)

    # ---------- E/S ----------
    def _open(self, mode):
        return open(self.image, mode, buffering=0)

    def _read(self, pos, size):
        with self._open('rb') as f:
            f.seek(pos)
            data = f.read(size)
        if len(data) < size:
            data += bytes(size - len(data))
        return data

    def _write(self, pos, data):
        if not self.writable:
            raise PermissionError("modo lectura")
        with self._open('r+b') as f:
            f.seek(pos)
            f.write(data)

    def _sector_pos(self, sector):
        return self.offset + sector * SECTOR_SIZE

    # ---------- superbloque ----------
    def load(self):
        self.table = bytearray(self._read(self.offset, SECTOR_SIZE))
        if bytes(self.table[0:2]) not in (b'FM', b'MF'):
            raise ValueError(
                "No se encontro MicroFS (firma 0x%02X 0x%02X, se esperaba 'FM') en el offset %d"
                % (self.table[0], self.table[1], self.offset))
        self.next_sector = struct.unpack_from('<H', self.table, 6)[0]
        self.files = []
        for i in range(MAX_ENTRIES):
            base = TABLE_OFFSET + i * ENTRY_SIZE
            if self.table[base] == 0:
                continue
            raw_name = bytes(self.table[base:base + MFS_NAME_LEN])
            name = raw_name.split(b'\x00')[0].decode('ascii', errors='replace')
            start, size, sectors = struct.unpack_from('<HHH', self.table, base + 12)
            self.files.append({
                'slot': i, 'name': name, 'start': start,
                'size': size, 'sectors': sectors,
            })
        return self

    def save(self):
        self.table[0:2] = b'FM'
        struct.pack_into('<H', self.table, 6, self.next_sector)
        for i in range(MAX_ENTRIES):
            base = TABLE_OFFSET + i * ENTRY_SIZE
            self.table[base:base + ENTRY_SIZE] = bytes(ENTRY_SIZE)
        for f in self.files:
            base = TABLE_OFFSET + f['slot'] * ENTRY_SIZE
            name = f['name'].encode('ascii')[:MAX_NAME_LEN]
            self.table[base:base + len(name)] = name
            struct.pack_into('<HHH', self.table, base + 12,
                             f['start'], f['size'], f['sectors'])
        self._write(self.offset, bytes(self.table))

    # ---------- operaciones ----------
    def find(self, name):
        name = name.upper()
        for f in self.files:
            if f['name'].upper() == name:
                return f
        return None

    def read_file(self, name):
        f = self.find(name)
        if f is None:
            return None
        return self._read(self._sector_pos(f['start']), f['size'])

    def write_file(self, name, data):
        name = name.upper()[:MAX_NAME_LEN]
        if len(data) > MAX_FILE_SIZE:
            raise ValueError("El archivo supera %d bytes" % MAX_FILE_SIZE)
        sectors = max(1, (len(data) + SECTOR_SIZE - 1) // SECTOR_SIZE)

        existing = self.find(name)
        if existing is not None:
            if sectors <= existing['sectors']:
                self._write_sectors(existing['start'], existing['sectors'], data)
                existing['size'] = len(data)
                self.save()
                return

        # Reasignacion: compacta todos los archivos en orden, como mfs_create.
        entries = [(f['name'], data if f['name'].upper() == name else
                    self._read(self._sector_pos(f['start']), f['size']))
                   for f in self.files]
        if not any(n.upper() == name for n, _ in entries):
            entries.append((name, data))
        if len(entries) > MAX_ENTRIES:
            raise ValueError("No hay espacio para mas archivos (maximo %d reales)" % MAX_ENTRIES)

        sector = 1
        self.files = []
        for i, (n, content) in enumerate(entries):
            nsec = max(1, (len(content) + SECTOR_SIZE - 1) // SECTOR_SIZE)
            self._write_sectors(sector, nsec, content)
            self.files.append({'slot': i, 'name': n, 'start': sector,
                               'size': len(content), 'sectors': nsec})
            sector += nsec
        self.next_sector = sector
        self.save()

    def _write_sectors(self, start, sectors, content):
        for s in range(sectors):
            start_off = s * SECTOR_SIZE
            chunk = content[start_off:start_off + SECTOR_SIZE]
            self._write(self._sector_pos(start + s), chunk.ljust(SECTOR_SIZE, b'\x00'))

    def delete(self, name):
        f = self.find(name)
        if f is None:
            return False
        self.files.remove(f)
        self.save()
        return True

    def rename(self, old_name, new_name):
        """Cambia el nombre sin mover los datos ni modificar la reserva."""
        f = self.find(old_name)
        if f is None:
            raise ValueError("Archivo no encontrado: %s" % old_name)
        new_name = new_name.upper()
        if not new_name:
            raise ValueError("El nombre no puede estar vacio")
        if len(new_name) > MAX_NAME_LEN:
            raise ValueError("El nombre no puede superar %d caracteres" % MAX_NAME_LEN)
        if any(c < ' ' or c > '~' for c in new_name):
            raise ValueError("El nombre solo admite ASCII imprimible")
        other = self.find(new_name)
        if other is not None and other is not f:
            raise ValueError("Ya existe un archivo llamado %s" % new_name)
        f['name'] = new_name
        self.save()

    def format(self):
        self.files = []
        self.next_sector = 1
        self.table = bytearray(SECTOR_SIZE)
        self.save()

    def free_sectors(self):
        import os
        try:
            total = (os.path.getsize(self.image) - self.offset) // SECTOR_SIZE
        except OSError:
            return None
        return max(0, total - self.next_sector)


def main():
    parser = argparse.ArgumentParser(
        description="Herramienta para el sistema de archivos MicroFS (microfs-6502-cc65)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    parser.add_argument('image', help="archivo de imagen, \\\\.\\PhysicalDriveN o /dev/sdX")
    parser.add_argument('--offset', type=lambda x: int(x, 0), default=0,
                        help="offset en bytes del sector 0 (por defecto 0; 0x100000 si la "
                             "imagen conserva la tabla de particiones)")
    parser.add_argument('command', choices=['list', 'read', 'export', 'import', 'delete', 'rename', 'format'])
    parser.add_argument('name', nargs='?', help="nombre del archivo MicroFS")
    parser.add_argument('local', nargs='?', help="archivo local (segundo argumento)")
    args = parser.parse_args()

    writable = args.command in ('import', 'delete', 'rename', 'format')
    try:
        fs = MicroFS(args.image, args.offset, writable=writable).load()

        if args.command == 'list':
            free = fs.free_sectors()
            print("\nMicroFS: %d archivo(s), proximo sector libre %d%s"
                  % (len(fs.files), fs.next_sector,
                     "" if free is None else ", %d sectores libres" % free))
            print("-" * 62)
            print("%-12s %8s %10s %7s" % ("NOMBRE", "TAMANO", "RESERVADO", "SECTOR"))
            for f in fs.files:
                print("%-12s %8d %10d %7d"
                      % (f['name'], f['size'], f['sectors'] * SECTOR_SIZE, f['start']))
            print("-" * 62)

        elif args.command == 'read':
            data = fs.read_file(args.name or "")
            if data is None:
                sys.exit("Archivo no encontrado: %s" % args.name)
            sys.stdout.buffer.write(data)

        elif args.command == 'export':
            data = fs.read_file(args.name or "")
            if data is None:
                sys.exit("Archivo no encontrado: %s" % args.name)
            with open(args.local, 'wb') as f:
                f.write(data)
            print("Exportado %s (%d bytes) a %s" % (args.name, len(data), args.local))

        elif args.command == 'import':
            with open(args.local, 'rb') as f:
                data = f.read()
            fs.write_file(args.name, data)
            print("Importado %s (%d bytes) como %s" % (args.local, len(data), args.name.upper()))

        elif args.command == 'delete':
            if not fs.delete(args.name or ""):
                sys.exit("Archivo no encontrado: %s" % args.name)
            print("Eliminado %s" % args.name)

        elif args.command == 'rename':
            if args.local is None:
                sys.exit("Uso: microfs.py <imagen> rename <nombre_actual> <nombre_nuevo>")
            fs.rename(args.name or "", args.local)
            print("Renombrado %s -> %s" % (args.name.upper(), args.local.upper()))

        elif args.command == 'format':
            fs.format()
            print("Medio formateado con MicroFS")

    except PermissionError:
        sys.exit("Sin permisos para escribir en %s.\n"
                 "Windows: ejecuta como Administrador y quita la letra de unidad a la SD.\n"
                 "Linux: usa sudo." % args.image)
    except FileNotFoundError:
        sys.exit("No se encontro %s" % args.image)
    except ValueError as e:
        sys.exit("Error: %s" % e)


if __name__ == '__main__':
    main()
