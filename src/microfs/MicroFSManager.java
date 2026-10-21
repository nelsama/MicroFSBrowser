package microfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Implementación en Java del sistema de archivos MicroFS (microfs-6502-cc65).
 *
 * Formato, idéntico al driver en C del 6502:
 *   Sector 0: tabla de archivos (512 bytes)
 *     bytes 0-1  : firma "FM" (0x46 0x4D)
 *     bytes 6-7  : próximo sector libre (little endian)
 *     bytes 16.. : 16 entradas de 32 bytes
 *   Sector 1+  : datos de los archivos (contiguos)
 *
 * Entrada de archivo (32 bytes):
 *     bytes 0-11 : nombre terminado en NUL (máx. 11 caracteres)
 *     bytes 12-13: sector inicial
 *     bytes 14-15: tamaño en bytes
 *     bytes 16-17: sectores reservados
 *     bytes 18-31: no usados
 *
 * Modelo de trabajo: al abrir un medio la tabla y el contenido de los archivos se cargan en
 * memoria. Las modificaciones se aplican en memoria y sólo se escriben en el medio con
 * {@link #flush()}, lo que permite deshacer y evita escrituras parciales.
 */
public final class MicroFSManager {

    public static final int MFS_MAX_FILES = 16;
    public static final int MFS_NAME_LEN = 12;
    public static final int SECTOR_SIZE = 512;
    public static final int MAX_FILE_SIZE = 65535;
    /** El 6502 direcciona los sectores con uint16_t: el área MicroFS no puede pasar de 32 MB. */
    public static final long MAX_MEDIA_BYTES = 65535L * SECTOR_SIZE;

    private static final int TABLE_OFFSET = 16;
    private static final int ENTRY_SIZE = 32;
    /**
     * Entradas que caben realmente en el sector 0: 16 de cabecera + N*32 <= 512 => 15.
     * El driver declara MFS_MAX_FILES = 16, pero la entrada 16 se saldría del sector.
     */
    public static final int MAX_ENTRIES_IN_SECTOR = (SECTOR_SIZE - TABLE_OFFSET) / ENTRY_SIZE;
    /** Longitud máxima utilizable del nombre: el último byte de la entrada es el NUL. */
    public static final int MAX_NAME_LENGTH = MFS_NAME_LEN - 1;

    public static final class Entry {
        String name = "";
        int startSector;
        int size;
        int sectors;
        int allocatedSectors;
        final byte[] reserved = new byte[ENTRY_SIZE - 18];

        public boolean isUsed() { return name != null && !name.isEmpty(); }
        public String getName() { return name; }
        public int getStartSector() { return startSector; }
        public int getSize() { return size; }
        public int getSectors() { return sectors; }
        public int getAllocatedSectors() { return allocatedSectors; }
        public int getReservedBytes() { return allocatedSectors * SECTOR_SIZE; }

        Entry copy() {
            Entry e = new Entry();
            e.name = name;
            e.startSector = startSector;
            e.size = size;
            e.sectors = sectors;
            e.allocatedSectors = allocatedSectors;
            System.arraycopy(reserved, 0, e.reserved, 0, reserved.length);
            return e;
        }
    }

    private static final class FileNode {
        Entry entry = new Entry();
        byte[] data = new byte[0];
        /** Sector en el que estaba el archivo al abrir el medio (0 si es nuevo). */
        int originalStart;
        /** Sectores reservados que tenía al abrir el medio. */
        int originalSectors;
        /** false si el contenido cambió en memoria desde el último guardado. */
        boolean persisted = true;

        FileNode copy() {
            FileNode n = new FileNode();
            n.entry = entry.copy();
            n.data = data.clone();
            n.originalStart = originalStart;
            n.originalSectors = originalSectors;
            n.persisted = persisted;
            return n;
        }
    }

    private String device;
    private long baseOffset;
    private long mediaBytes = -1;
    private List<FileNode> nodes = new ArrayList<>();
    private int nextSector = 1;
    private boolean mounted;

    public MicroFSManager() {
        newMedium();
    }

    /** Estado inicial: medio nuevo formateado en memoria (sin dispositivo asignado). */
    public void newMedium() {
        device = null;
        baseOffset = 0;
        mediaBytes = -1;
        nodes = new ArrayList<>();
        nextSector = 1;
        mounted = true;
    }

    // ==================== Montaje ====================

    public void mount(String device, long baseOffset) throws IOException {
        List<FileNode> loaded = new ArrayList<>();
        byte[] table = new byte[SECTOR_SIZE];
        int next;
        long total;

        // Una sola sesión para toda la operación: en Windows abrir el dispositivo es caro.
        try (DeviceAccess.Medium medium = DeviceAccess.openMedium(device)) {
            total = medium.size();
            if (total >= 0 && (baseOffset >= total || baseOffset < 0)) {
                throw new IOException("El offset " + baseOffset + " está fuera del medio (" + total + " bytes).");
            }

            medium.read(baseOffset, table, SECTOR_SIZE);

            // El driver escribe filetab[0]='F', filetab[1]='M'. Se acepta también "MF",
            // usado por versiones anteriores de esta herramienta.
            boolean signature = (table[0] == 0x46 && table[1] == 0x4D)
                    || (table[0] == 0x4D && table[1] == 0x46);
            if (!signature) {
                throw new IOException(String.format(
                        "No se encontró el sistema de archivos MicroFS.%n" +
                        "Se esperaba la firma \"FM\" (46 4D) y se encontró %02X %02X en el offset %d.",
                        table[0] & 0xFF, table[1] & 0xFF, baseOffset));
            }

            next = (table[6] & 0xFF) | ((table[7] & 0xFF) << 8);
            if (next < 1) {
                throw new IOException("La tabla declara un próximo sector libre inválido (" + next + ").");
            }

            for (int i = 0; i < MAX_ENTRIES_IN_SECTOR; i++) {
                int off = TABLE_OFFSET + i * ENTRY_SIZE;
                if (table[off] == 0) continue;
                FileNode n = new FileNode();
                n.entry = parseEntry(table, off);
                if (n.entry.startSector < 1 || n.entry.sectors < 0) {
                    throw new IOException("Entrada inválida en la posición " + i +
                            " (sector inicial " + n.entry.startSector + ").");
                }
                n.entry.allocatedSectors = Math.max(n.entry.sectors, sectorsNeeded(n.entry.size));
                n.originalStart = n.entry.startSector;
                n.originalSectors = n.entry.allocatedSectors;
                loaded.add(n);
            }

            // Una sola lectura para toda el área de datos usada por los archivos
            // (sectores huérfanos tras borrados no se leen): imprescindible para que el
            // montaje sea rápido en dispositivos físicos.
            int lastSector = 1;
            for (FileNode n : loaded) {
                lastSector = Math.max(lastSector, n.entry.startSector + n.entry.allocatedSectors);
            }
            if (!loaded.isEmpty()) {
                int bytes = lastSector * SECTOR_SIZE;
                byte[] block = new byte[bytes];
                medium.read(baseOffset, block, bytes);
                for (FileNode n : loaded) {
                    int from = n.entry.startSector * SECTOR_SIZE;
                    int count = Math.min(n.entry.size, n.entry.allocatedSectors * SECTOR_SIZE);
                    n.data = Arrays.copyOfRange(block, from, from + count);
                }
            }
        }

        this.device = device;
        this.baseOffset = baseOffset;
        this.mediaBytes = total < 0 ? -1 : total - baseOffset;
        this.nextSector = next;
        nodes = loaded;
        mounted = true;
    }

    public void close() {
        mounted = false;
        device = null;
        nodes = new ArrayList<>();
        nextSector = 1;
    }

    private static Entry parseEntry(byte[] table, int off) {
        Entry e = new Entry();
        int len = 0;
        while (len < MFS_NAME_LEN && table[off + len] != 0) len++;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int b = table[off + i] & 0xFF;
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        e.name = sb.toString();
        e.startSector = (table[off + 12] & 0xFF) | ((table[off + 13] & 0xFF) << 8);
        e.size = (table[off + 14] & 0xFF) | ((table[off + 15] & 0xFF) << 8);
        e.sectors = (table[off + 16] & 0xFF) | ((table[off + 17] & 0xFF) << 8);
        System.arraycopy(table, off + 18, e.reserved, 0, e.reserved.length);
        return e;
    }

    // ==================== Consultas ====================

    public boolean isMounted() { return mounted; }

    public String getDevice() { return device; }

    public long getBaseOffset() { return baseOffset; }

    /** Tamaño del área MicroFS en bytes, o -1 si se desconoce. */
    public long getMediaBytes() { return mediaBytes; }

    /** Asigna el medio de destino al guardar (se usa al formatear una imagen sin abrirla). */
    public void setTarget(String device, long baseOffset) {
        this.device = device;
        this.baseOffset = baseOffset;
        try {
            long total = DeviceAccess.size(device);
            this.mediaBytes = total < 0 ? -1 : total - baseOffset;
        } catch (IOException ex) {
            this.mediaBytes = -1;
        }
    }

    public List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        for (FileNode n : nodes) result.add(n.entry.copy());
        result.sort(Comparator.comparing(e -> e.name.toUpperCase()));
        return result;
    }

    public Entry find(String name) {
        FileNode n = nodeOf(name);
        return n == null ? null : n.entry.copy();
    }

    public int getFileCount() { return nodes.size(); }

    public int getNextSector() { return nextSector; }

    /** Sectores libres según el tamaño conocido del medio, o -1 si se desconoce. */
    public long freeSectors() {
        if (mediaBytes <= 0) return -1;
        long total = Math.min(mediaBytes, MAX_MEDIA_BYTES) / SECTOR_SIZE;
        return Math.max(0, total - nextSector);
    }

    private FileNode nodeOf(String name) {
        String up = name == null ? "" : name.trim().toUpperCase();
        for (FileNode n : nodes) {
            if (n.entry.name.toUpperCase().equals(up)) return n;
        }
        return null;
    }

    // ==================== Lectura ====================

    public byte[] readFile(String name) throws IOException {
        FileNode n = nodeOf(name);
        if (n == null) throw new IOException("Archivo no encontrado: " + name);
        return n.data.clone();
    }

    private byte[] readSectors(DeviceAccess.Medium medium, long base, int startSector,
                               int sectors, int size) throws IOException {
        if (sectors <= 0) return new byte[0];
        int bytes = sectors * SECTOR_SIZE;
        byte[] raw = new byte[bytes];
        // Una sola lectura por rango contiguo: mucho más rápido en dispositivos físicos.
        medium.read(base + (long) startSector * SECTOR_SIZE, raw, bytes);
        return Arrays.copyOf(raw, Math.min(size, raw.length));
    }

    private long offsetOf(int sector) {
        return baseOffset + (long) sector * SECTOR_SIZE;
    }

    private static int sectorsNeeded(int size) {
        return Math.max(1, (size + SECTOR_SIZE - 1) / SECTOR_SIZE);
    }

    // ==================== Modificaciones en memoria ====================

    /**
     * Crea o reemplaza un archivo en memoria.
     *
     * MicroFS no tiene asignación dinámica: cada archivo reserva sectores al crearse y esa
     * reserva no cambia. Si el contenido cabe en la reserva actual se reutiliza; si no,
     * {@link #flush()} recompacta el medio y reasigna el espacio de todos los archivos.
     */
    public void writeFile(String name, byte[] content) throws IOException {
        ensureMounted();
        String clean = sanitizeName(name);
        if (content.length > MAX_FILE_SIZE) {
            throw new IOException("El archivo supera el máximo de " + MAX_FILE_SIZE + " bytes (" + content.length + ").");
        }
        FileNode n = nodeOf(clean);
        if (n == null) {
            if (nodes.size() >= MAX_ENTRIES_IN_SECTOR) {
                throw new IOException("No hay espacio para más archivos (máximo " + MAX_ENTRIES_IN_SECTOR + ").");
            }
            n = new FileNode();
            n.entry.name = clean;
            n.entry.startSector = 0;
            n.entry.sectors = sectorsNeeded(content.length);
            n.originalStart = 0;
            n.persisted = false;
            nodes.add(n);
        }
        n.entry.size = content.length;
        // La reserva nunca se reduce: sólo puede crecer (y entonces se recompacta al guardar).
        n.entry.allocatedSectors = Math.max(n.entry.allocatedSectors, sectorsNeeded(content.length));
        n.data = content.clone();
        n.persisted = false;
    }

    /**
     * Crea un archivo vacío con reserva fija en bytes, igual que mfs_create() en el 6502.
     * Falla si el nombre ya existe (MFS_ERR_EXISTS).
     */
    public Entry createFile(String name, int reservedBytes) throws IOException {
        ensureMounted();
        String clean = sanitizeName(name);
        if (nodeOf(clean) != null) {
            throw new IOException("Ya existe un archivo llamado " + clean);
        }
        if (nodes.size() >= MAX_ENTRIES_IN_SECTOR) {
            throw new IOException("No hay espacio para más archivos (máximo " + MAX_ENTRIES_IN_SECTOR + ").");
        }
        if (reservedBytes < 0 || reservedBytes > MAX_FILE_SIZE) {
            throw new IOException("La reserva debe estar entre 0 y " + MAX_FILE_SIZE + " bytes.");
        }
        FileNode n = new FileNode();
        n.entry.name = clean;
        n.entry.sectors = sectorsNeeded(reservedBytes);
        n.entry.allocatedSectors = n.entry.sectors;
        n.entry.size = 0;
        n.originalStart = 0;
        n.persisted = false;
        nodes.add(n);
        return n.entry.copy();
    }

    public void rename(String oldName, String newName) throws IOException {
        ensureMounted();
        FileNode n = nodeOf(oldName);
        if (n == null) throw new IOException("Archivo no encontrado: " + oldName);
        String clean = sanitizeName(newName);
        FileNode other = nodeOf(clean);
        if (other != null && other != n) throw new IOException("Ya existe un archivo llamado " + clean);
        n.entry.name = clean;
    }

    /** Borra la entrada de la tabla; los sectores quedan sin reutilizar, como en el 6502. */
    public void delete(String name) throws IOException {
        ensureMounted();
        FileNode n = nodeOf(name);
        if (n == null) throw new IOException("Archivo no encontrado: " + name);
        nodes.remove(n);
    }

    public void deleteAll() {
        nodes.clear();
    }

    /** Contenido actual del área de datos usada por los archivos (para copias de seguridad). */
    public byte[] snapshotUsedArea() throws IOException {
        ensureMounted();
        int lastSector = 1;
        for (FileNode n : nodes) {
            lastSector = Math.max(lastSector, Math.max(n.originalStart, n.entry.startSector)
                    + Math.max(n.originalSectors, n.entry.allocatedSectors));
        }
        int bytes = lastSector * SECTOR_SIZE;
        byte[] data = new byte[bytes];
        try (DeviceAccess.Medium medium = DeviceAccess.openMedium(device)) {
            medium.read(baseOffset, data, bytes);
        }
        return data;
    }

    /** Formatea: tabla vacía con firma "FM" y next_sector = 1 (en memoria). */
    public void format() {
        long keepBase = baseOffset;
        String keepDevice = device;
        long keepMedia = mediaBytes;
        newMedium();
        baseOffset = keepBase;
        device = keepDevice;
        mediaBytes = keepMedia;
    }

    // ==================== Guardado ====================

    /**
     * Escribe en el medio la tabla y los sectores de datos que hayan cambiado.
     *
     * Se respeta la posición original de cada archivo siempre que sea posible
     * (renombrar, borrar o editar archivos sin crecer): así el medio conserva el
     * reparto de sectores que tenía y no se tocan los datos ajenos. Sólo cuando un
     * archivo no cabe en su reserva se recompacta todo el medio.
     *
     * @return true si hubo que recompactar
     */
    public boolean flush() throws IOException {
        ensureMounted();
        if (device == null || device.isEmpty()) {
            throw new IOException("No hay un medio asignado. Abre o crea una imagen de SD.");
        }

        boolean needsRebuild = false;
        for (FileNode n : nodes) {
            if (n.originalStart < 1 || sectorsNeeded(n.entry.size) > n.originalSectors) {
                needsRebuild = true;
                break;
            }
        }

        if (needsRebuild) {
            rebuild();
            return true;
        }
        writeInPlace();
        return false;
    }

    /** Guarda conservando la ubicación de cada archivo (caso normal). */
    private void writeInPlace() throws IOException {
        int maxSector = 1;
        for (FileNode n : nodes) {
            maxSector = Math.max(maxSector, n.originalStart + n.originalSectors);
        }
        nextSector = Math.max(nextSector, maxSector);

        long endOffset = (long) nextSector * SECTOR_SIZE;
        if (endOffset > MAX_MEDIA_BYTES) {
            throw new IOException("Los archivos no caben en el espacio direccionable por el 6502.");
        }

        try (DeviceAccess.Medium medium = DeviceAccess.openMedium(device)) {
            for (FileNode n : nodes) {
                n.entry.startSector = n.originalStart;
                n.entry.sectors = n.originalSectors;
                n.entry.allocatedSectors = n.originalSectors;
                if (!n.persisted) {
                    writeSectors(medium, n.entry.startSector, n.entry.sectors, n.data);
                    n.persisted = true;
                }
            }
            writeTable(medium);
        }
    }

    /** Recompacta todos los archivos de forma contigua (sólo si alguno no cabe). */
    private void rebuild() throws IOException {
        List<FileNode> ordered = new ArrayList<>(nodes);
        int sector = 1;
        for (FileNode n : ordered) {
            n.entry.allocatedSectors = Math.max(sectorsNeeded(n.entry.size), n.entry.allocatedSectors);
            n.entry.startSector = sector;
            n.entry.sectors = n.entry.allocatedSectors;
            sector += n.entry.allocatedSectors;
        }

        long endOffset = sector * (long) SECTOR_SIZE;
        if (endOffset > MAX_MEDIA_BYTES) {
            throw new IOException("Los archivos no caben en el espacio direccionable por el 6502 " +
                    "(máximo " + (MAX_MEDIA_BYTES / 1024 / 1024) + " MB).");
        }
        if (mediaBytes > 0 && endOffset > mediaBytes) {
            throw new IOException("Los archivos no caben en el medio: se necesitan " +
                    humanSize(endOffset) + " y el medio tiene " + humanSize(mediaBytes) + ".");
        }

        // Una sola sesión para todo el guardado.
        try (DeviceAccess.Medium medium = DeviceAccess.openMedium(device)) {
            for (FileNode n : ordered) {
                // El 6502 copia byte a byte hasta el tamaño declarado y nunca escribe de más.
                writeSectors(medium, n.entry.startSector, n.entry.sectors, n.data);
                n.persisted = true;
            }
            nextSector = sector;
            writeTable(medium);
        }
    }

    private void writeSectors(DeviceAccess.Medium medium, int startSector, int sectors, byte[] content)
            throws IOException {
        int bytes = sectors * SECTOR_SIZE;
        byte[] buffer = new byte[bytes];
        System.arraycopy(content, 0, buffer, 0, Math.min(content.length, bytes));
        medium.write(offsetOf(startSector), buffer, bytes);
    }

    private void writeTable(DeviceAccess.Medium medium) throws IOException {
        medium.write(baseOffset, buildTable(), SECTOR_SIZE);
    }

    /** Tabla de archivos del sector 0 serializada. */
    private byte[] buildTable() {
        byte[] table = new byte[SECTOR_SIZE];
        table[0] = 0x46; // 'F'
        table[1] = 0x4D; // 'M'
        table[6] = (byte) (nextSector & 0xFF);
        table[7] = (byte) ((nextSector >> 8) & 0xFF);

        for (int i = 0; i < MAX_ENTRIES_IN_SECTOR && i < nodes.size(); i++) {
            Entry e = nodes.get(i).entry;
            int off = TABLE_OFFSET + i * ENTRY_SIZE;
            if (off + ENTRY_SIZE > SECTOR_SIZE) break;
            byte[] nameBytes = e.name.getBytes(StandardCharsets.US_ASCII);
            int n = Math.min(nameBytes.length, MAX_NAME_LENGTH);
            System.arraycopy(nameBytes, 0, table, off, n);
            table[off + n] = 0;
            table[off + 12] = (byte) (e.startSector & 0xFF);
            table[off + 13] = (byte) ((e.startSector >> 8) & 0xFF);
            table[off + 14] = (byte) (e.size & 0xFF);
            table[off + 15] = (byte) ((e.size >> 8) & 0xFF);
            table[off + 16] = (byte) (e.sectors & 0xFF);
            table[off + 17] = (byte) ((e.sectors >> 8) & 0xFF);
            System.arraycopy(e.reserved, 0, table, off + 18, e.reserved.length);
        }
        return table;
    }

    public String sanitizeName(String name) throws IOException {
        String n = name == null ? "" : name.trim().toUpperCase();
        if (n.isEmpty()) throw new IOException("El nombre no puede estar vacío.");
        if (n.length() > MAX_NAME_LENGTH) {
            throw new IOException("El nombre no puede superar " + MAX_NAME_LENGTH + " caracteres.");
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IOException("El nombre solo admite caracteres ASCII imprimibles.");
            }
        }
        return n;
    }

    private void ensureMounted() throws IOException {
        if (!mounted) throw new IOException("No hay ningún medio MicroFS abierto.");
    }

    static String humanSize(long bytes) {
        if (bytes < 0) return "desconocido";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
