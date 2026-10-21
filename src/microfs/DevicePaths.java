package microfs;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Resuelve la ruta con la que se puede abrir el medio para lectura y escritura.
 *
 * En Windows, un disco físico puede NO abrirse como \\.\PhysicalDriveN (Acceso denegado
 * incluso como Administrador; es habitual en lectores USB multitarjeta), mientras que la
 * unidad montada sí se abre como dispositivo: \\.\F:. Se prueban ambas rutas.
 */
final class DevicePaths {

    private DevicePaths() {}

    /** true si el medio admite lectura de bloques crudos. */
    static boolean probe(String path) {
        try (DeviceAccess.Medium medium = DeviceAccess.openMedium(path)) {
            byte[] probe = new byte[1];
            medium.read(0, probe, 1);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Devuelve la ruta utilizable para el medio indicado. Si la ruta dada ya funciona se
     * devuelve tal cual; si es un disco de Windows, se prueban las letras de unidad montadas.
     */
    static String resolve(String path, StringBuilder notes) {
        if (!DeviceAccess.isWindowsDevice(path)) return path;

        if (!DeviceAccess.isPhysicalDrive(path)) {
            return path;
        }

        // Para discos físicos, la unidad montada es la vía más rápida y la única que
        // Windows suele permitir abrir con Java.
        List<String> letters = driveLetters(path);
        if (!letters.isEmpty()) {
            String volume = "\\\\.\\" + letters.get(0);
            if (notes != null) {
                notes.append("Se usará la unidad ").append(letters.get(0))
                     .append(" en lugar de ").append(path)
                     .append(" (Windows no permite abrir el disco físico directamente).");
            }
            return volume;
        }

        if (probe(path)) return path;

        for (String letter : letters) {
            String volume = "\\\\.\\" + letter;
            if (probe(volume)) {
                if (notes != null) {
                    notes.append("Se usará la unidad ").append(letter).append(" en lugar de ").append(path).append('.');
                }
                return volume;
            }
        }
        return path;
    }

    /** Letras de unidad asignadas al disco físico indicado. */
    static List<String> driveLetters(String physicalPath) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            int number = DeviceAccess.physicalDiskNumber(physicalPath);
            String out = DeviceAccess.Powershell.run(
                    "$ErrorActionPreference='SilentlyContinue'; " +
                    "foreach ($p in (Get-Partition -DiskNumber " + number + ")) { " +
                    "  if ($p.DriveLetter) { Write-Output ($p.DriveLetter + ':') } }");
            for (String line : out.split("\\R")) {
                String t = line.trim().toUpperCase(Locale.ROOT);
                if (t.length() == 2 && t.charAt(1) == ':') result.add(t);
            }
        } catch (Exception ignored) {
            // sin letras disponibles
        }
        return result;
    }
}
