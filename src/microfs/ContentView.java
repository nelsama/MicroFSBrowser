package microfs;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Presentación del contenido de un archivo MicroFS.
 * MicroFS no guarda atributos de archivo: el formato se decide por la extensión y por
 * la validez del texto, así se pueden ver programas en hexadecimal y textos como texto.
 */
public final class ContentView {

    public enum Kind { TEXT, HEX, PETSCII }

    private static final int HEX_PREVIEW_LIMIT = 1024 * 1024;

    private ContentView() {}

    public static Kind kindFor(String name, byte[] data) {
        String upper = name.toUpperCase();
        if (upper.endsWith(".PET")) return Kind.PETSCII;
        if (upper.endsWith(".PRG") || upper.endsWith(".BIN") || upper.endsWith(".ROM")
                || upper.endsWith(".SID") || upper.endsWith(".O")) {
            return Kind.HEX;
        }
        if (upper.endsWith(".PNG")) return Kind.HEX;   // sin previsualización; se exporta
        if (upper.endsWith(".WAV")) return Kind.HEX;
        if (upper.endsWith(".TXT") || upper.endsWith(".ASM") || upper.endsWith(".C")
                || upper.endsWith(".H") || upper.endsWith(".BAS") || upper.endsWith(".LST")
                || upper.endsWith(".MAP") || upper.endsWith(".LOG") || upper.endsWith(".CSV")) {
            return Kind.TEXT;
        }
        return isProbablyText(data) ? Kind.TEXT : Kind.HEX;
    }

    public static boolean isProbablyText(byte[] data) {
        if (data.length == 0) return true;
        int control = 0;
        for (byte b : data) {
            int v = b & 0xFF;
            if (v == 0) return false;
            if (v == 0x0D || v == 0x0A || v == 0x09) continue;
            if (v < 0x20 || v == 0x7F) control++;
        }
        return control * 100 / data.length < 5;
    }

    public static String asText(byte[] data, Charset charset) {
        String text = new String(data, charset);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String asUtf8(byte[] data) {
        return asText(data, StandardCharsets.UTF_8);
    }

    /** Volcado hexadecimal con offsets, 16 bytes por línea. */
    public static String hexDump(byte[] data) {
        int limit = Math.min(data.length, HEX_PREVIEW_LIMIT);
        StringBuilder sb = new StringBuilder(limit * 5);
        for (int off = 0; off < limit; off += 16) {
            sb.append(String.format("%04X  ", off));
            for (int i = 0; i < 16; i++) {
                if (off + i < limit) {
                    sb.append(String.format("%02X ", data[off + i] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(" |");
            for (int i = 0; i < 16 && off + i < limit; i++) {
                int v = data[off + i] & 0xFF;
                sb.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
            }
            sb.append("|\n");
        }
        if (limit < data.length) {
            sb.append("... (").append(data.length - limit).append(" bytes no mostrados)\n");
        }
        return sb.toString();
    }

    /** Convierte bytes PETSCII a ASCII aproximado (mapa de rangos sin usar tablas completas). */
    public static String petsciiToAscii(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v == 0x0D) {
                sb.append('\n');
            } else if ((v >= 0x20 && v <= 0x5F) || (v >= 0x61 && v <= 0x7A)) {
                sb.append((char) v);
            } else if (v >= 0xC1 && v <= 0xDA) {
                sb.append((char) ('A' + (v - 0xC1)));
            } else if (v >= 0x41 && v <= 0x5A) {
                sb.append((char) ('a' + (v - 0x41)));
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * Convierte texto de la UI a PETSCII (mayúsculas 0x41-0x5A y retorno de carro 0x0D).
     * Se usa para importar listados BASIC en tarjetas C64/C128/X16.
     */
    public static byte[] asciiToPetscii(String text) {
        StringBuilder src = new StringBuilder(text.replace("\r\n", "\n").replace('\r', '\n'));
        byte[] out = new byte[src.length()];
        int n = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\n') {
                out[n++] = 0x0D;
            } else if (c >= 'a' && c <= 'z') {
                out[n++] = (byte) (c - 'a' + 0x41);
            } else if (c <= 0x7F) {
                out[n++] = (byte) c;
            } else {
                out[n++] = (byte) '?';
            }
        }
        byte[] result = new byte[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }
}
