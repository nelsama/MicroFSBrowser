package microfs;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MicroFS Browser - Navegador gráfico para sistema de archivos MicroFS
 * 
 * @author MicroFS Tools
 * @version 1.0
 */
public class MicroFSBrowser extends JFrame {
    
    private static final long serialVersionUID = 1L;
    
    // Constantes MicroFS
    private static final int MFS_MAGIC = 0x4D46;      // "MF"
    private static final int MFS_MAX_FILES = 16;      // 16 entradas * 32 bytes = 512 - 16 header
    private static final int MFS_NAME_LEN = 12;
    private static final int SECTOR_SIZE = 512;
    
    // Componentes UI
    private JTextField deviceField;
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JTextArea contentArea;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JButton connectButton;
    private JButton formatButton;
    private JButton refreshButton;
    private JButton readButton;
    private JButton writeButton;
    private JButton deleteButton;
    private JButton importButton;
    private JButton exportButton;
    private JButton detectDisksButton;
    private JComboBox<String> diskComboBox;
    
    // Estado
    private String currentDevice = "";
    private boolean connected = false;
    private List<MFSFileEntry> files = new ArrayList<>();
    private int numFiles = 0;
    private int nextSector = 1;
    
    /**
     * Entrada de archivo MicroFS
     */
    private static class MFSFileEntry {
        String name;
        int startSector;
        int size;
        int sectors;
        int flags;
        
        MFSFileEntry() {
            this.name = "";
            this.startSector = 0;
            this.size = 0;
            this.sectors = 0;
            this.flags = 0;
        }
        
        MFSFileEntry(String name, int startSector, int size, int sectors, int flags) {
            this.name = name;
            this.startSector = startSector;
            this.size = size;
            this.sectors = sectors;
            this.flags = flags;
        }
    }
    
    public MicroFSBrowser() {
        super("MicroFS Browser v1.0");
        initializeUI();
        setupEventHandlers();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(800, 600));
    }
    
    private void initializeUI() {
        // Look and Feel del sistema
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Usar L&F por defecto
        }
        
        setLayout(new BorderLayout(5, 5));
        
        // Panel superior - Conexión
        JPanel topPanel = createConnectionPanel();
        add(topPanel, BorderLayout.NORTH);
        
        // Panel central - Split entre tabla y contenido
        JSplitPane centerSplit = createCenterPanel();
        add(centerSplit, BorderLayout.CENTER);
        
        // Panel inferior - Log y estado
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Borde general
        ((JPanel)getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));
        
        updateButtonStates();
    }
    
    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 5));
        panel.setBorder(new TitledBorder("Dispositivo SD"));
        
        // Panel superior - Detección de discos
        JPanel diskDetectPanel = new JPanel(new BorderLayout(5, 0));
        diskDetectPanel.add(new JLabel("Discos detectados: "), BorderLayout.WEST);
        diskComboBox = new JComboBox<>();
        diskComboBox.setPreferredSize(new Dimension(400, 25));
        diskDetectPanel.add(diskComboBox, BorderLayout.CENTER);
        detectDisksButton = new JButton("Detectar Discos");
        diskDetectPanel.add(detectDisksButton, BorderLayout.EAST);
        
        // Panel inferior - Campo manual y botones
        JPanel devicePanel = new JPanel(new BorderLayout(5, 0));
        devicePanel.add(new JLabel("Dispositivo: "), BorderLayout.WEST);
        deviceField = new JTextField("\\\\.\\PhysicalDrive2");
        deviceField.setToolTipText("Ej: \\\\.\\PhysicalDrive2 (Windows) o /dev/sdc (Linux)");
        devicePanel.add(deviceField, BorderLayout.CENTER);
        
        // Botones de conexión
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        connectButton = new JButton("Conectar");
        formatButton = new JButton("Formatear");
        refreshButton = new JButton("Actualizar");
        
        buttonPanel.add(connectButton);
        buttonPanel.add(formatButton);
        buttonPanel.add(refreshButton);
        
        devicePanel.add(buttonPanel, BorderLayout.EAST);
        
        // Combinar paneles
        JPanel topContent = new JPanel(new GridLayout(2, 1, 5, 5));
        topContent.add(diskDetectPanel);
        topContent.add(devicePanel);
        
        panel.add(topContent, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JSplitPane createCenterPanel() {
        // Panel izquierdo - Lista de archivos
        JPanel fileListPanel = new JPanel(new BorderLayout(5, 5));
        fileListPanel.setBorder(new TitledBorder("Archivos MicroFS"));
        
        // Tabla de archivos
        String[] columns = {"#", "Nombre", "Tamaño", "Sectores", "Sector Inicio"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        fileTable = new JTable(tableModel);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        
        JScrollPane tableScroll = new JScrollPane(fileTable);
        fileListPanel.add(tableScroll, BorderLayout.CENTER);
        
        // Botones de operaciones
        JPanel opButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        readButton = new JButton("Leer");
        writeButton = new JButton("Escribir");
        deleteButton = new JButton("Eliminar");
        importButton = new JButton("PC → SD");
        exportButton = new JButton("SD → PC");
        
        opButtonPanel.add(readButton);
        opButtonPanel.add(writeButton);
        opButtonPanel.add(deleteButton);
        opButtonPanel.add(new JSeparator(SwingConstants.VERTICAL));
        opButtonPanel.add(importButton);
        opButtonPanel.add(exportButton);
        
        fileListPanel.add(opButtonPanel, BorderLayout.SOUTH);
        
        // Panel derecho - Contenido del archivo
        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(new TitledBorder("Contenido del Archivo"));
        
        contentArea = new JTextArea();
        contentArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        JScrollPane contentScroll = new JScrollPane(contentArea);
        contentPanel.add(contentScroll, BorderLayout.CENTER);
        
        // Vista hexadecimal checkbox
        JPanel viewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox hexCheck = new JCheckBox("Vista Hexadecimal");
        viewPanel.add(hexCheck);
        contentPanel.add(viewPanel, BorderLayout.NORTH);
        
        // Split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, 
                                               fileListPanel, contentPanel);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.5);
        
        return splitPane;
    }
    
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // Log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Log"));
        logArea = new JTextArea(4, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logPanel.add(logScroll, BorderLayout.CENTER);
        
        // Barra de estado
        statusLabel = new JLabel("Desconectado");
        statusLabel.setBorder(new BevelBorder(BevelBorder.LOWERED));
        
        panel.add(logPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private Icon createIcon(String emoji) {
        // Crear icono simple con texto
        return null; // Los iconos emoji no funcionan bien en todos los sistemas
    }
    
    private void setupEventHandlers() {
        connectButton.addActionListener(e -> connectToDevice());
        formatButton.addActionListener(e -> formatDevice());
        refreshButton.addActionListener(e -> refreshFileList());
        readButton.addActionListener(e -> readSelectedFile());
        writeButton.addActionListener(e -> writeFile());
        deleteButton.addActionListener(e -> deleteSelectedFile());
        importButton.addActionListener(e -> importFile());
        exportButton.addActionListener(e -> exportFile());
        detectDisksButton.addActionListener(e -> detectPhysicalDisks());
        
        // Cuando se selecciona un disco del combo, actualizar el campo de texto
        diskComboBox.addActionListener(e -> {
            if (diskComboBox.getSelectedItem() != null) {
                String selected = (String) diskComboBox.getSelectedItem();
                // Formato: "F: - Label (X GB) [Type]|DiskNum|DriveLetter"
                String[] parts = selected.split("\\|");
                if (parts.length >= 2) {
                    String diskNumber = parts[1].trim();
                    // Usar PhysicalDrive para escritura directa (evita "El parámetro no es correcto")
                    deviceField.setText("\\\\.\\PhysicalDrive" + diskNumber);
                } else if (selected.length() >= 2 && selected.charAt(1) == ':') {
                    // Fallback: usar letra de unidad
                    String driveLetter = selected.substring(0, 1);
                    deviceField.setText("\\\\.\\"+  driveLetter + ":");
                }
            }
        });
        
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    readSelectedFile();
                }
            }
        });
        
        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });
        
        // Detectar discos al iniciar
        SwingUtilities.invokeLater(() -> detectPhysicalDisks());
    }
    
    /**
     * Detectar discos removibles usando PowerShell (Windows)
     * Obtiene el PhysicalDriveN correspondiente a cada letra de unidad
     */
    private void detectPhysicalDisks() {
        diskComboBox.removeAllItems();
        diskComboBox.addItem("Detectando discos...");
        
        new Thread(() -> {
            try {
                List<String[]> disks = new ArrayList<>();
                
                // Usar PowerShell para obtener volúmenes removibles con su PhysicalDrive
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-Command",
                    "Get-Partition | Where-Object {$_.DriveLetter -ne $null} | ForEach-Object { " +
                    "$part = $_; " +
                    "$vol = Get-Volume -Partition $part -ErrorAction SilentlyContinue; " +
                    "if ($vol -and ($vol.DriveType -eq 'Removable')) { " +
                    "  $part.DriveLetter + '|' + $part.DiskNumber + '|' + $vol.FileSystemLabel + '|' + [math]::Round($vol.Size/1GB,2) + '|' + $vol.DriveType " +
                    "} " +
                    "}"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && line.contains("|")) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 4) {
                            String driveLetter = parts[0].trim();
                            String diskNumber = parts[1].trim();
                            String label = parts[2].trim();
                            String sizeGB = parts[3].trim();
                            String driveType = parts.length > 4 ? parts[4].trim() : "Removable";
                            
                            if (label.isEmpty()) label = "Sin etiqueta";
                            
                            // Guardar: [displayText, physicalDrive]
                            String displayText = String.format("%s: - %s (%.2f GB) [%s]", 
                                driveLetter, label, Double.parseDouble(sizeGB.isEmpty() ? "0" : sizeGB), driveType);
                            disks.add(new String[]{displayText, diskNumber, driveLetter});
                        }
                    }
                }
                process.waitFor();
                
                // Actualizar combo en el hilo de UI
                final List<String[]> finalDisks = disks;
                SwingUtilities.invokeLater(() -> {
                    diskComboBox.removeAllItems();
                    if (finalDisks.isEmpty()) {
                        diskComboBox.addItem("No se detectaron unidades removibles");
                        log("No se detectaron unidades removibles");
                    } else {
                        for (String[] disk : finalDisks) {
                            // Formato: "F: - Label (X GB) [Type]|DiskNum|DriveLetter"
                            diskComboBox.addItem(disk[0] + "|" + disk[1] + "|" + disk[2]);
                        }
                        log("Detectadas " + finalDisks.size() + " unidad(es) removible(s)");
                    }
                });
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    diskComboBox.removeAllItems();
                    diskComboBox.addItem("Error al detectar discos");
                    log("Error detectando discos: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void updateButtonStates() {
        boolean hasSelection = fileTable.getSelectedRow() >= 0;
        boolean hasDevice = !deviceField.getText().trim().isEmpty();
        
        formatButton.setEnabled(hasDevice);  // Formatear siempre disponible si hay dispositivo
        refreshButton.setEnabled(connected);
        readButton.setEnabled(connected && hasSelection);
        writeButton.setEnabled(connected);
        deleteButton.setEnabled(connected && hasSelection);
        importButton.setEnabled(connected);
        exportButton.setEnabled(connected && hasSelection);
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    private void setStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }
    
    // ==================== Operaciones MicroFS ====================
    
    private byte[] readSector(int sector) throws IOException {
        byte[] buffer = new byte[SECTOR_SIZE];
        
        // En Windows, usar FileInputStream para dispositivos físicos
        try (FileInputStream fis = new FileInputStream(currentDevice)) {
            java.nio.channels.FileChannel channel = fis.getChannel();
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(buffer);
            channel.position((long) sector * SECTOR_SIZE);
            int bytesRead = channel.read(byteBuffer);
            if (bytesRead < SECTOR_SIZE) {
                // Rellenar con ceros si es necesario
                for (int i = Math.max(0, bytesRead); i < SECTOR_SIZE; i++) {
                    buffer[i] = 0;
                }
            }
        }
        return buffer;
    }
    
    private void writeSector(int sector, byte[] data) throws IOException {
        byte[] buffer = new byte[SECTOR_SIZE];
        System.arraycopy(data, 0, buffer, 0, Math.min(data.length, SECTOR_SIZE));
        
        // Crear archivo temporal con los datos del sector
        File tempFile = File.createTempFile("microfs_sector_", ".bin");
        try {
            Files.write(tempFile.toPath(), buffer);
            
            // Usar PowerShell para escribir directamente al disco físico
            // Esto evita las restricciones de Java con dispositivos raw
            long offset = (long) sector * SECTOR_SIZE;
            
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-Command",
                "$bytes = [System.IO.File]::ReadAllBytes('" + tempFile.getAbsolutePath().replace("'", "''") + "'); " +
                "$fs = [System.IO.File]::Open('" + currentDevice.replace("'", "''") + "', " +
                "[System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite); " +
                "$fs.Seek(" + offset + ", [System.IO.SeekOrigin]::Begin) | Out-Null; " +
                "$fs.Write($bytes, 0, $bytes.Length); " +
                "$fs.Flush(); " +
                "$fs.Close();"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Error al escribir sector " + sector + ": " + output.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Escritura interrumpida", e);
        } finally {
            tempFile.delete();
        }
    }
    
    private void loadSuperblock() throws IOException {
        byte[] data = readSector(0);
        
        // Leer header
        int magic = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        int version = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        numFiles = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        nextSector = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        
        if (magic != MFS_MAGIC) {
            throw new IOException("No es un sistema MicroFS válido (magic: 0x" + 
                                  Integer.toHexString(magic) + ")");
        }
        
        // Leer entradas de archivos (15 entradas de 32 bytes cada una)
        files.clear();
        for (int i = 0; i < MFS_MAX_FILES; i++) {
            int offset = 16 + i * 32;
            if (offset + 19 > SECTOR_SIZE) break;
            
            // Nombre (12 bytes)
            StringBuilder nameBuilder = new StringBuilder();
            for (int j = 0; j < MFS_NAME_LEN; j++) {
                byte b = data[offset + j];
                if (b == 0) break;
                nameBuilder.append((char) b);
            }
            String name = nameBuilder.toString();
            
            // Campos numéricos
            int startSector = (data[offset + 12] & 0xFF) | ((data[offset + 13] & 0xFF) << 8);
            int size = (data[offset + 14] & 0xFF) | ((data[offset + 15] & 0xFF) << 8);
            int sectors = (data[offset + 16] & 0xFF) | ((data[offset + 17] & 0xFF) << 8);
            int flags = data[offset + 18] & 0xFF;
            
            files.add(new MFSFileEntry(name, startSector, size, sectors, flags));
        }
    }
    
    private void saveSuperblock() throws IOException {
        byte[] data = new byte[SECTOR_SIZE];
        
        // Header
        data[0] = (byte) (MFS_MAGIC & 0xFF);
        data[1] = (byte) ((MFS_MAGIC >> 8) & 0xFF);
        data[2] = 1; // version
        data[3] = 0;
        data[4] = (byte) (numFiles & 0xFF);
        data[5] = (byte) ((numFiles >> 8) & 0xFF);
        data[6] = (byte) (nextSector & 0xFF);
        data[7] = (byte) ((nextSector >> 8) & 0xFF);
        
        // Entradas (15 entradas * 32 bytes = 480 bytes, offset 16, total = 496)
        for (int i = 0; i < MFS_MAX_FILES; i++) {
            MFSFileEntry entry = (i < files.size()) ? files.get(i) : new MFSFileEntry();
            int offset = 16 + i * 32;
            
            // Verificar que no excedemos el sector
            if (offset + 19 > SECTOR_SIZE) break;
            
            // Nombre
            byte[] nameBytes = entry.name.getBytes("ASCII");
            for (int j = 0; j < MFS_NAME_LEN; j++) {
                data[offset + j] = (j < nameBytes.length) ? nameBytes[j] : 0;
            }
            
            // Campos numéricos
            data[offset + 12] = (byte) (entry.startSector & 0xFF);
            data[offset + 13] = (byte) ((entry.startSector >> 8) & 0xFF);
            data[offset + 14] = (byte) (entry.size & 0xFF);
            data[offset + 15] = (byte) ((entry.size >> 8) & 0xFF);
            data[offset + 16] = (byte) (entry.sectors & 0xFF);
            data[offset + 17] = (byte) ((entry.sectors >> 8) & 0xFF);
            data[offset + 18] = (byte) (entry.flags & 0xFF);
        }
        
        writeSector(0, data);
    }
    
    private int findFile(String name) {
        String upperName = name.toUpperCase();
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).name.toUpperCase().equals(upperName)) {
                return i;
            }
        }
        return -1;
    }
    
    // ==================== Acciones de UI ====================
    
    private void connectToDevice() {
        currentDevice = deviceField.getText().trim();
        if (currentDevice.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Por favor, especifica un dispositivo", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            loadSuperblock();
            connected = true;
            log("Conectado a " + currentDevice);
            setStatus("Conectado: " + currentDevice + " | Archivos: " + numFiles + 
                     " | Próximo sector: " + nextSector);
            refreshFileTable();
            updateButtonStates();
            connectButton.setText("Reconectar");
        } catch (IOException e) {
            connected = false;
            log("Error al conectar: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al conectar:\n" + e.getMessage() + 
                "\n\nAsegúrate de ejecutar como Administrador.", 
                "Error de Conexión", JOptionPane.ERROR_MESSAGE);
            updateButtonStates();
        }
    }
    
    private void formatDevice() {
        currentDevice = deviceField.getText().trim();
        if (currentDevice.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Por favor, especifica un dispositivo", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        int result = JOptionPane.showConfirmDialog(this,
            "¿Estás seguro de que deseas formatear?\n" +
            "Dispositivo: " + currentDevice + "\n\n" +
            "¡TODOS LOS DATOS SERÁN ELIMINADOS!",
            "Confirmar Formateo",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        
        try {
            numFiles = 0;
            nextSector = 1;
            files.clear();
            for (int i = 0; i < MFS_MAX_FILES; i++) {
                files.add(new MFSFileEntry());
            }
            saveSuperblock();
            
            connected = true;
            log("SD formateada con MicroFS");
            setStatus("Formateado: " + currentDevice);
            refreshFileTable();
            updateButtonStates();
            connectButton.setText("Reconectar");
            
            JOptionPane.showMessageDialog(this, 
                "SD formateada correctamente con MicroFS", 
                "Formateo Completo", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            log("Error al formatear: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al formatear:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void refreshFileList() {
        if (!connected) return;
        
        try {
            loadSuperblock();
            refreshFileTable();
            setStatus("Conectado: " + currentDevice + " | Archivos: " + numFiles + 
                     " | Próximo sector: " + nextSector);
            log("Lista de archivos actualizada");
        } catch (IOException e) {
            log("Error al actualizar: " + e.getMessage());
        }
    }
    
    private void refreshFileTable() {
        tableModel.setRowCount(0);
        int index = 0;
        for (MFSFileEntry entry : files) {
            if (!entry.name.isEmpty()) {
                tableModel.addRow(new Object[]{
                    index,
                    entry.name,
                    entry.size + " bytes",
                    entry.sectors,
                    entry.startSector
                });
            }
            index++;
        }
    }
    
    private void readSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, 
                "Selecciona un archivo de la lista", 
                "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String fileName = (String) tableModel.getValueAt(selectedRow, 1);
        
        try {
            int idx = findFile(fileName);
            if (idx < 0) {
                log("Archivo no encontrado: " + fileName);
                return;
            }
            
            MFSFileEntry entry = files.get(idx);
            byte[] data = new byte[entry.size];
            int bytesRead = 0;
            
            for (int s = 0; s < entry.sectors && bytesRead < entry.size; s++) {
                byte[] sectorData = readSector(entry.startSector + s);
                int toCopy = Math.min(SECTOR_SIZE, entry.size - bytesRead);
                System.arraycopy(sectorData, 0, data, bytesRead, toCopy);
                bytesRead += toCopy;
            }
            
            // Mostrar contenido
            String content = new String(data, "UTF-8");
            contentArea.setText(content);
            contentArea.setCaretPosition(0);
            
            log("Leído: " + fileName + " (" + entry.size + " bytes)");
            
        } catch (IOException e) {
            log("Error al leer: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al leer archivo:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void writeFile() {
        String fileName = JOptionPane.showInputDialog(this, 
            "Nombre del archivo (max 11 caracteres, ej: TEST.TXT):",
            "Escribir Archivo",
            JOptionPane.QUESTION_MESSAGE);
        
        if (fileName == null || fileName.trim().isEmpty()) {
            return;
        }
        
        fileName = fileName.toUpperCase().trim();
        if (fileName.length() > MFS_NAME_LEN - 1) {
            fileName = fileName.substring(0, MFS_NAME_LEN - 1);
        }
        
        String content = contentArea.getText();
        if (content.isEmpty()) {
            int result = JOptionPane.showConfirmDialog(this,
                "El contenido está vacío. ¿Deseas crear un archivo vacío?",
                "Confirmar",
                JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        
        try {
            byte[] contentBytes = content.getBytes("UTF-8");
            int idx = findFile(fileName);
            
            if (idx >= 0) {
                // Archivo existe
                MFSFileEntry entry = files.get(idx);
                if (contentBytes.length > entry.sectors * SECTOR_SIZE) {
                    JOptionPane.showMessageDialog(this,
                        "El contenido es muy grande para el archivo existente.\n" +
                        "Máximo: " + (entry.sectors * SECTOR_SIZE) + " bytes\n" +
                        "Actual: " + contentBytes.length + " bytes",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // Escribir datos
                for (int s = 0; s < entry.sectors; s++) {
                    byte[] sectorData = new byte[SECTOR_SIZE];
                    int start = s * SECTOR_SIZE;
                    int toCopy = Math.min(SECTOR_SIZE, contentBytes.length - start);
                    if (toCopy > 0) {
                        System.arraycopy(contentBytes, start, sectorData, 0, toCopy);
                    }
                    writeSector(entry.startSector + s, sectorData);
                }
                
                entry.size = contentBytes.length;
                saveSuperblock();
                log("Archivo actualizado: " + fileName + " (" + contentBytes.length + " bytes)");
                
            } else {
                // Crear nuevo archivo
                int slot = -1;
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).name.isEmpty()) {
                        slot = i;
                        break;
                    }
                }
                
                if (slot < 0) {
                    JOptionPane.showMessageDialog(this,
                        "No hay espacio para más archivos (máximo: " + MFS_MAX_FILES + ")",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                int sectors = (contentBytes.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
                if (sectors == 0) sectors = 1;
                
                // Escribir datos
                for (int s = 0; s < sectors; s++) {
                    byte[] sectorData = new byte[SECTOR_SIZE];
                    int start = s * SECTOR_SIZE;
                    int toCopy = Math.min(SECTOR_SIZE, contentBytes.length - start);
                    if (toCopy > 0) {
                        System.arraycopy(contentBytes, start, sectorData, 0, toCopy);
                    }
                    writeSector(nextSector + s, sectorData);
                }
                
                // Crear entrada
                files.set(slot, new MFSFileEntry(fileName, nextSector, 
                                                  contentBytes.length, sectors, 0));
                nextSector += sectors;
                numFiles++;
                saveSuperblock();
                
                log("Archivo creado: " + fileName + " (" + contentBytes.length + 
                    " bytes, " + sectors + " sector(es))");
            }
            
            refreshFileTable();
            setStatus("Conectado: " + currentDevice + " | Archivos: " + numFiles + 
                     " | Próximo sector: " + nextSector);
            
            JOptionPane.showMessageDialog(this, 
                "Archivo guardado: " + fileName, 
                "Éxito", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            log("Error al escribir: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al escribir archivo:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void deleteSelectedFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, 
                "Selecciona un archivo de la lista", 
                "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String fileName = (String) tableModel.getValueAt(selectedRow, 1);
        
        int result = JOptionPane.showConfirmDialog(this,
            "¿Eliminar archivo '" + fileName + "'?",
            "Confirmar Eliminación",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        
        try {
            int idx = findFile(fileName);
            if (idx < 0) {
                log("Archivo no encontrado: " + fileName);
                return;
            }
            
            files.set(idx, new MFSFileEntry());
            numFiles--;
            saveSuperblock();
            
            refreshFileTable();
            contentArea.setText("");
            setStatus("Conectado: " + currentDevice + " | Archivos: " + numFiles + 
                     " | Próximo sector: " + nextSector);
            log("Archivo eliminado: " + fileName);
            
            JOptionPane.showMessageDialog(this, 
                "Archivo eliminado: " + fileName, 
                "Éxito", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            log("Error al eliminar: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al eliminar archivo:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void importFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Seleccionar archivo a importar");
        
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        
        File localFile = fileChooser.getSelectedFile();
        
        // Pedir nombre en MicroFS
        String defaultName = localFile.getName().toUpperCase();
        if (defaultName.length() > MFS_NAME_LEN - 1) {
            defaultName = defaultName.substring(0, MFS_NAME_LEN - 1);
        }
        
        String mfsName = (String) JOptionPane.showInputDialog(this,
            "Nombre del archivo en MicroFS:",
            "Nombre en MicroFS",
            JOptionPane.QUESTION_MESSAGE,
            null, null, defaultName);
        
        if (mfsName == null || mfsName.trim().isEmpty()) {
            return;
        }
        
        mfsName = mfsName.toUpperCase().trim();
        if (mfsName.length() > MFS_NAME_LEN - 1) {
            mfsName = mfsName.substring(0, MFS_NAME_LEN - 1);
        }
        
        try {
            byte[] contentBytes = Files.readAllBytes(localFile.toPath());
            
            // Verificar tamaño
            if (contentBytes.length > 65535) {
                JOptionPane.showMessageDialog(this,
                    "El archivo es muy grande (máximo: 65535 bytes)",
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Usar lógica similar a writeFile
            int idx = findFile(mfsName);
            
            if (idx >= 0) {
                MFSFileEntry entry = files.get(idx);
                if (contentBytes.length > entry.sectors * SECTOR_SIZE) {
                    JOptionPane.showMessageDialog(this,
                        "El contenido es muy grande para el archivo existente.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                for (int s = 0; s < entry.sectors; s++) {
                    byte[] sectorData = new byte[SECTOR_SIZE];
                    int start = s * SECTOR_SIZE;
                    int toCopy = Math.min(SECTOR_SIZE, contentBytes.length - start);
                    if (toCopy > 0) {
                        System.arraycopy(contentBytes, start, sectorData, 0, toCopy);
                    }
                    writeSector(entry.startSector + s, sectorData);
                }
                
                entry.size = contentBytes.length;
                saveSuperblock();
                
            } else {
                int slot = -1;
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).name.isEmpty()) {
                        slot = i;
                        break;
                    }
                }
                
                if (slot < 0) {
                    JOptionPane.showMessageDialog(this,
                        "No hay espacio para más archivos",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                int sectors = (contentBytes.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
                if (sectors == 0) sectors = 1;
                
                for (int s = 0; s < sectors; s++) {
                    byte[] sectorData = new byte[SECTOR_SIZE];
                    int start = s * SECTOR_SIZE;
                    int toCopy = Math.min(SECTOR_SIZE, contentBytes.length - start);
                    if (toCopy > 0) {
                        System.arraycopy(contentBytes, start, sectorData, 0, toCopy);
                    }
                    writeSector(nextSector + s, sectorData);
                }
                
                files.set(slot, new MFSFileEntry(mfsName, nextSector, 
                                                  contentBytes.length, sectors, 0));
                nextSector += sectors;
                numFiles++;
                saveSuperblock();
            }
            
            refreshFileTable();
            setStatus("Conectado: " + currentDevice + " | Archivos: " + numFiles + 
                     " | Próximo sector: " + nextSector);
            log("Importado: " + localFile.getName() + " -> " + mfsName + 
                " (" + contentBytes.length + " bytes)");
            
            JOptionPane.showMessageDialog(this, 
                "Archivo importado: " + mfsName, 
                "Éxito", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            log("Error al importar: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al importar archivo:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void exportFile() {
        int selectedRow = fileTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, 
                "Selecciona un archivo de la lista", 
                "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String fileName = (String) tableModel.getValueAt(selectedRow, 1);
        
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Guardar archivo como");
        fileChooser.setSelectedFile(new File(fileName));
        
        int result = fileChooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        
        File localFile = fileChooser.getSelectedFile();
        
        try {
            int idx = findFile(fileName);
            if (idx < 0) {
                log("Archivo no encontrado: " + fileName);
                return;
            }
            
            MFSFileEntry entry = files.get(idx);
            byte[] data = new byte[entry.size];
            int bytesRead = 0;
            
            for (int s = 0; s < entry.sectors && bytesRead < entry.size; s++) {
                byte[] sectorData = readSector(entry.startSector + s);
                int toCopy = Math.min(SECTOR_SIZE, entry.size - bytesRead);
                System.arraycopy(sectorData, 0, data, bytesRead, toCopy);
                bytesRead += toCopy;
            }
            
            Files.write(localFile.toPath(), data);
            
            log("Exportado: " + fileName + " -> " + localFile.getAbsolutePath());
            
            JOptionPane.showMessageDialog(this, 
                "Archivo exportado a:\n" + localFile.getAbsolutePath(), 
                "Éxito", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (IOException e) {
            log("Error al exportar: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al exportar archivo:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    // ==================== Main ====================
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MicroFSBrowser browser = new MicroFSBrowser();
            browser.setVisible(true);
        });
    }
}
