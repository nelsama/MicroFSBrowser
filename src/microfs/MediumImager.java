package microfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.DoubleConsumer;

/**
 * Copia medios completos byte a byte, como Win32DiskImager:
 *
 *   - dispositivo/imagen  ->  archivo .img   (respaldo de la tarjeta)
 *   - archivo .img        ->  dispositivo    (grabar la imagen, como en un pendrive)
 *
 * Nunca escribe en un disco del sistema. La verificación opcional relee el destino
 * y compara.
 */
final class MediumImager {

    private static final int CHUNK = 256 * 1024;
    /** Tamaño del bloque cuando se copia dentro del mismo proceso Java. */
    private static final int FAST_CHUNK = 4 * 1024 * 1024;

    private MediumImager() {}

    /** Error con detalle suficiente para el usuario. */
    static final class CopyException extends IOException {
        private static final long serialVersionUID = 1L;

        CopyException(String message) {
            super(message);
        }

        CopyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Información previa a la copia. */
    static final class Plan {
        long sourceSize;
        long targetSize = -1;
        boolean sourceIsDevice;
        boolean targetIsDevice;
        String notes = "";
    }

    /**
     * Comprueba que la copia tenga sentido.
     *
     * @param source imagen o dispositivo de origen
     * @param target imagen o dispositivo de destino
     */
    static Plan plan(String source, String target) throws IOException {
        Plan plan = new Plan();
        plan.sourceIsDevice = DeviceAccess.isWindowsDevice(source) || isLinuxDevice(source);
        plan.targetIsDevice = DeviceAccess.isWindowsDevice(target) || isLinuxDevice(target);

        if (source.equals(target)) {
            throw new CopyException("El origen y el destino son el mismo medio.");
        }
        if (!exists(source)) {
            throw new CopyException("No existe el origen: " + source);
        }

        plan.sourceSize = plan.sourceIsDevice ? deviceSize(source) : fileSize(source);
        if (plan.sourceSize <= 0) {
            throw new CopyException("El origen no tiene datos legibles (" + plan.sourceSize + " bytes).");
        }
        if (!isLinuxDevice(source) && !DeviceAccess.isWindowsDevice(source)
                && plan.sourceSize % MicroFSManager.SECTOR_SIZE != 0) {
            plan.notes = "El origen no es múltiplo de 512 bytes; se copiará tal cual.";
        }

        if (plan.targetIsDevice) {
            guardSystemDisk(target);
            plan.targetSize = deviceSize(target);
            if (plan.targetSize > 0 && plan.targetSize < plan.sourceSize) {
                throw new CopyException("La imagen (" + MicroFSManager.humanSize(plan.sourceSize)
                        + ") no cabe en el dispositivo (" + MicroFSManager.humanSize(plan.targetSize) + ").");
            }
            if (plan.targetSize > plan.sourceSize) {
                plan.notes = "El destino es mayor que la imagen; los bytes sobrantes no se tocan.";
            }
        } else if (!targetExistsOrParentWritable(target)) {
            throw new CopyException("No se puede escribir en " + target);
        }
        return plan;
    }

    /**
     * Copia el origen completo al destino.
     *
     * Cuando hay un dispositivo Windows por medio, la copia la hace UN ÚNICO proceso
     * PowerShell (abrir un proceso cuesta ~300 ms; hacerlo por bloque tarda minutos u
     * horas). El progreso se obtiene mirando cuánto ha crecido el archivo destino.
     *
     * @param verify releer el destino y comparar al terminar
     * @param progress 0.0 a 1.0 (puede ser null)
     */
    static void copy(String source, String target, boolean verify, DoubleConsumer progress)
            throws IOException {
        Plan plan = plan(source, target);

        if (plan.targetIsDevice) {
            copyToDevice(source, target, plan, progress);
        } else {
            copyToFile(source, target, plan, progress);
        }

        if (verify) {
            verifyCopy(source, target, plan.sourceSize, progress);
        }
    }

    /** Destino = dispositivo físico: siempre se escribe con PowerShell. */
    private static void copyToDevice(String source, String target, Plan plan, DoubleConsumer progress)
            throws IOException {
        DeviceAccess.ensureWindowsWritable(target);

        if (usesPowershell(source)) {
            if (usesPowershell(target)) {
                // Dispositivo -> dispositivo: pasar por un archivo temporal.
                Path tmp = Files.createTempFile("microfs_img", ".tmp");
                try {
                    copyDeviceToFile(source, tmp.toAbsolutePath().toString(), plan.sourceSize, progress);
                    copyFileToDevice(tmp.toAbsolutePath().toString(), target, plan.sourceSize, null);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } else {
                // Si el destino no se puede abrir con Java, se escribe por PowerShell igualmente.
                copyFileToDevice(source, target, plan.sourceSize, progress);
            }
            return;
        }

        // Origen = imagen: se escribe todo en un proceso si el destino exige PowerShell.
        if (usesPowershell(target)) {
            copyFileToDevice(source, target, plan.sourceSize, progress);
        } else {
            copyWithJava(source, target, plan, progress);
        }
    }

    /** Destino = archivo .img: lectura del medio por PowerShell si hace falta. */
    private static void copyToFile(String source, String target, Plan plan, DoubleConsumer progress)
            throws IOException {
        if (usesPowershell(source)) {
            copyDeviceToFile(source, target, plan.sourceSize, progress);
        } else {
            copyWithJava(source, target, plan, progress);
        }
    }

    /**
     * True si hay que usar PowerShell para este medio.
     *
     * Windows nunca permite abrir un disco físico ni un volumen crudo con Java, así que
     * cualquier ruta \\.\… o \\?\… va por PowerShell. No se sondea con RandomAccessFile porque
     * algunos volúmenes se abren de forma intermitente (o con parámetros no válidos) y daba
     * lugar a intentos con Java que fallaban con "El parámetro no es correcto".
     */
    private static boolean usesPowershell(String path) {
        if (DeviceAccess.isWindowsDevice(path)) return true;
        return !DeviceAccess.isWindows() && path.startsWith("/dev/");
    }

    /** Copia directa con Java (imágenes, o cuando el dispositivo sí se puede abrir). */
    private static void copyWithJava(String source, String target, Plan plan, DoubleConsumer progress)
            throws IOException {
        try (DeviceAccess.Medium in = openForRead(source);
             RandomAccessFile out = new RandomAccessFile(target, "rw")) {
            out.setLength(0);
            byte[] buffer = new byte[FAST_CHUNK];
            long done = 0;
            while (done < plan.sourceSize) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Copia cancelada");
                }
                int n = (int) Math.min(FAST_CHUNK, plan.sourceSize - done);
                in.read(done, buffer, n);
                out.seek(done);
                out.write(buffer, 0, n);
                done += n;
                report(progress, done, plan.sourceSize);
            }
        }
    }

    /** Ruta de archivo lista para incrustar en un script PowerShell (normalizada y escapada). */
    private static String psPath(String path) throws IOException {
        String canonical = new java.io.File(path).getCanonicalPath();
        return DeviceAccess.Powershell.q(canonical);
    }

    /**
     * Dispositivo -> archivo en un solo proceso PowerShell.
     * El script lee el medio y escribe el archivo; la JVM solo vigila el tamaño resultante.
     */
    private static void copyDeviceToFile(String device, String target, long total,
                                         DoubleConsumer progress) throws IOException {
        java.io.File file = new java.io.File(target);
        java.io.File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new CopyException("No se puede crear la carpeta " + parent);
        }
        Files.deleteIfExists(Paths.get(target));

        String script =
                "$ErrorActionPreference='Stop'; " +
                "try { " +
                "  $src=[System.IO.File]::Open('" + DeviceAccess.Powershell.q(device) + "', 'Open', 'Read', 'ReadWrite'); " +
                "  $dst=[System.IO.File]::Create('" + psPath(target) + "'); " +
                "  $buf=New-Object byte[] (4MB); $left=" + total + "; " +
                "  while ($left -gt 0) { " +
                "    $want=[Math]::Min($buf.Length, $left); " +
                "    $n=$src.Read($buf, 0, $want); if ($n -le 0) { break } " +
                "    $dst.Write($buf, 0, $n); $left -= $n }; " +
                "  $dst.Flush(); $dst.Close(); $src.Close(); " +
                "} catch { Write-Error $_.Exception.Message; exit 1 }";

        DeviceAccess.Powershell.runWatching(script, file, total, progress);

        long size = Files.size(file.toPath());
        if (size != total) {
            throw new CopyException("La copia quedó incompleta: " + MicroFSManager.humanSize(size)
                    + " de " + MicroFSManager.humanSize(total) + ".");
        }
    }

    /** Archivo -> dispositivo en un solo proceso PowerShell. */
    private static void copyFileToDevice(String source, String device, long total,
                                         DoubleConsumer progress) throws IOException {
        if (DeviceAccess.isWindows() && DeviceAccess.isPhysicalDrive(device)) {
            DeviceAccess.ensureWindowsWritable(device);
        }
        String script =
                "try { " +
                "  $src=[System.IO.File]::OpenRead('" + psPath(source) + "'); " +
                "  $dst=[System.IO.File]::Open('" + DeviceAccess.Powershell.q(device) + "', 'Open', 'Write', 'ReadWrite'); " +
                "  $buf=New-Object byte[] (4MB); $total=[long]0; $n=0; " +
                "  do { " +
                "    $n=$src.Read($buf, 0, $buf.Length); " +
                "    if ($null -eq $n) { break } " +
                "    if ($n -gt 0) { $dst.Write($buf, 0, $n); $total += $n } " +
                "  } while ($n -gt 0); " +
                "  $dst.Flush($true); $dst.Close(); $src.Close(); " +
                "  Write-Output $total " +
                "} catch { Write-Error $_.Exception.Message; exit 1 }";
        DeviceAccess.Powershell.run(script);
        report(progress, total, total);
    }

    // ==================== Utilidades internas ====================

    private static void report(DoubleConsumer progress, long done, long total) {
        if (progress != null && total > 0) {
            progress.accept((double) done / total);
        }
    }

    /** Relee el destino y compara con el origen. También devuelve el primer byte distinto. */
    static void verifyCopy(String source, String target, long size) throws IOException {
        verifyCopy(source, target, size, null);
    }

    /** Relee el destino y compara con el origen, informando del progreso. */
    static void verifyCopy(String source, String target, long size, DoubleConsumer progress)
            throws IOException {
        if (usesPowershell(source) || usesPowershell(target)) {
            // Comparar dispositivo contra archivo en un solo proceso PowerShell.
            String device = usesPowershell(source) ? source : target;
            String file = usesPowershell(source) ? target : source;
            verifyInPowershell(device, file, size, progress);
            return;
        }

        try (DeviceAccess.Medium a = openForRead(source);
             DeviceAccess.Medium b = openForRead(target)) {
            byte[] bufA = new byte[FAST_CHUNK];
            byte[] bufB = new byte[FAST_CHUNK];
            long offset = 0;
            while (offset < size) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Verificación cancelada");
                }
                int n = (int) Math.min(FAST_CHUNK, size - offset);
                a.read(offset, bufA, n);
                b.read(offset, bufB, n);
                for (int i = 0; i < n; i++) {
                    if (bufA[i] != bufB[i]) {
                        long pos = offset + i;
                        throw new CopyException(String.format(
                                "La verificación falló: el byte %d (sector %d, offset %d) no coincide.",
                                pos, pos / MicroFSManager.SECTOR_SIZE, pos % MicroFSManager.SECTOR_SIZE));
                    }
                }
                offset += n;
                report(progress, offset, size);
            }
        }
    }

    /** Contrasta dispositivo y archivo en un solo proceso; informa del byte del primer fallo. */
    private static void verifyInPowershell(String device, String file, long size,
                                          DoubleConsumer progress) throws IOException {
        String script =
                "$ErrorActionPreference='Stop'; " +
                "try { " +
                "  $dev=[System.IO.File]::Open('" + DeviceAccess.Powershell.q(device) + "', 'Open', 'Read', 'ReadWrite'); " +
                "  $img=[System.IO.File]::OpenRead('" + psPath(file) + "'); " +
                "  $a=New-Object byte[] (4MB); $b=New-Object byte[] (4MB); $pos=[long]0; $left=[long]" + size + "; " +
                "  while ($left -gt 0) { " +
                "    $want=[int][Math]::Min($a.Length, $left); " +
                "    $na=$dev.Read($a, 0, $want); $nb=$img.Read($b, 0, $want); " +
                "    if ($na -le 0 -or $nb -le 0) { throw ('Fin inesperado en el byte ' + $pos) } " +
                "    for ($i=0; $i -lt $want; $i++) { " +
                "      if ($a[$i] -ne $b[$i]) { throw ('Diferencia en el byte ' + ($pos + $i)) } }; " +
                "    $pos += $want; $left -= $want }; " +
                "  $dev.Close(); $img.Close(); " +
                "  Write-Output ('OK ' + $pos); " +
                "} catch { Write-Error $_.Exception.Message; exit 1 }";
        DeviceAccess.Powershell.run(script);
    }

    private static DeviceAccess.Medium openForRead(String source) throws IOException {
        return DeviceAccess.openMedium(source);
    }

    private static boolean exists(String path) {
        if (DeviceAccess.isWindowsDevice(path) || isLinuxDevice(path)) return true;
        return new java.io.File(path).exists();
    }

    private static boolean targetExistsOrParentWritable(String target) {
        java.io.File file = new java.io.File(target);
        if (file.exists()) return file.canWrite();
        java.io.File parent = file.getAbsoluteFile().getParentFile();
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    private static long fileSize(String path) throws IOException {
        try {
            return Files.size(Paths.get(path));
        } catch (IOException ex) {
            throw new CopyException("No se puede leer " + path + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Tamaño del dispositivo. En Windows no se puede abrir \\.\PhysicalDriveN, así que se
     * consulta por WMI/PowerShell (Get-Disk), o por la letra de unidad si es un volumen.
     */
    static long deviceSize(String device) throws IOException {
        boolean esDispositivo = DeviceAccess.isWindowsDevice(device) || isLinuxDevice(device);
        if (!esDispositivo) {
            return fileSize(device);
        }
        if (DeviceAccess.isWindows()) {
            try {
                if (DeviceAccess.isPhysicalDrive(device)) {
                    int number = DeviceAccess.physicalDiskNumber(device);
                    String out = DeviceAccess.Powershell.run(
                            "$ErrorActionPreference='SilentlyContinue'; " +
                            "$d = Get-Disk -Number " + number + "; if ($d) { [string]$d.Size } else { '-1' }");
                    return parseLong(out);
                }
                String letter = device.replace("\\\\.\\", "").replace("\\\\?\\", "");
                if (letter.length() >= 2 && letter.charAt(1) == ':') {
                    String out = DeviceAccess.Powershell.run(
                            "$ErrorActionPreference='SilentlyContinue'; " +
                            "try { $d = Get-Partition -DriveLetter '" + letter.charAt(0) + "'; " +
                            "  if ($d) { [string]$d.Size } else { '-1' } } catch { '-1' }");
                    long size = parseLong(out);
                    if (size > 0) return size;
                }
            } catch (IOException ignored) {
                // sin tamaño conocido
            }
            return -1;
        }
        try (RandomAccessFile raf = new RandomAccessFile(device, "r")) {
            return raf.length();
        }
    }

    private static long parseLong(String out) {
        for (String line : out.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                // seguir buscando
            }
        }
        return -1;
    }

    private static boolean isLinuxDevice(String path) {
        return !DeviceAccess.isWindows() && path.startsWith("/dev/");
    }

    /** Bloquea la operación si el destino es el disco del sistema operativo. */
    static void guardSystemDisk(String device) throws IOException {
        if (DeviceAccess.isWindows()) {
            if (!DeviceAccess.isPhysicalDrive(device)) {
                // Un volumen con letra C: es el del sistema salvo que esté en otro disco.
                String letter = device.replace("\\\\.\\", "").replace("\\\\?\\", "");
                if (letter.regionMatches(true, 0, "C:", 0, 2)) {
                    throw new CopyException("No se puede grabar la imagen en C: (unidad del sistema).");
                }
                return;
            }
            int number = DeviceAccess.physicalDiskNumber(device);
            String out = DeviceAccess.Powershell.run(
                    "$ErrorActionPreference='SilentlyContinue'; " +
                    "Get-Disk | Where-Object { $_.IsSystem -or $_.IsBoot } | ForEach-Object { $_.Number }");
            for (String line : out.split("\\R")) {
                if (line.trim().equals(String.valueOf(number))) {
                    throw new CopyException("El disco " + number +
                            " contiene el sistema operativo. Operación bloqueada por seguridad.");
                }
            }
            return;
        }
        String real = device;
        try {
            real = Paths.get(device).toRealPath().toString();
        } catch (IOException ignored) {
            // ruta tal cual
        }
        try {
            String mounts = new String(Runtime.getRuntime().exec(new String[]{"findmnt", "-n", "-o", "SOURCE,TARGET", "/"})
                    .getInputStream().readAllBytes());
            if (mounts.contains(real)) {
                throw new CopyException("El dispositivo " + device + " contiene el sistema operativo.");
            }
        } catch (CopyException ex) {
            throw ex;
        } catch (Exception ignored) {
            // sin información de montaje
        }
    }
}
