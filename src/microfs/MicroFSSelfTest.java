package microfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prueba de compatibilidad con el driver microfs-6502-cc65.
 * Simula la imagen en memoria y la exporta a un archivo temporal .img.
 *
 * Ejecutar: java -cp bin microfs.MicroFSSelfTest
 */
public final class MicroFSSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("microfs-test");
        Path img = dir.resolve("sd.img");
        Files.deleteIfExists(img);

        testFormatLayout(img);
        testFilesAreContiguous(img);
        testRoundTripManyFiles(img);
        testNoRoom(img);
        testUndoJournal(img);
        testRename(img);
        testPositionsPreserved(img);

        System.out.println();
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
        if (failed > 0) System.exit(1);
    }

    // ==================== Pruebas ====================

    /** mfs_format(): "FM" en 0-1, next_sector = 1 en 6-7, resto del sector 0 a cero. */
    private static void testFormatLayout(Path img) throws IOException {
        Files.write(img, new byte[512 * 64]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.flush();

        byte[] sector = readSector(img, 0);
        check("formato: firma 'F'", sector[0] == 0x46);
        check("formato: firma 'M'", sector[1] == 0x4D);
        check("formato: next_sector = 1", sector[6] == 1 && sector[7] == 0);
        boolean zero = true;
        for (int i = 2; i < 512; i++) {
            if (i == 6 || i == 7) continue;
            if (sector[i] != 0) { zero = false; break; }
        }
        check("formato: resto del sector 0 a cero", zero);
        check("formato: nextSector reportado", fs.getNextSector() == 1);
    }

    /** mfs_create(): entrada de 32 bytes con nombre NUL, sector, tamaño y sectores reservados. */
    private static void testFilesAreContiguous(Path img) throws IOException {
        Files.write(img, new byte[512 * 64]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.writeFile("hello.txt", "Hello World!\r\n".getBytes(StandardCharsets.US_ASCII));
        fs.writeFile("DATA.BIN", new byte[700]);
        fs.flush();

        byte[] s0 = readSector(img, 0);
        check("nombre en mayúsculas: HELLO.TXT", nameAt(s0, 16).equals("HELLO.TXT"));
        check("tamaño HELLO.TXT = 14", u16(s0, 16 + 14) == 14);
        check("sector inicial HELLO.TXT = 1", u16(s0, 16 + 12) == 1);
        check("sectores reservados HELLO.TXT = 1", u16(s0, 16 + 16) == 1);
        check("sector inicial DATA.BIN = 2", u16(s0, 48 + 12) == 2);
        check("sectores reservados DATA.BIN = 2", u16(s0, 48 + 16) == 2);
        check("next_sector = 4", u16(s0, 6) == 4);
        check("nombre terminado en NUL", s0[16 + 9] == 0);

        byte[] data = readSector(img, 1);
        check("datos HELLO.TXT en sector 1",
                new String(data, 0, 14, StandardCharsets.US_ASCII).equals("Hello World!\r\n"));
        check("resto del sector 1 a cero", data[14] == 0 && data[511] == 0);

        MicroFSManager reopened = new MicroFSManager();
        reopened.mount(img.toString(), 0);
        check("relectura: 2 archivos", reopened.getFileCount() == 2);
        check("relectura: contenido DATA.BIN",
                Arrays.equals(reopened.readFile("DATA.BIN"), new byte[700]));
        check("relectura: contenido HELLO.TXT",
                new String(reopened.readFile("HELLO.TXT"), StandardCharsets.US_ASCII).equals("Hello World!\r\n"));
    }

    private static void testRoundTripManyFiles(Path img) throws IOException {
        Files.write(img, new byte[512 * 128]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        for (int i = 0; i < MicroFSManager.MAX_ENTRIES_IN_SECTOR; i++) {
            byte[] content = new byte[i * 37 + 1];
            Arrays.fill(content, (byte) ('A' + i));
            fs.writeFile("F" + i + ".BIN", content);
        }
        fs.flush();

        MicroFSManager reopened = new MicroFSManager();
        reopened.mount(img.toString(), 0);
        boolean allOk = true;
        int expectedSector = 1;
        for (int i = 0; i < MicroFSManager.MAX_ENTRIES_IN_SECTOR; i++) {
            byte[] content = reopened.readFile("F" + i + ".BIN");
            if (content.length != i * 37 + 1) allOk = false;
            MicroFSManager.Entry e = reopened.find("F" + i + ".BIN");
            if (e.getStartSector() != expectedSector) allOk = false;
            expectedSector += e.getSectors();
        }
        check("archivos con contenido íntegro y sectores contiguos", allOk);

        try {
            fs.writeFile("F17.BIN", new byte[10]);
            check("archivo 16 rechazado (no cabe en el sector 0)", false);
        } catch (IOException ex) {
            check("archivo 16 rechazado (no cabe en el sector 0)", true);
        }

        fs.delete("F0.BIN");
        fs.writeFile("NEW.BIN", new byte[5]);
        fs.flush();
        MicroFSManager after = new MicroFSManager();
        after.mount(img.toString(), 0);
        check("hueco reutilizado tras eliminar", after.getFileCount() == MicroFSManager.MAX_ENTRIES_IN_SECTOR && after.find("NEW.BIN") != null);
    }

    private static void testNoRoom(Path img) throws IOException {
        Files.write(img, new byte[512 * 4]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.writeFile("BIG.BIN", new byte[512 * 10]);
        try {
            fs.flush();
            check("rechaza guardar sin espacio", false);
        } catch (IOException ex) {
            check("rechaza guardar sin espacio", true);
        }
    }

    private static void testUndoJournal(Path img) throws IOException {
        Files.write(img, new byte[512 * 16]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.writeFile("KEEP.TXT", "v1".getBytes(StandardCharsets.US_ASCII));
        fs.flush();

        byte[] original = readSector(img, 0);
        WriteJournal journal = new WriteJournal();
        journal.capture(img.toString(), 0);

        MicroFSManager bad = new MicroFSManager();
        bad.mount(img.toString(), 0);
        bad.deleteAll();
        bad.flush();

        MicroFSManager emptied = new MicroFSManager();
        emptied.mount(img.toString(), 0);
        check("la operación eliminó la tabla", emptied.getFileCount() == 0);

        journal.rollback(img.toString(), 0);
        check("deshacer restaura la tabla", Arrays.equals(original, readSector(img, 0)));
        MicroFSManager restored = new MicroFSManager();
        restored.mount(img.toString(), 0);
        check("deshacer recupera el archivo", new String(restored.readFile("KEEP.TXT"),
                StandardCharsets.US_ASCII).equals("v1"));
    }

    private static void testRename(Path img) throws IOException {
        Files.write(img, new byte[512 * 32]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.writeFile("OLD.TXT", "contenido".getBytes(StandardCharsets.US_ASCII));
        fs.writeFile("OTRO.TXT", "otro".getBytes(StandardCharsets.US_ASCII));
        fs.flush();

        MicroFSManager.Entry before = fs.find("OLD.TXT");
        fs.rename("old.txt", "nuevo.txt");
        fs.flush();

        MicroFSManager reopened = new MicroFSManager();
        reopened.mount(img.toString(), 0);
        MicroFSManager.Entry after = reopened.find("NUEVO.TXT");
        check("renombrar: nombre en mayúsculas", after != null);
        check("renombrar: archivo anterior ya no existe", reopened.find("OLD.TXT") == null);
        check("renombrar: conserva sector inicial y reserva",
                after != null && after.getStartSector() == before.getStartSector()
                        && after.getSectors() == before.getSectors());
        check("renombrar: conserva el contenido",
                new String(reopened.readFile("NUEVO.TXT"), StandardCharsets.US_ASCII).equals("contenido"));
        check("renombrar: no afecta a otros archivos",
                new String(reopened.readFile("OTRO.TXT"), StandardCharsets.US_ASCII).equals("otro"));

        try {
            reopened.rename("NUEVO.TXT", "OTRO.TXT");
            check("renombrar: rechaza nombre duplicado", false);
        } catch (IOException ex) {
            check("renombrar: rechaza nombre duplicado", true);
        }
        try {
            reopened.rename("NUEVO.TXT", "NOMBRE_DEMASIADO_LARGO.TXT");
            check("renombrar: rechaza nombre de más de 11 caracteres", false);
        } catch (IOException ex) {
            check("renombrar: rechaza nombre de más de 11 caracteres", true);
        }
        try {
            reopened.rename("NOEXISTE.TXT", "X.TXT");
            check("renombrar: rechaza archivo inexistente", false);
        } catch (IOException ex) {
            check("renombrar: rechaza archivo inexistente", true);
        }
    }

    /** Renombrar, borrar o editar sin crecer no debe reubicar los archivos. */
    private static void testPositionsPreserved(Path img) throws IOException {
        Files.write(img, new byte[512 * 64]);
        MicroFSManager fs = new MicroFSManager();
        fs.setTarget(img.toString(), 0);
        fs.format();
        fs.writeFile("A.BIN", new byte[600]);     // sector 1, 2 sectores
        fs.writeFile("B.BIN", new byte[2000]);    // sector 3, 4 sectores
        fs.flush();

        List<Integer> antes = sectores(img);

        // 1) Renombrar no recompacta
        MicroFSManager m = new MicroFSManager();
        m.mount(img.toString(), 0);
        m.rename("A.BIN", "Z.BIN");
        boolean rebuilt = m.flush();
        check("renombrar no recompacta", !rebuilt);
        check("renombrar conserva los sectores", sectores(img).equals(antes));

        // 2) Borrar no recompacta ni reutiliza los sectores
        m = new MicroFSManager();
        m.mount(img.toString(), 0);
        m.delete("Z.BIN");
        rebuilt = m.flush();
        check("borrar no recompacta", !rebuilt);
        MicroFSManager reopened = new MicroFSManager();
        reopened.mount(img.toString(), 0);
        check("borrar conserva la posición del resto",
                reopened.find("B.BIN").getStartSector() == antes.get(1));

        // 3) Editar con el mismo tamaño no recompacta
        m = new MicroFSManager();
        m.mount(img.toString(), 0);
        m.writeFile("B.BIN", new byte[2000]);
        rebuilt = m.flush();
        check("editar sin crecer no recompacta", !rebuilt);
        check("editar sin crecer conserva los sectores", sectores(img).get(0) == antes.get(1));

        // 4) Crecer sí exige reasignar
        m = new MicroFSManager();
        m.mount(img.toString(), 0);
        m.writeFile("B.BIN", new byte[9000]);
        rebuilt = m.flush();
        check("crecer requiere reasignar", rebuilt);
    }

    private static List<Integer> sectores(Path img) throws IOException {
        MicroFSManager m = new MicroFSManager();
        m.mount(img.toString(), 0);
        List<Integer> out = new ArrayList<>();
        for (MicroFSManager.Entry e : m.list()) out.add(e.getStartSector());
        java.util.Collections.sort(out);
        return out;
    }

    // ==================== Utilidades ====================

    private static byte[] readSector(Path img, int sector) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(img.toFile(), "r")) {
            raf.seek((long) sector * 512);
            byte[] b = new byte[512];
            raf.readFully(b);
            return b;
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static String nameAt(byte[] table, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12 && table[off + i] != 0; i++) {
            sb.append((char) (table[off + i] & 0xFF));
        }
        return sb.toString();
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [OK]   " + description);
        } else {
            failed++;
            System.out.println("  [FALLA] " + description);
        }
    }
}
