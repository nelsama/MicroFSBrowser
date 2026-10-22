package microfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Acceso al medio donde vive MicroFS.
 *
 * Destinos soportados:
 *   - Archivo de imagen (.img / .bin): lectura y escritura directa con RandomAccessFile.
 *   - Volumen/dispositivo de Windows (\\.\F:, \\.\PhysicalDriveN) y /dev/sdX en Linux.
 *
 * Se intenta siempre el acceso directo con Java por velocidad; si Windows lo niega
 * (habitual en \\.\PhysicalDriveN y en algunos lectores USB), se delega en PowerShell.
 */
final class DeviceAccess {

    private DeviceAccess() {}

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isWindowsDevice(String device) {
        return device.startsWith("\\\\.\\") || device.startsWith("\\\\?\\");
    }

    /** true si es una ruta de dispositivo de Windows (no una imagen). */
    static boolean isPhysical(String device) {
        return isWindows() && isWindowsDevice(device);
    }

    /** true si es un disco físico Windows del tipo \\.\PhysicalDriveN. */
    static boolean isPhysicalDrive(String device) {
        return isPhysical(device) && device.toUpperCase(Locale.ROOT).contains("PHYSICALDRIVE");
    }

    /**
     * Abre una sesión de acceso al medio. La sesión elige la vía más rápida disponible y
     * debe cerrarse al terminar la operación.
     */
    /**
     * Abre una sesión de acceso al medio. La sesión elige la vía más rápida disponible y
     * debe cerrarse al terminar la operación.
     *
     * En Windows, un volumen (\\.\F:) se abre directo con Java en milisegundos; un disco
     * físico (\\.\PhysicalDriveN) suele estar bloqueado y requiere PowerShell.
     */
    static Medium openMedium(String device) throws IOException {
        if (isWindowsDevice(device)) {
            try {
                return new FileMedium(new RandomAccessFile(device, "rw"));
            } catch (IOException ex) {
                // Windows rechaza el acceso directo ("El parámetro no es correcto" o
                // "Acceso denegado"): se usa PowerShell, también para volúmenes como \\.\F:.
                return new PowershellMedium(device);
            }
        }
        return new FileMedium(new RandomAccessFile(device, "rw"));
    }

    /** Interfaz común de acceso a bloques del medio. */
    interface Medium extends AutoCloseable {
        void read(long offset, byte[] buffer, int length) throws IOException;

        void write(long offset, byte[] data, int length) throws IOException;

        /** Tamaño conocido en bytes, o -1. */
        long size();

        /** Descripción de la vía usada (para el log). */
        String description();

        @Override
        void close() throws IOException;
    }

    private static final class FileMedium implements Medium {
        private final RandomAccessFile raf;

        FileMedium(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void read(long offset, byte[] buffer, int length) throws IOException {
            raf.seek(offset);
            raf.readFully(buffer, 0, length);
        }

        @Override
        public void write(long offset, byte[] data, int length) throws IOException {
            raf.seek(offset);
            raf.write(data, 0, length);
        }

        @Override
        public long size() {
            try {
                return raf.length();
            } catch (IOException ex) {
                return -1;
            }
        }

        @Override
        public String description() {
            return "acceso directo";
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    /**
     * Sesión de acceso mediante PowerShell, usada cuando Windows niega el acceso directo.
     * Cada llamada arranca un proceso (~300 ms), por eso se pide SIEMPRE el rango de bytes
     * que se necesita de una sola vez.
     */
    static final class PowershellMedium implements Medium {
        private final String device;
        private final Path workFile;

        PowershellMedium(String device) throws IOException {
            this.device = device;
            this.workFile = Files.createTempFile("microfs_io", ".bin");
        }

        @Override
        public void read(long offset, byte[] buffer, int length) throws IOException {
            pwshRead(offset, length);
            byte[] data = Files.readAllBytes(workFile);
            if (data.length < length) {
                throw new IOException("Lectura incompleta del dispositivo (" + data.length
                        + " de " + length + " bytes en el offset " + offset + ")");
            }
            System.arraycopy(data, 0, buffer, 0, length);
        }

        @Override
        public void write(long offset, byte[] data, int length) throws IOException {
            ensureWindowsWritable(device);
            Files.write(workFile, java.util.Arrays.copyOf(data, length));
            String script =
                    "$ErrorActionPreference='Stop'; " +
                    "try { " +
                    "  $bytes=[System.IO.File]::ReadAllBytes('" + Powershell.q(workFile.toAbsolutePath().toString()) + "'); " +
                    "  $fs=[System.IO.File]::Open('" + Powershell.q(device) + "', 'Open', 'Write', 'ReadWrite'); " +
                    "  $fs.Seek(" + offset + ", 'Begin') | Out-Null; " +
                    "  $fs.Write($bytes, 0, $bytes.Length); " +
                    "  $fs.Flush($true); $fs.Close(); " +
                    "} catch { Write-Error $_.Exception.Message; exit 1 }";
            Powershell.run(script);
        }

        private void pwshRead(long offset, int length) throws IOException {
            String script =
                    "$ErrorActionPreference='Stop'; " +
                    "try { " +
                    "  $fs=[System.IO.File]::Open('" + Powershell.q(device) + "', 'Open', 'Read', 'ReadWrite'); " +
                    "  $fs.Seek(" + offset + ", 'Begin') | Out-Null; " +
                    "  $buf=New-Object byte[] " + length + "; " +
                    "  $read=0; while ($read -lt " + length + ") { " +
                    "    $n=$fs.Read($buf, $read, " + length + " - $read); " +
                    "    if ($n -le 0) { break } $read += $n }; " +
                    "  $fs.Close(); " +
                    "  if ($read -lt " + length + " -and $read -gt 0) { $buf = $buf[0..($read-1)] } " +
                    "  [System.IO.File]::WriteAllBytes('" + Powershell.q(workFile.toAbsolutePath().toString()) + "', $buf); " +
                    "} catch { Write-Error $_.Exception.Message; exit 1 }";
            Powershell.run(script);
        }

        @Override
        public long size() {
            return -1;
        }

        @Override
        public String description() {
            return "PowerShell";
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(workFile);
        }
    }

    // ==================== Operaciones puntuales (compatibilidad) ====================

    /** Tamaño del medio en bytes, o -1 si es desconocido. */
    static long size(String device) throws IOException {
        if (isWindowsDevice(device)) return -1;
        try (RandomAccessFile raf = new RandomAccessFile(device, "r")) {
            return raf.length();
        }
    }

    static void read(String device, long offset, byte[] buffer, int length) throws IOException {
        if (length <= 0) return;
        try (Medium medium = openMedium(device)) {
            medium.read(offset, buffer, length);
        }
    }

    static void write(String device, long offset, byte[] data, int length) throws IOException {
        if (length <= 0) return;
        try (Medium medium = openMedium(device)) {
            medium.write(offset, data, length);
        }
    }

    /** Crea una imagen de ceros del tamaño indicado. */
    static void createImage(String path, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.setLength(size);
        }
    }

    /** Sugiere un tamaño de imagen apto para el 6502 (máx. 32 MB de datos). */
    static long limitForMicroFS(long requested) {
        return Math.min(requested, MicroFSManager.MAX_MEDIA_BYTES);
    }

    // ==================== Dispositivos Windows ====================

    static int physicalDiskNumber(String device) throws IOException {
        int idx = device.toUpperCase(Locale.ROOT).indexOf("PHYSICALDRIVE");
        if (idx < 0) throw new IOException("Ruta de dispositivo no reconocida: " + device);
        String num = device.substring(idx + "PhysicalDrive".length()).replaceAll("[^0-9].*$", "");
        if (num.isEmpty()) throw new IOException("Número de disco inválido en: " + device);
        return Integer.parseInt(num);
    }

    /**
     * Comprueba que se puede escribir en el disco físico: no puede ser el disco del sistema
     * y no debe tener una letra de unidad asignada.
     */
    static void ensureWindowsWritable(String device) throws IOException {
        if (!isPhysicalDrive(device)) return;
        int number = physicalDiskNumber(device);

        String sysDisks = Powershell.run(
                "$ErrorActionPreference='SilentlyContinue'; " +
                "Get-Disk | Where-Object { $_.IsSystem -or $_.IsBoot } | ForEach-Object { $_.Number }");
        for (String line : sysDisks.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty() && t.equals(String.valueOf(number))) {
                throw new IOException("El disco " + number + " contiene el sistema operativo. " +
                        "Operación bloqueada por seguridad.");
            }
        }

        String accessPaths = Powershell.run(
                "$ErrorActionPreference='SilentlyContinue'; " +
                "foreach ($p in Get-Partition -DiskNumber " + number + ") { " +
                "  foreach ($a in $p.AccessPaths) { Write-Output $a } }");
        List<String> volumes = new ArrayList<>();
        for (String line : accessPaths.split("\\R")) {
            String t = line.trim();
            if (t.length() == 2 && t.charAt(1) == ':') volumes.add(t);
        }
        if (!volumes.isEmpty()) {
            throw new IOException("La SD está montada como " + String.join(", ", volumes) + ".\n\n" +
                    "Windows no permite escribir en el disco físico mientras la unidad tiene letra.\n" +
                    "Quita la letra desde Administración de discos y vuelve a intentarlo.");
        }
    }

    /** Ejecuta scripts de PowerShell capturando stdout; falla si el código de salida no es 0. */
    static final class Powershell {
        private Powershell() {}

        /**
         * Ejecuta un script. Se escribe en un archivo .ps1 temporal y se lanza con -File:
         * pasar scripts largos por -Command hace que Windows recorte la línea de comandos y el
         * script se ejecute truncado (escapó como copias que escribían 0 bytes).
         */
        static String run(String script) throws IOException {
            byte[] outBytes;
            int exit;
            java.nio.file.Path ps1 = writeScript(script);
            try {
                ProcessBuilder pb = scriptProcess(ps1);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = process.getInputStream().read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                }
                outBytes = buffer.toByteArray();
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrumpido", e);
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(ps1);
                } catch (IOException ignored) {
                    // temporal
                }
            }
            String output = new String(outBytes, StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IOException("PowerShell falló (" + exit + "): " + output.trim());
            }
            return output;
        }

        static String q(String s) {
            return s.replace("'", "''");
        }

        /** Escribe el script en un .ps1 temporal (UTF-8 con BOM) y devuelve la ruta. */
        private static java.nio.file.Path writeScript(String script) throws IOException {
            java.nio.file.Path ps1 = java.nio.file.Files.createTempFile("microfs", ".ps1");
            String contenido = "$OutputEncoding=[Console]::OutputEncoding=[Text.Encoding]::UTF8;\r\n"
                    + "$ErrorActionPreference='Stop';\r\n" + script;
            byte[] bytes = contenido.getBytes(StandardCharsets.UTF_8);
            byte[] withBom = new byte[bytes.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(bytes, 0, withBom, 3, bytes.length);
            java.nio.file.Files.write(ps1, withBom);
            return ps1;
        }

        private static ProcessBuilder scriptProcess(java.nio.file.Path ps1) {
            return new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", ps1.toAbsolutePath().toString());
        }

        /**
         * Ejecuta un script largo (copias de medios) informando del progreso a partir del
         * tamaño del archivo que el script va generando.
         *
         * @param watched archivo cuyo crecimiento representa el avance
         * @param total tamaño final esperado
         */
        static void runWatching(String script, java.io.File watched, long total,
                                java.util.function.DoubleConsumer progress) throws IOException {
            java.nio.file.Path ps1 = writeScript(script);
            Process process;
            try {
                process = scriptProcess(ps1).start();
            } catch (IOException ex) {
                try {
                    java.nio.file.Files.deleteIfExists(ps1);
                } catch (IOException ignored) {
                    // temporal
                }
                throw new IOException("No se pudo iniciar PowerShell", ex);
            }

            // Leer la salida en segundo plano para que el proceso no se bloquee.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[4096];
                int n;
                try {
                    while ((n = process.getInputStream().read(chunk)) > 0) {
                        buffer.write(chunk, 0, n);
                    }
                } catch (IOException ignored) {
                    // el proceso terminó
                }
            }, "pwsh-output");
            reader.setDaemon(true);
            reader.start();

            long last = -1;
            while (process.isAlive()) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new java.io.InterruptedIOException("Copia cancelada");
                }
                long size = watched.length();
                if (size != last && progress != null) {
                    last = size;
                    progress.accept(total > 0 ? Math.min(1.0, (double) size / total) : 0);
                }
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("Copia cancelada", e);
                }
            }

            int exit;
            try {
                exit = process.exitValue();
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrumpido", e);
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(ps1);
                } catch (IOException ignored) {
                    // temporal
                }
            }
            String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IOException("PowerShell falló (" + exit + "): " + output.trim());
            }
            if (progress != null && total > 0) progress.accept(1.0);
        }
    }
}
