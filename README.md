# MicroFS Browser

Navegador gráfico de archivos para el sistema de archivos MicroFS en tarjetas SD.

## Requisitos

- Java JDK 8 o superior
- Permisos de administrador para acceso a dispositivos físicos

## Compilar y Ejecutar

### Windows
```batch
run.bat
```

### Linux/Mac
```bash
chmod +x run.sh
sudo ./run.sh
```

### Manual
```bash
# Compilar
javac -d bin -sourcepath src src/microfs/MicroFSBrowser.java

# Ejecutar
java -cp bin microfs.MicroFSBrowser
```

## Uso

1. **Dispositivo**: Introduce la ruta del dispositivo SD
   - Windows: `\\.\PhysicalDrive2` (ver en Administración de discos)
   - Linux: `/dev/sdc` (verificar con `lsblk`)

2. **Conectar**: Clic en "Conectar" para leer el superbloque MicroFS

3. **Operaciones disponibles**:
   - **Formatear**: Inicializa la SD con MicroFS (¡borra todos los datos!)
   - **Actualizar**: Recarga la lista de archivos
   - **Leer**: Lee el contenido del archivo seleccionado
   - **Escribir**: Crea o actualiza un archivo con el contenido del editor
   - **Eliminar**: Elimina el archivo seleccionado
   - **Importar**: Copia un archivo del PC a la SD
   - **Exportar**: Copia un archivo de la SD al PC

## Estructura MicroFS

- **Sector 0**: Superbloque
  - Magic: 0x4D46 ("MF")
  - Versión: 1
  - Número de archivos
  - Próximo sector libre
  - 16 entradas de directorio (32 bytes cada una)

- **Sectores 1+**: Datos de archivos

## Limitaciones

- Máximo 16 archivos
- Nombres de hasta 11 caracteres
- Tamaño máximo por archivo: 65535 bytes
- Sin subdirectorios
- Asignación contigua de sectores

## Capturas

```
+------------------------------------------+
| MicroFS Browser v1.0                     |
+------------------------------------------+
| Dispositivo: [\\.\PhysicalDrive2] [Conectar] [Formatear] [Actualizar]
+------------------------------------------+
| Archivos MicroFS      | Contenido        |
|-----------------------|------------------|
| # | Nombre    | Tam   | [Área de texto]  |
| 0 | HELLO.TXT | 13    |                  |
| 1 | DATA.BIN  | 512   |                  |
|-----------------------|------------------|
| [Leer] [Escribir] [Eliminar] [Importar] [Exportar]
+------------------------------------------+
| Log: Conectado a \\.\PhysicalDrive2      |
+------------------------------------------+
```

## Licencia

Libre para uso personal y educativo.
