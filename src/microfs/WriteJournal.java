package microfs;

import java.io.IOException;

/**
 * Copia de respaldo del sector 0 (tabla de archivos) tomada antes de la primera modificación
 * de una sesión. Permite "Deshacer" y también actuar como red de seguridad si la escritura
 * final falla a medias: la tabla anterior queda en {@link #readBackup()}.
 */
public final class WriteJournal {

    private final byte[] backup = new byte[MicroFSManager.SECTOR_SIZE];
    private boolean captured;

    /**
     * Guarda la tabla actual del medio si aún no se hizo en esta sesión.
     * Funciona tanto con imágenes como con dispositivos físicos.
     */
    public void capture(String device, long baseOffset) throws IOException {
        if (captured) return;
        DeviceAccess.read(device, baseOffset, backup, MicroFSManager.SECTOR_SIZE);
        captured = true;
    }

    public boolean isCaptured() {
        return captured;
    }

    public byte[] readBackup() {
        return captured ? backup.clone() : null;
    }

    /** Restaura la tabla original en el medio. */
    public void rollback(String device, long baseOffset) throws IOException {
        if (!captured) return;
        DeviceAccess.write(device, baseOffset, backup, MicroFSManager.SECTOR_SIZE);
    }

    public void clear() {
        captured = false;
        java.util.Arrays.fill(backup, (byte) 0);
    }
}
