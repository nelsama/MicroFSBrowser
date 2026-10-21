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
     * @param verify releer el destino y comparar al terminar
     * @param progress 0.0 a 1.0 (puede ser null)
     */
    static void copy(String source, String target, boolean verify, DoubleConsumer progress)
            throws IOException {
        Plan plan = plan(source, target);

        // Origen
        try (DeviceAccess.Medium in = openForRead(source)) {
            if (plan.targetIsDevice) {
                DeviceAccess.ensureWindowsWritable(target);
                try (DeviceAccess.Medium out = DeviceAccess.openMedium(target)) {
                    stream(in, out, plan.sourceSize, progress);
                }
            } else {
                try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                    out.setLength(0);
                    streamToFile(in, out, plan.sourceSize, progress);
                }
            }
        }

        if (verify) {
            verifyCopy(source, target, plan.sourceSize);
        }
    }

    // ==================== Utilidades internas ====================

    private static void stream(DeviceAccess.Medium in, DeviceAccess.Medium out, long total,
                               DoubleConsumer progress) throws IOException {
        byte[] buffer = new byte[CHUNK];
        long done = 0;
        while (done < total) {
            int n = (int) Math.min(CHUNK, total - done);
            in.read(done, buffer, n);
            out.write(done, buffer, n);
            done += n;
            report(progress, done, total);
        }
    }

    private static void streamToFile(DeviceAccess.Medium in, RandomAccessFile out, long total,
                                     DoubleConsumer progress) throws IOException {
        byte[] buffer = new byte[CHUNK];
        long done = 0;
        while (done < total) {
            int n = (int) Math.min(CHUNK, total - done);
            in.read(done, buffer, n);
            out.seek(done);
            out.write(buffer, 0, n);
            done += n;
            report(progress, done, total);
        }
    }

    private static void report(DoubleConsumer progress, long done, long total) {
        if (progress != null && total > 0) {
            progress.accept((double) done / total);
        }
    }

    /** Relee el destino y compara con el origen. También devuelve el primer byte distinto. */
    static void verifyCopy(String source, String target, long size) throws IOException {
        try (DeviceAccess.Medium a = openForRead(source);
             DeviceAccess.Medium b = openForRead(target)) {
            byte[] bufA = new byte[CHUNK];
            byte[] bufB = new byte[CHUNK];
            long offset = 0;
            while (offset < size) {
                int n = (int) Math.min(CHUNK, size - offset);
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
            }
        }
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
