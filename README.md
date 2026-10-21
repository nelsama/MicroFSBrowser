# MicroFS Browser

Navegador gráfico en Java Swing para el sistema de archivos **MicroFS** que usa el
proyecto [microfs-6502-cc65](https://github.com/nelsama/microfs-6502-cc65).

Permite abrir una **imagen de tarjeta SD** (`.img`, `.bin`, `.dsk`, `.raw`) o un
dispositivo físico, listar, ver, editar, importar y exportar archivos con el mismo
formato que el driver en C del 6502.

## Requisitos

- Java JDK 8 o superior (probado con JDK 17)
- Sólo para dispositivos físicos: permisos de administrador

## Compilar y ejecutar

### Windows

| Script | Qué hace |
|---|---|
| `run.bat` | Compila `src\microfs\*.java`, genera `MicroFSBrowser.jar` y abre la aplicación |
| `ejecutar.bat` | **Sólo ejecuta** `MicroFSBrowser.jar` (no compila) |
| `MicroFSBrowser_Admin.bat` | Ejecuta con permisos de administrador (necesario para discos físicos) |
| `MicroFSBrowser.bat` | Ejecuta el JAR sin pedir elevación |

Flujo normal: `run.bat` una vez (o tras cambiar el código) y después `ejecutar.bat`.

### Linux / macOS

```bash
chmod +x run.sh
./run.sh
```

### Manual

```bash
javac -encoding UTF-8 -d bin -sourcepath src src/microfs/*.java
jar cfm MicroFSBrowser.jar MANIFEST.MF -C bin .
java -jar MicroFSBrowser.jar
```

## Formato MicroFS

Es exactamente el que lee y escribe `microfs.c` del driver 6502:

```
Sector 0: tabla de archivos (512 bytes)
├── Bytes 0-1    : firma "FM" (0x46 0x4D)
├── Bytes 2-5    : sin uso
├── Bytes 6-7    : próximo sector libre (little endian, 16 bits)
├── Bytes 8-15   : sin uso
└── Bytes 16-495 : 15 entradas × 32 bytes

Entrada de archivo (32 bytes)
├── Bytes 0-11  : nombre ASCII terminado en NUL (máx. 11 caracteres)
├── Bytes 12-13 : sector inicial
├── Bytes 14-15 : tamaño en bytes
├── Bytes 16-17 : sectores reservados
└── Bytes 18-31 : sin uso

Sector 1+: datos de los archivos, en orden y contiguos
```

### Detalles que importan al trabajar desde el PC

- **Firma `FM`, no `MF`**: el driver escribe `filetab[0] = 'F'` y `filetab[1] = 'M'`.
  Esta herramienta lee ambas para no perder tarjetas creadas con versiones anteriores.
- **15 archivos reales**: la cabecera ocupa 16 bytes y cada entrada 32, así que sólo
  caben `(512 - 16) / 32 = 15` entradas. El driver declara `MFS_MAX_FILES = 16`, pero la
  entrada 16 se saldría del sector 0; la herramienta no permite crear esa entrada.
- **Sin asignación dinámica**: `mfs_create(nombre, tamaño)` reserva los sectores en el
  momento de crear el archivo y esa reserva nunca cambia. Al borrar, los sectores quedan
  huérfanos y no se reutilizan (`next_sector` no retrocede).
- **Nombres**: máximo 11 caracteres ASCII. La herramienta convierte a mayúsculas y
  recorta conservando la extensión (`DOCUMENTOLARGO.TXT` → `DOCUMENTOL.TXT`). Ojo: importar
  un archivo cuyo nombre ya se truncó en el PC (`LAUNCHER.BIN` → `LAUNCHE.BIN`) produce un
  nombre distinto al que espera un `BOOT.INI` del 6502; usa **Renombrar** para ajustarlo.
- **Tamaño máximo por archivo**: 65535 bytes. El área MicroFS no debería pasar de 32 MB
  (16 MB con seguridad), porque los sectores se direccionan con 16 bits.
- Al guardar, la herramienta **no mueve los archivos**: se escriben en su ubicación actual.
  Sólo si un archivo no cabe en su reserva (creció) se recompacta el medio reasignando
  sectores en orden, y se avisa en el log.

## Flujo de trabajo en la aplicación

1. **Crear imagen…** o **Abrir imagen…**: trabaja sobre un archivo `.img`. También puedes
   escribir una ruta (`\\.\PhysicalDrive3` o `/dev/sdb`) o elegirla de **Detectar discos**.
2. **Sector 0 en offset**: si la imagen conserva la tabla de particiones (contenido crudo
   de la SD), MicroFS no empieza en el byte 0. Usa **Detectar offset** y la herramienta
   buscará la firma `FM` en 0, en las particiones del MBR y en 1/2/4/8 MB.
3. **Abrir MicroFS**: carga tabla y archivos en memoria.
4. **Ver / Editar texto / Renombrar / PC → SD / SD → PC / Eliminar**: los cambios quedan
   en memoria. La barra de estado marca `CAMBIOS SIN GUARDAR`.
   Los selectores de archivos (importar, exportar, abrir/crear imagen) **recuerdan la última
   carpeta usada** entre sesiones.
   *Renombrar* también está disponible con **F2** sobre el archivo seleccionado: cambia el
   nombre en la tabla sin mover los datos ni la reserva de sectores.
5. **Guardar cambios**: escribe en el medio sólo lo que cambió. Se **respetan las
   posiciones originales** de los archivos (renombrar, borrar o editar sin crecer no mueve
   nada); sólo si un archivo no cabe en su reserva se reasigna el reparto de sectores, y se
   avisa en el log. La barra de estado indica siempre `Sin cambios pendientes` o
   `CAMBIOS SIN GUARDAR`.
6. **Copia de seguridad automática**: en el primer guardado la aplicación pregunta si
   quieres copias. Si aceptas, escribe `microfs_AAAAMMDD-HHMMSS.bak` (sector 0 + área de
   datos usada) en la carpeta elegida o, por defecto, en `<usuario>\MicroFS Backups`.
   Si respondes que no, no vuelve a preguntar durante esa sesión.

La vista elige automáticamente texto, hexadecimal o PETSCII según la extensión
(`.PET`) y el contenido; la casilla **Vista hexadecimal** fuerza el volcado.

## Trabajar con tarjetas SD físicas

### Recomendado: usar una imagen

En Windows, escribir en el disco físico de la tarjeta es frágil (montaje, permisos,
drivers del lector). El camino más fiable:

1. Lee la tarjeta a un archivo con Win32DiskImager / `dd if=/dev/sdb of=sd.img`.
2. Edita `sd.img` con MicroFS Browser.
3. Vuelve a escribir la imagen en la tarjeta.

### Dispositivo físico

- **Windows**: ejecuta como Administrador (`MicroFSBrowser_Admin.bat`).
  Si `\.\PhysicalDriveN` está bloqueado (habitual en lectores USB multitarjeta y en
  volúmenes sin sistema de archivos reconocido), la aplicación **lo detecta y escribe a
  través de la unidad montada** (`\.\F:`) usando PowerShell, indicándolo en el log.
- **Linux**: `/dev/sdX` (verifica con `lsblk`); requiere `sudo` y estar desmontada.

La escritura se bloquea por seguridad si el dispositivo contiene el sistema operativo.

## Herramienta de línea de comandos (Python)

`microfs.py` ofrece las mismas operaciones para scripts e integración:

```bash
python microfs.py sd.img list
python microfs.py sd.img read HELLO.TXT
python microfs.py sd.img export HELLO.TXT hello.txt
python microfs.py sd.img import PROGRAM.PRG program.prg
python microfs.py sd.img delete PROGRAM.PRG
python microfs.py sd.img rename PROGRAM.PRG MAIN.PRG
python microfs.py sd.img format
python microfs.py sd.img --offset 0x100000 list     # imagen con tabla de particiones
```

## Pruebas

```bash
javac -encoding UTF-8 -d bin -sourcepath src src/microfs/*.java
java -cp bin microfs.MicroFSSelfTest
# o sobre el JAR ya generado
java -cp MicroFSBrowser.jar microfs.MicroFSSelfTest
```

La prueba crea imágenes temporales y verifica el formato byte a byte: firma, cabecera,
posiciones, tamaño y reserva de cada entrada, contenido en los sectores, relectura,
renombrado, límite de entradas, guardado sin reubicar archivos, restauración desde la
copia de seguridad y rechazo de guardados que no caben.

## Historial

### v2.0

- Compatibilidad con el formato real del driver (`FM`, 15 entradas, cabecera de 6 bytes).
- Apertura de **imágenes de tarjeta** además de dispositivos físicos.
- Detección de discos SD/USB con preselección de la tarjeta.
- Detección automática del offset del sector 0 (MBR / 1 MB).
- Renombrar archivos (botón y **F2**) sin mover datos.
- Guardado que respeta la posición de los archivos; recompacta sólo si es necesario.
- Copias de seguridad `.bak` y *Deshacer*.
- Importación/exportación en lote, vista texto/hex/PETSCII.
- Lectura de dispositivos físicos en ~1 s (antes ~150 s).
- La CLI `microfs.py` y todos los selectores de archivos/carpetas recuerdan la última
  ubicación usada.

## Rendimiento en dispositivos físicos

En Windows, Java no puede abrir `\\.\PhysicalDriveN` ni `\\.\F:`, así que ese acceso se
hace con PowerShell. Para que sea rápido, la aplicación:

- abre el dispositivo **una sola vez** por operación;
- lee de una vez todo el rango de sectores que necesitan todos los archivos.

Medido con una tarjeta de 954 MB con 15 archivos: **≈1 s** para cargar el contenido
completo (antes, abriendo el dispositivo por sector, tardaba ~150 s).
Las imágenes `.img` se leen directamente con Java y son prácticamente instantáneas.

## Limitaciones conocidas

- Sin subdirectorios, sin timestamps y sin atributos.
- El renombrado cambia sólo el nombre: no libera ni reorganiza sectores.
- El borrado no libera espacio (limitación del formato, no de la herramienta).
- Máximo 15 archivos y 65535 bytes por archivo.
- En Windows, los discos físicos requieren elevar permisos y no tener letra de unidad.

## Licencia

Libre para uso personal y educativo.
