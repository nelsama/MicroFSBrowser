package microfs;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * MicroFS Browser - Navegador gráfico del sistema de archivos MicroFS.
 *
 * Trabaja con una imagen de tarjeta SD (.img / .bin) o, si el sistema lo permite, con un
 * dispositivo físico (Windows: \\.\PhysicalDriveN; Linux: /dev/sdX). El formato es el del
 * driver microfs-6502-cc65.
 *
 * Los cambios se aplican primero en memoria; el botón Guardar escribe la tabla y los datos
 * en el medio, lo que permite deshacer operaciones antes de confirmarlas.
 */
public class MicroFSBrowser extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String[] IMAGE_FILTER = {"img", "bin", "dsk", "raw"};
    private static final String BACKUP_PREF = "carpetaCopiasSeguridad";
    private static final String LAST_FOLDER_PREF = "ultimaCarpeta";

    private File backupChooser;
    private boolean backupsDisabled;
    private final SimpleDateFormat backupFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");

    // Componentes de conexión
    private JTextField deviceField;
    private JTextField baseOffsetField;
    private JComboBox<DiskItem> diskComboBox;
    private JButton detectButton;
    private JButton openImageButton;
    private JButton newImageButton;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton formatButton;
    private JButton saveButton;
    private JButton undoButton;

    // Datos
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JCheckBox hexViewCheck;
    private JTextArea contentArea;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JButton viewButton;
    private JButton editButton;
    private JButton deleteButton;
    private JButton importButton;
    private JButton exportButton;
    private JButton renameButton;

    private final MicroFSManager fs = new MicroFSManager();
    private final WriteJournal journal = new WriteJournal();
    private final List<String> rowNames = new ArrayList<>();
    private boolean dirty;
    private boolean selectingFromCode;
    /** Nombre del archivo cuya edición de texto está abierta (null si no hay). */
    private String editingName;

    private static final class DiskItem {
        final String label;
        final String path;
        final int score;

        DiskItem(String label, String path) {
            this(label, path, 0);
        }

        DiskItem(String label, String path, int score) {
            this.label = label;
            this.path = path;
            this.score = score;
        }

        @Override
        public String toString() { return label; }
    }

    public MicroFSBrowser() {
        super("MicroFS Browser v2.0");
        buildUi();
        bindEvents();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (confirmDiscard()) dispose();
            }
        });
        setSize(1080, 740);
        setMinimumSize(new Dimension(860, 580));
        setLocationRelativeTo(null);
        refreshTable();
    }

    private void initLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // L&F por defecto
        }
    }

    // ==================== Construcción de la UI ====================

    private void buildUi() {
        initLookAndFeel();
        setLayout(new BorderLayout(6, 6));
        setJMenuBar(createMenuBar());
        add(createConnectionPanel(), BorderLayout.NORTH);
        add(createMainSplit(), BorderLayout.CENTER);
        add(createLogPanel(), BorderLayout.SOUTH);
        ((JPanel) getContentPane()).setBorder(new EmptyBorder(8, 8, 8, 8));
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu menu = new JMenu("Imagen");

        JMenuItem backup = new JMenuItem("Copiar tarjeta a imagen…");
        backup.setToolTipText("Guarda una copia byte a byte del dispositivo o de un archivo .img");
        backup.addActionListener(e -> copyMediumToImage());

        JMenuItem restore = new JMenuItem("Grabar imagen en tarjeta…");
        restore.setToolTipText("Escribe una imagen .img completa en el dispositivo, como al grabar un pendrive");
        restore.addActionListener(e -> writeImageToMedium());

        JMenuItem verify = new JMenuItem("Verificar dispositivo con imagen…");
        verify.setToolTipText("Comprueba que el dispositivo coincide byte a byte con una imagen");
        verify.addActionListener(e -> verifyMediumAgainstImage());

        menu.add(backup);
        menu.add(restore);
        menu.addSeparator();
        menu.add(verify);
        bar.add(menu);
        return bar;
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new TitledBorder("1. Medio"));

        JPanel diskRow = new JPanel(new BorderLayout(5, 0));
        diskComboBox = new JComboBox<>();
        diskComboBox.setPreferredSize(new Dimension(560, 26));
        detectButton = new JButton("Detectar discos");
        diskRow.add(new JLabel("Discos SD/USB:"), BorderLayout.WEST);
        diskRow.add(diskComboBox, BorderLayout.CENTER);
        diskRow.add(detectButton, BorderLayout.EAST);

        JPanel pathRow = new JPanel(new BorderLayout(5, 0));
        deviceField = new JTextField();
        deviceField.setToolTipText("Imagen: C:\\imagenes\\sd.img    Windows: \\\\.\\PhysicalDrive3    Linux: /dev/sdb");
        pathRow.add(new JLabel("Imagen o dispositivo:"), BorderLayout.WEST);
        pathRow.add(deviceField, BorderLayout.CENTER);
        JPanel pathButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        openImageButton = new JButton("Abrir imagen…");
        newImageButton = new JButton("Crear imagen…");
        pathButtons.add(openImageButton);
        pathButtons.add(newImageButton);
        pathRow.add(pathButtons, BorderLayout.EAST);

        JPanel offsetRow = new JPanel(new BorderLayout(5, 0));
        baseOffsetField = new JTextField("0", 12);
        baseOffsetField.setHorizontalAlignment(JTextField.RIGHT);
        baseOffsetField.setToolTipText("Offset del sector 0 de MicroFS dentro de la imagen. " +
                "Si la imagen conserva la tabla de particiones, MicroFS suele empezar en 1048576.");
        JPanel offsetButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton detectOffset = new JButton("Detectar offset");
        offsetButtons.add(baseOffsetField);
        offsetButtons.add(detectOffset);
        offsetButtons.add(Box.createHorizontalStrut(12));
        connectButton = new JButton("Abrir MicroFS");
        disconnectButton = new JButton("Cerrar");
        formatButton = new JButton("Formatear");
        saveButton = new JButton("Guardar cambios");
        undoButton = new JButton("Deshacer");
        offsetButtons.add(connectButton);
        offsetButtons.add(disconnectButton);
        offsetButtons.add(formatButton);
        offsetButtons.add(saveButton);
        offsetButtons.add(undoButton);
        offsetRow.add(new JLabel("Sector 0 en offset:"), BorderLayout.WEST);
        offsetRow.add(offsetButtons, BorderLayout.CENTER);

        detectOffset.addActionListener(e -> detectBaseOffset());

        JPanel rows = new JPanel(new GridLayout(3, 1, 4, 4));
        rows.add(diskRow);
        rows.add(pathRow);
        rows.add(offsetRow);
        panel.add(rows, BorderLayout.CENTER);
        return panel;
    }

    private JSplitPane createMainSplit() {
        JPanel filesPanel = new JPanel(new BorderLayout(5, 5));
        filesPanel.setBorder(new TitledBorder("2. Archivos"));

        String[] columns = {"Nombre", "Tamaño", "Reservado", "Libre", "Sector"};
        tableModel = new DefaultTableModel(columns, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        fileTable = new JTable(tableModel);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.setAutoCreateRowSorter(true);
        int[] widths = {130, 80, 90, 70, 60};
        for (int i = 0; i < widths.length; i++) {
            fileTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        filesPanel.add(new JScrollPane(fileTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        viewButton = new JButton("Ver");
        editButton = new JButton("Editar texto");
        deleteButton = new JButton("Eliminar");
        renameButton = new JButton("Renombrar");
        renameButton.setToolTipText("Cambiar el nombre sin mover el archivo (F2)");
        importButton = new JButton("PC → SD");
        exportButton = new JButton("SD → PC");
        buttons.add(viewButton);
        buttons.add(editButton);
        buttons.add(renameButton);
        buttons.add(deleteButton);
        buttons.add(importButton);
        buttons.add(exportButton);
        filesPanel.add(buttons, BorderLayout.SOUTH);

        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(new TitledBorder("Contenido"));
        JPanel viewOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        hexViewCheck = new JCheckBox("Vista hexadecimal");
        viewOptions.add(hexViewCheck);
        contentPanel.add(viewOptions, BorderLayout.NORTH);

        contentArea = new JTextArea();
        contentArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(false);
        contentArea.setEditable(false);
        contentPanel.add(new JScrollPane(contentArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filesPanel, contentPanel);
        split.setDividerLocation(460);
        split.setResizeWeight(0.45);
        return split;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Log"));
        logArea = new JTextArea(5, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(new BevelBorder(BevelBorder.LOWERED));
        panel.add(logPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    // ==================== Eventos ====================

    private void bindEvents() {
        detectButton.addActionListener(e -> detectDisks());
        openImageButton.addActionListener(e -> chooseExistingImage());
        newImageButton.addActionListener(e -> createNewImage());
        connectButton.addActionListener(e -> openMedium());
        disconnectButton.addActionListener(e -> {
            if (!preparePendingEdits()) return;
            if (confirmDiscard()) {
                fs.close();
                dirty = false;
                journal.clear();
                contentArea.setText("");
                contentArea.setEditable(false);
                editButton.setText("Editar texto");
                refreshTable();
                log("Medio cerrado");
            }
        });
        formatButton.addActionListener(e -> formatMedium());
        saveButton.addActionListener(e -> saveChanges());
        undoButton.addActionListener(e -> undoChanges());
        viewButton.addActionListener(e -> viewSelected());
        editButton.addActionListener(e -> toggleEdit());
        deleteButton.addActionListener(e -> deleteSelected());
        importButton.addActionListener(e -> importFiles());
        exportButton.addActionListener(e -> exportSelected());
        renameButton.addActionListener(e -> renameSelected());
        hexViewCheck.addActionListener(e -> {
            if (fileTable.getSelectedRow() >= 0) viewSelected();
        });

        diskComboBox.addActionListener(e -> {
            if (selectingFromCode) return;
            Object item = diskComboBox.getSelectedItem();
            if (item instanceof DiskItem) {
                DiskItem d = (DiskItem) item;
                deviceField.setText(d.path);
                baseOffsetField.setText("0");
                updateButtonStates();
            }
        });

        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) viewSelected();
            }
        });
        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateButtonStates();
        });

        // F2 renombra el archivo seleccionado
        fileTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "microfs-rename");
        fileTable.getActionMap().put("microfs-rename", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                renameSelected();
            }
        });

        SwingUtilities.invokeLater(this::detectDisks);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateButtonStates() {
        boolean hasDevice = !deviceField.getText().trim().isEmpty();
        boolean opened = fs.isMounted() && fs.getDevice() != null;
        boolean hasRow = fileTable.getSelectedRow() >= 0;

        // El medio propuesto (imagen o disco) siempre se puede abrir o formatear.
        connectButton.setEnabled(hasDevice);
        disconnectButton.setEnabled(opened);
        formatButton.setEnabled(hasDevice);
        saveButton.setEnabled(dirty || contentArea.isEditable());
        undoButton.setEnabled(dirty && journal.isCaptured());
        viewButton.setEnabled(hasRow);
        editButton.setEnabled(hasRow);
        deleteButton.setEnabled(hasRow);
        renameButton.setEnabled(hasRow);
        importButton.setEnabled(true);
        exportButton.setEnabled(hasRow);
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (fs.getDevice() == null) {
            sb.append("Sin medio abierto");
        } else if (!fs.isMounted()) {
            sb.append("Medio cerrado");
        } else {
            sb.append(fs.getDevice());
            sb.append(" | Archivos: ").append(fs.getFileCount()).append('/').append(MicroFSManager.MAX_ENTRIES_IN_SECTOR);
            sb.append(" | Próximo sector libre: ").append(fs.getNextSector());
            long free = fs.freeSectors();
            sb.append(" | Espacio libre: ").append(free < 0 ? "desconocido"
                    : MicroFSManager.humanSize(free * MicroFSManager.SECTOR_SIZE));
            sb.append(" | Sector 0 en offset ").append(fs.getBaseOffset());
        }
        sb.append(dirty ? " | CAMBIOS SIN GUARDAR" : " | Sin cambios pendientes");
        statusLabel.setText(sb.toString());
        setTitle("MicroFS Browser v2.0" + (dirty ? " *" : ""));
    }

    // ==================== Detección ====================

    private void detectDisks() {
        selectingFromCode = true;
        diskComboBox.removeAllItems();
        diskComboBox.addItem(new DiskItem("Detectando…", ""));

        new Thread(() -> {
            List<DiskItem> items = new ArrayList<>();
            try {
                if (DeviceAccess.isWindows()) {
                    String script =
                            "$ErrorActionPreference='SilentlyContinue'; " +
                            "Get-Disk | ForEach-Object { " +
                            "  $disk = $_; $diskNumber = [int]$disk.Number; " +
                            "  $letters = @(); " +
                            "  foreach ($part in (Get-Partition -DiskNumber $diskNumber)) { " +
                            "    if ($part.DriveLetter) { $letters += ($part.DriveLetter + ':') } } " +
                            "  $sizeGb = [string][math]::Round([double]$disk.Size / 1GB, 2); " +
                            "  $bus = [string]$disk.BusType; " +
                            "  $lettersText = [string]($letters -join ','); " +
                            "  $line = @([string]$diskNumber, [string]$disk.FriendlyName, $bus, $sizeGb, $lettersText); " +
                            "  Write-Output ($line -join '|'); " +
                            "}";
                    String out = DeviceAccess.Powershell.run(script);
                    log("Discos informados por Windows:\n" + out.trim());
                    for (String line : out.split("\\R")) {
                        String t = line.trim();
                        if (t.isEmpty() || !t.contains("|")) continue;
                        String[] p = t.split("\\|", -1);
                        if (p.length < 4) continue;

                        String number = p[0].trim();
                        String model = p.length > 1 ? p[1].trim() : "";
                        String bus = p.length > 2 ? p[2].trim() : "";
                        String size = p.length > 3 ? p[3].trim() : "?";
                        String letters = p.length > 4 ? p[4].trim() : "";
                        if (number.isEmpty()) continue;

                        boolean removable = bus.equalsIgnoreCase("SD") || bus.equalsIgnoreCase("USB")
                                || bus.equalsIgnoreCase("MMC") || bus.equalsIgnoreCase("1394")
                                || model.toUpperCase().contains("CARD")
                                || model.toUpperCase().contains("STORAGE")
                                || model.toUpperCase().contains("SD/MMC");
                        // Prioridad: SD/MMC > almacenamiento USB con datos > resto.
                        double sizeGb = 0;
                        try {
                            sizeGb = Double.parseDouble(size);
                        } catch (NumberFormatException ignored) {
                            // tamaño no numérico
                        }
                        int score = 0;
                        if (bus.equalsIgnoreCase("SD") || bus.equalsIgnoreCase("MMC")) score = 100;
                        else if (removable && sizeGb > 0) score = 50;

                        String mount = letters.isEmpty()
                                ? "sin letra de unidad"
                                : "unidades " + letters + " (quitarlas para escribir)";

                        String label = "Disco " + number + " — " + model + " [" + bus + ", " + size + " GB, "
                                + mount + "]"
                                + (removable ? "" : "  (no parece SD/USB)");
                        items.add(new DiskItem(label, "\\\\.\\PhysicalDrive" + number, score));
                    }
                } else {
                    File devDir = new File("/dev");
                    File[] devs = devDir.listFiles((dir, n) -> n.matches("(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+)$"));
                    if (devs != null) {
                        java.util.Arrays.sort(devs);
                        for (File dev : devs) {
                            items.add(new DiskItem(dev.getAbsolutePath(), dev.getAbsolutePath()));
                        }
                    }
                }
            } catch (Exception ex) {
                log("No se pudieron detectar los discos: " + ex.getMessage());
            }

            final List<DiskItem> found = items;
            SwingUtilities.invokeLater(() -> {
                selectingFromCode = true;
                diskComboBox.removeAllItems();
                if (found.isEmpty()) {
                    diskComboBox.addItem(new DiskItem("No se detectaron discos; usa una imagen .img", ""));
                } else {
                    for (DiskItem item : found) diskComboBox.addItem(item);
                    // Elegir el medio con más posibilidades de ser la tarjeta:
                    // 1) SD/MMC, 2) USB con datos, 3) cualquier otro.
                    int best = -1;
                    int bestScore = -1;
                    for (int i = 0; i < found.size(); i++) {
                        int score = found.get(i).score;
                        if (score > bestScore) {
                            bestScore = score;
                            best = i;
                        }
                    }
                    if (best >= 0 && bestScore > 0) {
                        diskComboBox.setSelectedIndex(best);
                        DiskItem chosen = found.get(best);
                        deviceField.setText(chosen.path);
                        baseOffsetField.setText("0");
                    } else {
                        diskComboBox.setSelectedIndex(-1);
                        deviceField.setText("");
                    }
                }
                selectingFromCode = false;
                updateButtonStates();
            });
        }, "microfs-detect-disks").start();
    }

    // ==================== Abrir medio ====================

    private File chooseImageFile(boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Imágenes de tarjeta SD (*.img, *.bin, *.dsk, *.raw)", IMAGE_FILTER));
        if (save) {
            int r = chooser.showSaveDialog(this);
            if (r != JFileChooser.APPROVE_OPTION) return null;
            rememberFolder(chooser);
            return ensureExtension(chooser.getSelectedFile());
        }
        int r = chooser.showOpenDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) return null;
        rememberFolder(chooser);
        return chooser.getSelectedFile();
    }

    /** Abre un selector recordando la última carpeta usada. */
    private JFileChooser newFileChooser() {
        JFileChooser chooser = new JFileChooser();
        String last = Preferences.userNodeForPackage(MicroFSBrowser.class).get(LAST_FOLDER_PREF, "");
        if (!last.isEmpty()) {
            File folder = new File(last);
            if (folder.isDirectory()) {
                chooser.setCurrentDirectory(folder);
            }
        }
        return chooser;
    }

    /** Guarda la carpeta del selector para la próxima vez. */
    private void rememberFolder(JFileChooser chooser) {
        File folder = chooser.getCurrentDirectory();
        if (chooser.getSelectedFile() != null && chooser.getSelectedFile().getParentFile() != null) {
            File parent = chooser.getSelectedFile().getParentFile();
            if (parent.isDirectory()) folder = parent;
        }
        if (folder != null) {
            Preferences.userNodeForPackage(MicroFSBrowser.class).put(LAST_FOLDER_PREF, folder.getAbsolutePath());
        }
    }

    private File ensureExtension(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : IMAGE_FILTER) {
            if (name.endsWith("." + ext)) return file;
        }
        return new File(file.getAbsolutePath() + ".img");
    }

    private void chooseExistingImage() {
        if (!preparePendingEdits()) return;
        File file = chooseImageFile(false);
        if (file == null) return;
        deviceField.setText(file.getAbsolutePath());
        openMedium();
    }

    private void createNewImage() {
        if (!preparePendingEdits()) return;
        File file = chooseImageFile(true);
        if (file == null) return;

        Integer mb = askSizeMb();
        if (mb == null) return;
        long requested = (long) mb * 1024 * 1024;
        long size = DeviceAccess.limitForMicroFS(requested);
        if (size < requested) {
            JOptionPane.showMessageDialog(this,
                    "MicroFS direcciona como máximo " + MicroFSManager.MAX_MEDIA_BYTES / (1024 * 1024) +
                    " MB de datos (sectores en 16 bits).\nSe creará una imagen de " +
                    MicroFSManager.MAX_MEDIA_BYTES / (1024 * 1024) + " MB.",
                    "Límite de MicroFS", JOptionPane.INFORMATION_MESSAGE);
        }
        try {
            DeviceAccess.createImage(file.getAbsolutePath(), size);
            MicroFSManager fresh = new MicroFSManager();
            fresh.setTarget(file.getAbsolutePath(), 0);
            fresh.flush();
            log("Imagen creada: " + file.getAbsolutePath() + " (" + MicroFSManager.humanSize(size) + ")");
            deviceField.setText(file.getAbsolutePath());
            baseOffsetField.setText("0");
            openMedium();
        } catch (IOException ex) {
            showError("No se pudo crear la imagen", ex, file.getAbsolutePath());
        }
    }

    private Integer askSizeMb() {
        String[] options = {"1 MB", "4 MB", "16 MB", "32 MB", "Otra…"};
        int[] sizes = {1, 4, 16, 32};
        Object choice = JOptionPane.showInputDialog(this,
                "Tamaño de la imagen (1 MB es suficiente para el máximo de MicroFS):",
                "Crear imagen", JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == null) return null;
        String value = choice.toString();
        for (int i = 0; i < sizes.length; i++) {
            if (options[i].equals(value)) return sizes[i];
        }
        String custom = JOptionPane.showInputDialog(this, "Tamaño en MB:", "8");
        if (custom == null) return null;
        try {
            return Math.max(1, Integer.parseInt(custom.trim()));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Tamaño inválido.", "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private long parseBaseOffset() {
        String t = baseOffsetField.getText() == null ? "" : baseOffsetField.getText().trim();
        if (t.isEmpty()) return 0;
        try {
            long v = t.toLowerCase().startsWith("0x") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
            return v >= 0 && v % MicroFSManager.SECTOR_SIZE == 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void openMedium() {
        if (!preparePendingEdits()) return;
        String device = deviceField.getText().trim();
        if (device.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Indica una imagen o un dispositivo.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long offset = parseBaseOffset();
        if (offset < 0) {
            JOptionPane.showMessageDialog(this,
                    "El offset debe ser 0 o un múltiplo de 512 bytes.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (dirty && !confirmDiscard()) return;

        try {
            String resolved = DevicePaths.resolve(device, null);
            fs.mount(resolved, offset);
            dirty = false;
            journal.clear();
            contentArea.setText("");
            log("MicroFS abierto: " + resolved + " (sector 0 en offset " + offset + ", " +
                    fs.getFileCount() + " archivo(s))");
            if (!resolved.equals(device)) {
                log("Nota: la ruta " + device + " no admite acceso directo; se usó " + resolved +
                        " (misma tarjeta). Para escribir conviene quitar la letra de unidad.");
            }
            refreshTable();
        } catch (IOException ex) {
            fs.close();
            showError("No se pudo abrir MicroFS", ex, device);
            refreshTable();
        }
    }

    private void detectBaseOffset() {
        if (!preparePendingEdits()) return;
        String device = deviceField.getText().trim();
        if (device.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Indica primero la imagen o el dispositivo.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Long found = findMicroFSBase(device);
            if (found == null) {
                JOptionPane.showMessageDialog(this,
                        "No se encontró la firma \"FM\" de MicroFS en el medio.\n" +
                        "Puedes formatearlo desde este programa.", "Sin resultados", JOptionPane.WARNING_MESSAGE);
                return;
            }
            baseOffsetField.setText(String.valueOf(found));
            log("MicroFS detectado en el offset " + found + " (" + MicroFSManager.humanSize(found) + ")");
        } catch (IOException ex) {
            showError("No se pudo analizar el medio", ex, device);
        }
    }

    /** Busca la firma "FM" validando la coherencia de la tabla de archivos. */
    private Long findMicroFSBase(String device) throws IOException {
        List<Long> candidates = new ArrayList<>();
        candidates.add(0L);

        long total = DeviceAccess.size(device);
        if (total > 0) {
            byte[] mbr = new byte[MicroFSManager.SECTOR_SIZE];
            try {
                DeviceAccess.read(device, 0, mbr, MicroFSManager.SECTOR_SIZE);
                for (int i = 0; i < 4; i++) {
                    int base = 446 + i * 16;
                    long lba = (mbr[base + 8] & 0xFFL) | ((mbr[base + 9] & 0xFFL) << 8)
                            | ((mbr[base + 10] & 0xFFL) << 16) | ((mbr[base + 11] & 0xFFL) << 24);
                    if (lba > 0 && lba * MicroFSManager.SECTOR_SIZE < total) {
                        candidates.add(lba * MicroFSManager.SECTOR_SIZE);
                    }
                }
            } catch (IOException ignored) {
                // medio sin MBR legible
            }
            for (long mb : new long[]{1, 2, 4, 8}) {
                candidates.add(mb * 1024 * 1024);
            }
        }

        for (long off : candidates) {
            if (isMicroFSAt(device, off)) return off;
        }
        return null;
    }

    private boolean isMicroFSAt(String device, long offset) {
        try {
            byte[] s = new byte[MicroFSManager.SECTOR_SIZE];
            DeviceAccess.read(device, offset, s, MicroFSManager.SECTOR_SIZE);
            if (!(s[0] == 0x46 && s[1] == 0x4D)) return false;
            int next = (s[6] & 0xFF) | ((s[7] & 0xFF) << 8);
            if (next < 1) return false;
            for (int i = 0; i < MicroFSManager.MAX_ENTRIES_IN_SECTOR; i++) {
                int off = 16 + i * 32;
                if (s[off] == 0) continue;
                int start = (s[off + 12] & 0xFF) | ((s[off + 13] & 0xFF) << 8);
                int sectors = (s[off + 16] & 0xFF) | ((s[off + 17] & 0xFF) << 8);
                if (start < 1 || start + sectors > next) return false;
                for (int k = 0; k < MicroFSManager.MAX_NAME_LENGTH && s[off + k] != 0; k++) {
                    int c = s[off + k] & 0xFF;
                    if (c < 0x20 || c > 0x7E) return false;
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    // ==================== Tabla y contenido ====================

    private void refreshTable() {
        String keep = selectedName();
        rowNames.clear();
        List<MicroFSManager.Entry> list = fs.isMounted() ? fs.list() : new ArrayList<>();
        tableModel.setRowCount(0);
        for (MicroFSManager.Entry e : list) {
            rowNames.add(e.getName());
            tableModel.addRow(new Object[]{
                    e.getName(),
                    e.getSize(),
                    e.getReservedBytes(),
                    Math.max(0, e.getReservedBytes() - e.getSize()),
                    e.getStartSector()
            });
        }
        if (keep != null) {
            for (int row = 0; row < fileTable.getRowCount(); row++) {
                int model = fileTable.convertRowIndexToModel(row);
                if (model < rowNames.size() && rowNames.get(model).equals(keep)) {
                    fileTable.setRowSelectionInterval(row, row);
                    break;
                }
            }
        }
        updateButtonStates();
    }

    private String selectedName() {
        int row = fileTable.getSelectedRow();
        if (row < 0) return null;
        int model = fileTable.convertRowIndexToModel(row);
        return model >= 0 && model < rowNames.size() ? rowNames.get(model) : null;
    }

    private void viewSelected() {
        String name = selectedName();
        if (name == null) return;
        try {
            if (name.equals(editingName)) cerrarEdicion();
            byte[] data = fs.readFile(name);
            ContentView.Kind kind = ContentView.kindFor(name, data);
            String text;
            if (hexViewCheck.isSelected() || kind == ContentView.Kind.HEX) {
                text = ContentView.hexDump(data);
            } else if (kind == ContentView.Kind.PETSCII) {
                text = ContentView.petsciiToAscii(data);
            } else {
                text = ContentView.asUtf8(data);
            }
            contentArea.setText(text);
            contentArea.setCaretPosition(0);
            log("Ver " + name + ": " + data.length + " bytes (" +
                    MicroFSManager.humanSize(data.length) + ", vista " + kind + ")");
        } catch (IOException ex) {
            showError("No se pudo leer el archivo", ex, fs.getDevice());
        }
    }

    /**
     * Alterna la edición del archivo seleccionado.
     *
     * Al entrar en edición se siembra el área con el contenido real del archivo ("Ver"
     * muestra un volcado hexadecimal para binarios, que no debe guardarse como texto).
     * Al salir, el texto se aplica al modelo en memoria; "Guardar cambios" lo escribe en
     * el medio.
     */
    private void toggleEdit() {
        if (contentArea.isEditable()) {
            if (aplicarEdicion()) {
                JOptionPane.showMessageDialog(this,
                        "Cambios aplicados en memoria.\n" +
                        "Pulsa 'Guardar cambios' para escribirlos en el medio.",
                        "Edición", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }

        String name = selectedName();
        if (name == null) return;
        if (name.toUpperCase().endsWith(".PRG") || name.toUpperCase().endsWith(".BIN")) {
            int r = JOptionPane.showConfirmDialog(this,
                    "Este archivo parece binario. Si lo guardas como texto se perderá su contenido.\n" +
                    "¿Editar igualmente?", "Editar binario", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try {
            byte[] data = fs.readFile(name);
            boolean petscii = name.toUpperCase().endsWith(".PET");
            boolean binario = ContentView.kindFor(name, data) == ContentView.Kind.HEX;
            String text = petscii ? ContentView.petsciiToAscii(data) : ContentView.asUtf8(data);
            contentArea.setText(text);
            contentArea.setEditable(true);
            contentArea.setCaretPosition(0);
            contentArea.requestFocus();
            editingName = name;
            editButton.setText("Aplicar texto");
            log("Editando " + name + " (" + data.length + " bytes" +
                    (binario ? ", contenido binario" : "") +
                    "): al aplicar se convertirá el texto a bytes");
        } catch (IOException ex) {
            showError("No se pudo abrir el archivo para editar", ex, fs.getDevice());
        }
    }

    /** Escribe en el modelo el texto editado. Devuelve true si se aplicó. */
    private boolean aplicarEdicion() {
        String name = editingName != null ? editingName : selectedName();
        if (name == null) {
            cerrarEdicion();
            return false;
        }
        try {
            byte[] data = textToBytes(name, contentArea.getText());
            fs.writeFile(name, data);
            dirty = true;
            cerrarEdicion();
            refreshTable();
            log("Edición aplicada en memoria: " + name + " (" + data.length + " bytes)");
            return true;
        } catch (IOException ex) {
            showError("No se pudo aplicar la edición", ex, fs.getDevice());
            return false;
        }
    }

    private void cerrarEdicion() {
        editingName = null;
        contentArea.setEditable(false);
        editButton.setText("Editar texto");
        updateButtonStates();
    }

    private static byte[] textToBytes(String name, String text) {
        if (name.toUpperCase().endsWith(".PET")) {
            return ContentView.asciiToPetscii(text);
        }
        return text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void deleteSelected() {
        String name = selectedName();
        if (name == null) return;
        int r = JOptionPane.showConfirmDialog(this,
                "¿Eliminar '" + name + "'?\n\n" +
                "La entrada se borra de la tabla; sus sectores no se reutilizan (igual que mfs_delete en el 6502).",
                "Confirmar eliminación", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        try {
            journal.capture(fs.getDevice(), fs.getBaseOffset());
            fs.delete(name);
            dirty = true;
            contentArea.setText("");
            contentArea.setEditable(false);
            editButton.setText("Editar texto");
            refreshTable();
            log("Eliminado " + name + " (pendiente de guardar)");
        } catch (IOException ex) {
            showError("No se pudo eliminar", ex, fs.getDevice());
        }
    }

    /**
     * Cambia el nombre de un archivo conservando su contenido y sus sectores reservados.
     * En el formato MicroFS sólo se reescribe el nombre en la entrada de la tabla.
     */
    private void renameSelected() {
        String name = selectedName();
        if (name == null) return;

        if (contentArea.isEditable()) {
            if (!preparePendingEdits()) return;
        }

        String input = (String) JOptionPane.showInputDialog(this,
                "Nuevo nombre (máximo " + MicroFSManager.MAX_NAME_LENGTH +
                        " caracteres, se convertirá a mayúsculas):",
                "Renombrar " + name,
                JOptionPane.QUESTION_MESSAGE, null, null, name);
        if (input == null) return;

        String newName;
        try {
            newName = fs.sanitizeName(input);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Nombre inválido", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (newName.equals(name)) return;

        try {
            if (fs.getDevice() != null) {
                journal.capture(fs.getDevice(), fs.getBaseOffset());
            }
            fs.rename(name, newName);
            dirty = true;
            refreshTable();
            log("Renombrado " + name + " → " + newName + " (pendiente de guardar)");
        } catch (IOException ex) {
            showError("No se pudo renombrar", ex, fs.getDevice());
        }
    }

    // ==================== Importar / exportar ====================

    private void importFiles() {
        JFileChooser chooser = newFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Selecciona los archivos a importar");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberFolder(chooser);
        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;

        List<File> valid = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (File f : files) {
            if (f.length() > MicroFSManager.MAX_FILE_SIZE) {
                rejected.add(f.getName() + " (" + MicroFSManager.humanSize(f.length()) + ")");
            } else {
                valid.add(f);
            }
        }
        if (!rejected.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Estos archivos superan el máximo de " + MicroFSManager.MAX_FILE_SIZE + " bytes y no se importarán:\n - " +
                    String.join("\n - ", rejected), "Aviso", JOptionPane.WARNING_MESSAGE);
        }
        if (valid.isEmpty()) return;

        int free = MicroFSManager.MAX_ENTRIES_IN_SECTOR - fs.getFileCount();
        long sectors = 0;
        for (File f : valid) {
            sectors += Math.max(1, (f.length() + 511) / 512);
        }
        long available = fs.freeSectors();
        StringBuilder msg = new StringBuilder();
        msg.append("Archivos: ").append(valid.size()).append('\n');
        msg.append("Sectores necesarios: ").append(sectors);
        if (available >= 0) msg.append(" | libres: ").append(available);
        if (valid.size() > free) {
            msg.append("\n\nAviso: sólo quedan ").append(free).append(" entradas libres de ")
               .append(MicroFSManager.MAX_ENTRIES_IN_SECTOR).append('.');
        }
        if (available >= 0 && sectors > available) {
            msg.append("\n\nAviso: no cabe todo el espacio necesario en el medio.");
        }
        int r = JOptionPane.showConfirmDialog(this, msg.toString(), "Importar a MicroFS",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        try {
            journal.capture(fs.getDevice(), fs.getBaseOffset());
        } catch (IOException ex) {
            log("No se pudo respaldar la tabla actual: " + ex.getMessage());
        }

        int ok = 0;
        for (File f : valid) {
            try {
                String name = fs.sanitizeName(shortName(f.getName()));
                byte[] data = Files.readAllBytes(f.toPath());
                fs.writeFile(name, data);
                log("Importado " + f.getName() + " → " + name + " (" + MicroFSManager.humanSize(data.length) + ")");
                ok++;
            } catch (Exception ex) {
                log("No se pudo importar " + f.getName() + ": " + ex.getMessage());
            }
        }
        dirty = ok > 0;
        refreshTable();
        log(ok + " archivo(s) importado(s). Usa 'Guardar cambios' para escribir en el medio.");
    }

    /** Convierte un nombre de PC a 11 caracteres conservando la extensión. */
    static String shortName(String fileName) {
        String name = fileName.toUpperCase();
        if (name.length() <= MicroFSManager.MAX_NAME_LENGTH) return name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 4) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
        }
        int keep = Math.max(1, MicroFSManager.MAX_NAME_LENGTH - ext.length());
        return name.substring(0, Math.min(name.length(), keep)) + ext;
    }

    private void exportSelected() {
        String name = selectedName();
        if (name == null) return;
        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle("Exportar archivo");
        chooser.setSelectedFile(new File(name));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberFolder(chooser);
        try {
            byte[] data = fs.readFile(name);
            Files.write(chooser.getSelectedFile().toPath(), data);
            log("Exportado " + name + " → " + chooser.getSelectedFile().getAbsolutePath());
        } catch (IOException ex) {
            showError("No se pudo exportar", ex, fs.getDevice());
        }
    }

    // ==================== Copias completas del medio ====================

    /** Dispositivo seleccionado en el campo de medio, o null si es una imagen. */
    private String selectedDevice() {
        String text = deviceField.getText().trim();
        if (text.isEmpty()) return null;
        String resolved = DevicePaths.resolve(text, null);
        if (DeviceAccess.isWindowsDevice(resolved) || resolved.startsWith(DeviceAccess.isWindows() ? "\\\\.\\" : "/dev/")) {
            return resolved;
        }
        return null;
    }

    private void showImageMenuHint() {
        JOptionPane.showMessageDialog(this,
                "Estas operaciones trabajan con dispositivos físicos (tarjeta SD).\n" +
                "Elige la tarjeta en 'Discos detectados' o escribe \\\\.\\PhysicalDriveN / /dev/sdX.",
                "Imagen", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Tarjeta (o imagen abierta) -> archivo .img. */
    private void copyMediumToImage() {
        String source = deviceField.getText().trim();
        if (source.isEmpty()) {
            showImageMenuHint();
            return;
        }
        String resolved = DevicePaths.resolve(source, null);

        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle("Guardar copia del medio como imagen");
        chooser.setSelectedFile(new File("sd_backup.img"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberFolder(chooser);
        File target = ensureExtension(chooser.getSelectedFile());

        try {
            MediumImager.Plan plan = MediumImager.plan(resolved, target.getAbsolutePath());
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Se copiará el medio completo:\n\n" +
                    "Origen:  " + resolved + "\n" +
                    "Destino: " + target.getAbsolutePath() + "\n" +
                    "Tamaño:  " + MicroFSManager.humanSize(plan.sourceSize) +
                    (plan.notes.isEmpty() ? "" : "\n\n" + plan.notes),
                    "Copiar a imagen", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm != JOptionPane.OK_OPTION) return;

            runCopy(resolved, target.getAbsolutePath(), true,
                    "Copiando " + resolved + " a imagen");
            log("Imagen creada: " + target.getAbsolutePath());
        } catch (IOException ex) {
            showError("No se pudo copiar el medio", ex, resolved);
        }
    }

    /** Archivo .img -> tarjeta (como grabar un pendrive). */
    private void writeImageToMedium() {
        String device = selectedDevice();
        if (device == null) {
            showImageMenuHint();
            return;
        }

        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle("Elegir imagen para grabar");
        chooser.setFileFilter(new FileNameExtensionFilter("Imágenes de tarjeta SD (*.img, *.bin, *.dsk, *.raw)", IMAGE_FILTER));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberFolder(chooser);
        File image = chooser.getSelectedFile();

        try {
            MediumImager.Plan plan = MediumImager.plan(image.getAbsolutePath(), device);
            String texto = "Se SOBRESCRIBIRÁ el contenido del dispositivo:\n\n" +
                    "Destino: " + device +
                    (plan.targetSize > 0 ? " (" + MicroFSManager.humanSize(plan.targetSize) + ")" : "") + "\n" +
                    "Imagen:  " + image.getAbsolutePath() + "\n" +
                    "Tamaño:  " + MicroFSManager.humanSize(plan.sourceSize) +
                    (plan.notes.isEmpty() ? "" : "\n\n" + plan.notes) +
                    "\n\nTodos los datos actuales del dispositivo se perderán.";
            int confirm = JOptionPane.showConfirmDialog(this, texto,
                    "Grabar imagen en el dispositivo", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.OK_OPTION) return;

            String typed = JOptionPane.showInputDialog(this,
                    "Escribe GRABAR para confirmar la escritura en " + device + ":", "Confirmar");
            if (typed == null || !typed.trim().equalsIgnoreCase("GRABAR")) {
                log("Grabación cancelada");
                return;
            }

            // El modelo en memoria dejaría de coincidir con el medio
            if (fs.isMounted()) {
                fs.close();
                dirty = false;
                journal.clear();
            }

            runCopy(image.getAbsolutePath(), device, true,
                    "Grabando imagen en " + device);
            log("Imagen grabada en " + device);

            int abrir = JOptionPane.showConfirmDialog(this,
                    "Imagen grabada correctamente.\n¿Abrir MicroFS del dispositivo ahora?",
                    "Grabar imagen", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (abrir == JOptionPane.YES_OPTION) openMedium();
        } catch (IOException ex) {
            showError("No se pudo grabar la imagen", ex, device);
        }
    }

    /** Compara el dispositivo con una imagen, byte a byte. */
    private void verifyMediumAgainstImage() {
        String device = selectedDevice();
        if (device == null) {
            showImageMenuHint();
            return;
        }
        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle("Imagen con la que comparar");
        chooser.setFileFilter(new FileNameExtensionFilter("Imágenes de tarjeta SD (*.img, *.bin, *.dsk, *.raw)", IMAGE_FILTER));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberFolder(chooser);
        File image = chooser.getSelectedFile();

        try {
            long size = Files.size(image.toPath());
            boolean resultado = runWithProgress("Verificando " + device, progress -> {
                MediumImager.verifyCopy(image.getAbsolutePath(), device, size);
                return true;
            });
            if (resultado) {
                log("Verificación correcta: " + device + " coincide con " + image.getName());
                JOptionPane.showMessageDialog(this, "El dispositivo coincide con la imagen.",
                        "Verificación", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException ex) {
            showError("La verificación falló", ex, device);
        }
    }

    private interface CopyTask {
        boolean run(java.util.function.DoubleConsumer progress) throws IOException;
    }

    /** Ejecuta una tarea con barra de progreso modal y devuelve true si terminó bien. */
    private boolean runWithProgress(String title, CopyTask task) throws IOException {
        JDialog dialog = new JDialog(this, title, true);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        bar.setString("0 %");
        dialog.add(BorderLayout.CENTER, bar);
        dialog.add(BorderLayout.SOUTH, new JLabel(" No cierres esta ventana ni retires la tarjeta",
                SwingConstants.CENTER));
        dialog.setSize(420, 110);
        dialog.setLocationRelativeTo(this);

        final boolean[] ok = new boolean[1];
        final IOException[] error = new IOException[1];
        Thread worker = new Thread(() -> {
            try {
                ok[0] = task.run(progress -> {
                    int value = (int) Math.round(progress * 1000);
                    SwingUtilities.invokeLater(() -> {
                        bar.setValue(value);
                        bar.setString((value / 10) + " %");
                    });
                });
            } catch (IOException ex) {
                error[0] = ex;
            }
        }, "microfs-copy");
        worker.start();

        Thread watcher = new Thread(() -> {
            try {
                worker.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            SwingUtilities.invokeLater(dialog::dispose);
        }, "microfs-copy-watch");
        watcher.start();

        dialog.setVisible(true); // modal hasta que el watcher la cierre
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (error[0] != null) throw error[0];
        return ok[0];
    }

    private void runCopy(String source, String target, boolean verify, String title) throws IOException {
        long t0 = System.currentTimeMillis();
        runWithProgress(title, progress -> {
            MediumImager.copy(source, target, verify, progress);
            return true;
        });
        long ms = System.currentTimeMillis() - t0;
        log(String.format("%s -> %s completado en %.1f s%s", source, target, ms / 1000.0,
                verify ? " (verificado)" : ""));
    }

    // ==================== Guardar / formatear / deshacer ====================

    private void saveChanges() {
        String device = deviceField.getText().trim();
        if (device.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Indica la imagen o el dispositivo de destino.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long offset = parseBaseOffset();
        if (offset < 0) {
            JOptionPane.showMessageDialog(this, "El offset debe ser 0 o múltiplo de 512.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            if (contentArea.isEditable() && !aplicarEdicion()) return;
            // En Windows puede ser necesario escribir por la unidad montada (\\.\F:)
            // cuando \\.\PhysicalDriveN no admite acceso directo.
            String target = DevicePaths.resolve(device, null);
            if (!target.equals(fs.getDevice()) || fs.getBaseOffset() != offset) {
                fs.setTarget(target, offset);
            }
            File backup = backupBeforeSave(target);
            boolean rebuilt = fs.flush();
            dirty = false;
            journal.clear();
            fs.mount(target, offset);
            refreshTable();   // también actualiza la barra de estado
            log("Cambios guardados en " + target + " (" + fs.getFileCount() + " archivo(s))");
            if (rebuilt) {
                log("Aviso: fue necesario reasignar sectores (recompactar) para que cupiera algún archivo.");
            }
            String backupText = backup == null ? "" : "\n\nCopia de seguridad de los datos anteriores:\n"
                    + backup.getAbsolutePath();
            JOptionPane.showMessageDialog(this,
                    "Cambios guardados en el medio.\nSe recomienda expulsar la tarjeta antes de retirarla."
                            + backupText,
                    "Guardar", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            showError("No se pudieron guardar los cambios", ex, device);
        }
    }

    /**
     * Guarda una copia del sector 0 y del área de datos usada antes de escribir en el medio.
     * Las copias van a una carpeta del PC, nunca a la tarjeta. Si el usuario decide no
     * hacer copias, se guarda sin ellas.
     */
    private File backupBeforeSave(String device) {
        if (backupsDisabled) return null;
        if (!showBackups()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "Antes de escribir en la tarjeta se guarda una copia de seguridad\n" +
                    "del área usada por MicroFS, por si hay que recuperar un guardado erróneo.\n\n" +
                    "¿Deseas crear copias de seguridad antes de cada guardado?",
                    "Copias de seguridad", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                backupsDisabled = true;
                log("Copias de seguridad desactivadas para esta sesión");
                return null;
            }
            backupChooser = chooseBackupFolder();
            if (backupChooser == null) {
                backupChooser = defaultBackupFolder();
            }
        }
        if (backupChooser == null) return null;
        try {
            byte[] table = new byte[MicroFSManager.SECTOR_SIZE];
            DeviceAccess.read(device, fs.getBaseOffset(), table, MicroFSManager.SECTOR_SIZE);
            byte[] data = fs.snapshotUsedArea();
            byte[] all = new byte[table.length + data.length];
            System.arraycopy(table, 0, all, 0, table.length);
            System.arraycopy(data, 0, all, table.length, data.length);

            String stamp = backupFormat.format(new java.util.Date());
            File file = new File(backupChooser, "microfs_" + stamp + ".bak");
            Files.write(file.toPath(), all);
            log("Copia de seguridad: " + file.getAbsolutePath() + " (" + MicroFSManager.humanSize(all.length) + ")");
            return file;
        } catch (IOException ex) {
            log("No se pudo crear la copia de seguridad: " + ex.getMessage());
            return null;
        }
    }

    /** Carpeta usada por defecto: <usuario>\\MicroFS Backups, creada si no existe. */
    private File defaultBackupFolder() {
        File folder = new File(System.getProperty("user.home", "."), "MicroFS Backups");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            log("No se pudo crear " + folder.getAbsolutePath() + "; no se harán copias.");
            return null;
        }
        saveBackupFolder(folder);
        return folder;
    }

    private void saveBackupFolder(File folder) {
        Preferences prefs = Preferences.userNodeForPackage(MicroFSBrowser.class);
        prefs.put(BACKUP_PREF, folder.getAbsolutePath());
    }

    private File chooseBackupFolder() {
        JFileChooser chooser = newFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Carpeta para las copias de seguridad de la tarjeta");
        if (chooser.showDialog(this, "Usar esta carpeta") != JFileChooser.APPROVE_OPTION) return null;
        File folder = chooser.getSelectedFile();
        saveBackupFolder(folder);
        return folder;
    }

    private boolean showBackups() {
        if (backupChooser != null) return true;
        String saved = Preferences.userNodeForPackage(MicroFSBrowser.class).get(BACKUP_PREF, "");
        if (saved.isEmpty()) return false;
        File folder = new File(saved);
        if (!folder.isDirectory()) return false;
        backupChooser = folder;
        return true;
    }

    private void formatMedium() {
        String device = deviceField.getText().trim();
        if (device.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Indica la imagen o el dispositivo a formatear.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        long offset = Math.max(0, parseBaseOffset());
        int r = JOptionPane.showConfirmDialog(this,
                "¿Formatear este medio con MicroFS?\n\n" + device + "\n\n" +
                "Se escribirá una tabla de archivos vacía. Los datos anteriores dejan de ser accesibles.",
                "Confirmar formateo", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        try {
            if (fs.getDevice() != null) {
                journal.capture(fs.getDevice(), fs.getBaseOffset());
            }
            fs.setTarget(device, offset);
            fs.format();
            dirty = true;
            contentArea.setText("");
            contentArea.setEditable(false);
            editButton.setText("Editar texto");
            refreshTable();
            log("Medio formateado en memoria. Pulsa 'Guardar cambios' para aplicarlo.");
            JOptionPane.showMessageDialog(this,
                    "Formateo preparado.\nPulsa 'Guardar cambios' para escribir la tabla vacía en el medio.",
                    "Formatear", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            showError("No se pudo formatear", ex, device);
        }
    }

    private void undoChanges() {
        if (!journal.isCaptured()) {
            JOptionPane.showMessageDialog(this, "No hay operaciones para deshacer.",
                    "Deshacer", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "¿Descartar los cambios no guardados y volver al estado anterior?",
                "Deshacer", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        try {
            String device = fs.getDevice();
            if (device != null) {
                journal.rollback(device, fs.getBaseOffset());
                fs.mount(device, fs.getBaseOffset());
            } else {
                fs.newMedium();
            }
            dirty = false;
            journal.clear();
            contentArea.setText("");
            contentArea.setEditable(false);
            editButton.setText("Editar texto");
            refreshTable();
            log("Cambios descartados");
        } catch (IOException ex) {
            showError("No se pudo deshacer", ex, fs.getDevice());
        }
    }

    /** Aplica al modelo la edición de texto pendiente para no perderla al cambiar de contexto. */
    private boolean preparePendingEdits() {
        if (!contentArea.isEditable()) return true;
        return aplicarEdicion();
    }

    private boolean confirmDiscard() {
        if (!dirty) return true;
        String device = fs.getDevice();
        if (device != null && journal.isCaptured()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "Hay cambios sin guardar en memoria.\n\n" +
                    "Sí = descartarlos y recuperar la tabla que tenía el medio\n" +
                    "No = perder los cambios sin escribir nada\n" +
                    "Cancelar = volver a la aplicación",
                    "Cambios sin guardar", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
            if (r == JOptionPane.YES_OPTION) {
                try {
                    journal.rollback(device, fs.getBaseOffset());
                } catch (IOException ex) {
                    showError("No se pudo recuperar la tabla anterior", ex, device);
                    return false;
                }
            }
            journal.clear();
            dirty = false;
            return true;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "Hay cambios sin guardar. ¿Descartarlos?",
                "Cambios sin guardar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return false;
        dirty = false;
        journal.clear();
        return true;
    }

    private void showError(String title, Exception ex, String device) {
        log(title + ": " + ex.getMessage());
        StringBuilder msg = new StringBuilder(title).append(":\n\n").append(ex.getMessage());
        if (device != null && DeviceAccess.isPhysical(device)) {
            msg.append("\n\nEjecuta la aplicación como Administrador y cierra los programas que usen la tarjeta.");
        }
        JOptionPane.showMessageDialog(this, msg.toString(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MicroFSBrowser().setVisible(true));
    }
}
