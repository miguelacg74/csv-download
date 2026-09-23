package com.csv.download;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Dialogo modal de entrada para la descarga de tablas a CSV: listado de
 * tablas (separadas por comas, punto y coma o saltos de linea), carpeta de
 * salida, condicion WHERE opcional aplicada a todas las tablas y limite de
 * filas opcional (0 = sin limite).
 *
 * Alternativa puntual: activando "Ejecutar consulta personalizada" se pega
 * un SELECT/WITH propio y se exporta su resultado a un unico CSV (el listado
 * y el WHERE se ignoran).
 *
 * El estado completo se puede guardar/recargar como preset JSON
 * ({@link ExportPreset}) con los botones "Guardar preset..."/"Cargar
 * preset...". El boton "Recientes" despliega el historial de los
 * ultimos presets usados ({@link PresetHistory}) para cargarlos sin
 * tener que buscar de nuevo la ruta del archivo.
 *
 * Modo lote: "Ejecutar presets de carpeta..." ejecuta todos los presets
 * (*.json) de una carpeta elegida por el usuario ({@link PresetBatch}),
 * cada uno con su propia carpeta de salida.
 *
 * Es Swing puro: recibe el padre por parametro y no usa APIs del IDE.
 */
public class CsvExportDialog extends JDialog {

    private static final String DEFAULT_QUERY_FILE = "consulta";

    /** Peticion validada por el dialogo, lista para {@link CsvExporter}. */
    public static final class Request {

        private final List<String> tables;
        private final File directory;
        private final String where;
        private final int maxRows;
        private final boolean skipExisting;
        private final boolean useCustomQuery;
        private final String customQuery;
        private final String customFileName;
        private final List<File> presetFiles;

        private Request(List<String> tables, File directory, String where,
                        int maxRows, boolean skipExisting,
                        boolean useCustomQuery,
                        String customQuery, String customFileName,
                        List<File> presetFiles) {
            this.tables = tables;
            this.directory = directory;
            this.where = where;
            this.maxRows = maxRows;
            this.skipExisting = skipExisting;
            this.useCustomQuery = useCustomQuery;
            this.customQuery = customQuery;
            this.customFileName = customFileName;
            this.presetFiles = presetFiles;
        }

        /**
         * Peticion de ejecucion en lote: se ignoran los campos del dialogo
         * y se ejecutan los presets (*.json) indicados, cada uno con su
         * propia configuracion. Solo se conserva {@code skipExisting}: si
         * el checkbox del dialogo esta marcado, fuerza la omision de los
         * CSV existentes en TODO el lote (ademas del flag de cada preset).
         */
        public static Request forPresetBatch(List<File> presetFiles,
                                             boolean skipExisting) {
            return new Request(null, null, "", 0, skipExisting, false, "",
                    "", presetFiles);
        }

        /** Nombres de tabla tal cual los escribio el usuario. */
        public List<String> getTables() {
            return tables;
        }

        public File getDirectory() {
            return directory;
        }

        /** Condicion sin la palabra WHERE; cadena vacia si no se indico. */
        public String getWhere() {
            return where;
        }

        /** Maximo de filas por tabla; 0 = sin limite. */
        public int getMaxRows() {
            return maxRows;
        }

        /**
         * True si hay que omitir las tablas cuyo CSV ya existe en la
         * carpeta de salida en vez de reemplazarlo.
         */
        public boolean isSkipExisting() {
            return skipExisting;
        }

        /** True si hay que exportar una consulta en vez del listado. */
        public boolean isUseCustomQuery() {
            return useCustomQuery;
        }

        /** SELECT/WITH del usuario; solo si {@link #isUseCustomQuery()}. */
        public String getCustomQuery() {
            return customQuery;
        }

        /** Nombre del CSV para la consulta; "consulta" si quedo vacio. */
        public String getCustomFileName() {
            return customFileName;
        }

        /** True si la peticion es un lote de presets ({@link PresetBatch}). */
        public boolean isPresetBatch() {
            return presetFiles != null;
        }

        /** Archivos de preset del lote; null en el modo normal. */
        public List<File> getPresetFiles() {
            return presetFiles;
        }
    }

    private final JTextArea tablesArea = new JTextArea(6, 34);
    private final JCheckBox customCheck =
            new JCheckBox("Ejecutar consulta personalizada "
                    + "(ignora el listado y el WHERE)");
    private final JTextArea queryArea = new JTextArea(5, 34);
    private final JTextField customFileField = new JTextField(DEFAULT_QUERY_FILE, 18);
    private final JTextField dirField = new JTextField(28);
    private final JLabel whereLabel =
            new JLabel("Condicion WHERE (opcional, para todas las tablas):");
    private final JTextField whereField = new JTextField(28);
    private final JTextField maxRowsField = new JTextField("0", 8);
    private final JCheckBox skipCheck = new JCheckBox(
            "No reemplazar los CSV que ya existen (omite esas tablas)");
    private Request request;
    /** Ultimo preset cargado/guardado: destino por defecto al guardar. */
    private File presetFile;
    /** Historial de presets recientes; null si no hay persistencia. */
    private final PresetHistory history = createHistory();

    private CsvExportDialog(Frame owner, String connectionName) {
        super(owner, "Descargar tablas a CSV", true);
        build(connectionName);
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * Abre el dialogo y devuelve la peticion validada, o null si el usuario
     * cancelo. {@code connectionName} solo se usa para mostrar en el titulo
     * que conexion se va a usar (puede ser null).
     */
    public static Request ask(Component parent, String connectionName) {
        return ask(parent, connectionName, "");
    }

    /**
     * Igual que {@link #ask(Component, String)} pero pre-rellena el listado
     * de tablas (p.ej. con las tablas seleccionadas en el navegador de
     * conexiones); el usuario puede editarlo antes de descargar.
     */
    public static Request ask(Component parent, String connectionName,
                              String initialTables) {
        Frame owner = parent instanceof Frame
                ? (Frame) parent
                : (Frame) JOptionPane.getFrameForComponent(parent);
        CsvExportDialog dialog = new CsvExportDialog(owner, connectionName);
        if (initialTables != null && !initialTables.trim().isEmpty()) {
            dialog.tablesArea.setText(initialTables.trim());
            dialog.tablesArea.setCaretPosition(0);
        }
        dialog.setVisible(true);
        return dialog.request;
    }

    private void build(String connectionName) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        String title = "Tablas (separadas por comas):";
        if (connectionName != null && !connectionName.trim().isEmpty()) {
            title = "Tablas de '" + connectionName + "' (separadas por comas):";
        }
        addRow(form, g, row++, new JLabel(title));
        tablesArea.setLineWrap(true);
        tablesArea.setWrapStyleWord(true);
        addRow(form, g, row++, new JScrollPane(tablesArea));

        customCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleCustomMode();
            }
        });
        addRow(form, g, row++, customCheck);
        queryArea.setLineWrap(true);
        addRow(form, g, row++, new JScrollPane(queryArea));

        JPanel filePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filePanel.add(new JLabel("Nombre del CSV:"));
        filePanel.add(customFileField);
        addRow(form, g, row++, filePanel);
        toggleCustomMode();

        addRow(form, g, row++, new JLabel("Carpeta de salida:"));
        JPanel dirPanel = new JPanel(new BorderLayout(4, 0));
        dirPanel.add(dirField, BorderLayout.CENTER);
        JButton browse = new JButton("Examinar...");
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chooseDirectory();
            }
        });
        dirPanel.add(browse, BorderLayout.EAST);
        addRow(form, g, row++, dirPanel);

        addRow(form, g, row++, whereLabel);
        addRow(form, g, row++, whereField);

        JPanel limitPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        limitPanel.add(new JLabel("Maximo de filas por tabla (0 = sin limite):"));
        limitPanel.add(maxRowsField);
        addRow(form, g, row++, limitPanel);
        addRow(form, g, row++, skipCheck);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton load = new JButton("Cargar preset...");
        load.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                loadPreset();
            }
        });
        JButton save = new JButton("Guardar preset...");
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                savePreset();
            }
        });
        presets.add(load);
        presets.add(save);
        if (history != null) {
            final JButton recent = new JButton("Recientes");
            recent.setToolTipText(
                    "Ultimos presets cargados o guardados");
            recent.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    showHistoryMenu(recent);
                }
            });
            presets.add(recent);
        }
        JButton batch = new JButton("Ejecutar presets de carpeta...");
        batch.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runPresetFolder();
            }
        });
        presets.add(batch);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton export = new JButton("Descargar");
        export.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                accept();
            }
        });
        JButton cancel = new JButton("Cancelar");
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        actions.add(export);
        actions.add(cancel);

        buttons.add(presets, BorderLayout.WEST);
        buttons.add(actions, BorderLayout.EAST);
        getRootPane().setDefaultButton(export);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
    }

    private void addRow(JPanel form, GridBagConstraints g, int row,
                        Component component) {
        g.gridy = row;
        g.gridx = 0;
        g.weightx = 1;
        form.add(component, g);
    }

    /** Habilita/deshabilita los campos segun el modo consulta personalizada. */
    private void toggleCustomMode() {
        boolean custom = customCheck.isSelected();
        tablesArea.setEnabled(!custom);
        queryArea.setEnabled(custom);
        customFileField.setEnabled(custom);
        whereLabel.setEnabled(!custom);
        whereField.setEnabled(!custom);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Carpeta de salida para los CSV");
        String current = dirField.getText().trim();
        if (current.isEmpty() && history != null) {
            current = history.lastOutputDirectory();
        }
        if (!current.isEmpty()) {
            chooser.setCurrentDirectory(new File(current));
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- presets

    private void loadPreset() {
        JFileChooser chooser = presetChooser("Cargar preset de exportacion");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        loadPresetFile(chooser.getSelectedFile());
    }

    /** Carga el preset indicado y lo apunta en el historial. */
    private void loadPresetFile(File file) {
        try {
            apply(ExportPreset.load(file));
            presetFile = file;
            if (history != null) {
                history.add(file);
            }
        } catch (Exception e) {
            warn("No se pudo cargar el preset:\n" + e.getMessage());
            forgetIfMissing(file);
        }
    }

    /**
     * Menu desplegable del boton "Recientes": los ultimos presets
     * usados (los mas recientes primero) y la opcion de vaciar el
     * historial.
     */
    private void showHistoryMenu(JButton source) {
        List<File> files = history.entries();
        JPopupMenu menu = new JPopupMenu();
        if (files.isEmpty()) {
            JMenuItem empty = new JMenuItem("(sin presets recientes)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (final File file : files) {
                JMenuItem item = new JMenuItem(menuLabel(file));
                item.setToolTipText(file.getAbsolutePath());
                item.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        loadPresetFile(file);
                    }
                });
                menu.add(item);
            }
            menu.addSeparator();
            JMenuItem clear = new JMenuItem("Limpiar historial");
            clear.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    history.clear();
                }
            });
            menu.add(clear);
        }
        menu.show(source, 0, source.getHeight());
    }

    /** Etiqueta del menu: nombre del preset y su carpeta (acortada). */
    private static String menuLabel(File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            return file.getName();
        }
        String path = parent.getAbsolutePath();
        if (path.length() > 45) {
            path = path.substring(0, 20) + "..."
                    + path.substring(path.length() - 22);
        }
        return file.getName() + "   (" + path + ")";
    }

    /**
     * Si un preset del historial ya no existe en disco, ofrece
     * sacarlo de la lista de recientes.
     */
    private void forgetIfMissing(File file) {
        if (history == null || file.isFile()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "El archivo ya no existe. ¿Quitarlo del historial?",
                getTitle(), JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION) {
            history.remove(file);
        }
    }

    private void savePreset() {
        JFileChooser chooser = presetChooser("Guardar preset de exportacion");
        if (presetFile != null) {
            chooser.setCurrentDirectory(presetFile.getParentFile());
            chooser.setSelectedFile(presetFile);
        } else {
            chooser.setSelectedFile(new File("export.json"));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        try {
            toPreset().save(file);
            presetFile = file;
            if (history != null) {
                history.add(file);
            }
        } catch (Exception e) {
            warn("No se pudo guardar el preset:\n" + e.getMessage());
        }
    }

    private JFileChooser presetChooser(String title) {
        File start = presetStartDir();
        JFileChooser chooser = start == null
                ? new JFileChooser() : new JFileChooser(start);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(
                new FileNameExtensionFilter("Preset JSON (*.json)", "json"));
        return chooser;
    }

    /**
     * Carpeta inicial de los selectores de preset: la del ultimo
     * preset cargado/guardado o, en su defecto, la del preset mas
     * reciente del historial.
     */
    private File presetStartDir() {
        if (presetFile != null && presetFile.getParentFile() != null) {
            return presetFile.getParentFile();
        }
        if (history != null) {
            for (File file : history.entries()) {
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) {
                    return parent;
                }
            }
        }
        return null;
    }

    /**
     * Modo lote: el usuario elige una carpeta con presets (*.json), se le
     * muestran los encontrados y, si confirma, la peticion resultante es de
     * tipo {@link Request#isPresetBatch()} (cada preset usara su propia
     * carpeta de salida).
     */
    private void runPresetFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Carpeta con presets (*.json)");
        if (history != null) {
            String last = history.lastBatchDirectory();
            if (!last.isEmpty()) {
                chooser.setCurrentDirectory(new File(last));
            }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<File> files = PresetBatch.listPresets(chooser.getSelectedFile());
        if (files.isEmpty()) {
            warn("La carpeta elegida no contiene presets (*.json).");
            return;
        }
        if (history != null) {
            history.setLastBatchDirectory(chooser.getSelectedFile());
        }
        StringBuilder list = new StringBuilder();
        int shown = Math.min(files.size(), 15);
        for (int i = 0; i < shown; i++) {
            list.append("  ").append(files.get(i).getName()).append('\n');
        }
        if (files.size() > shown) {
            list.append("  ... y ").append(files.size() - shown)
                    .append(" mas\n");
        }
        String skipNote = skipCheck.isSelected()
                ? "Se omitiran los CSV que ya existen en cada carpeta.\n" : "";
        if (JOptionPane.showConfirmDialog(this,
                "Se ejecutaran " + files.size() + " presets de\n"
                        + chooser.getSelectedFile() + "\n\n" + list
                        + "\nCada preset usara su propia carpeta de salida.\n"
                        + skipNote + "¿Continuar?",
                getTitle(), JOptionPane.YES_NO_OPTION)
                != JOptionPane.YES_OPTION) {
            return;
        }
        request = Request.forPresetBatch(files, skipCheck.isSelected());
        dispose();
    }

    /** Vuelca los campos actuales del dialogo a un preset. */
    private ExportPreset toPreset() {
        ExportPreset preset = new ExportPreset();
        preset.setTables(tablesArea.getText());
        preset.setDirectory(dirField.getText().trim());
        preset.setWhere(whereField.getText().trim());
        preset.setMaxRows(parseMaxRows(0));
        preset.setSkipExisting(skipCheck.isSelected());
        preset.setUseCustomQuery(customCheck.isSelected());
        preset.setCustomQuery(queryArea.getText());
        preset.setCustomFileName(customFileField.getText().trim());
        return preset;
    }

    /** Rellena los campos del dialogo desde un preset cargado. */
    private void apply(ExportPreset preset) {
        tablesArea.setText(preset.getTables());
        dirField.setText(preset.getDirectory());
        whereField.setText(preset.getWhere());
        maxRowsField.setText(String.valueOf(preset.getMaxRows()));
        skipCheck.setSelected(preset.isSkipExisting());
        customCheck.setSelected(preset.isUseCustomQuery());
        queryArea.setText(preset.getCustomQuery());
        customFileField.setText(preset.getCustomFileName().isEmpty()
                ? DEFAULT_QUERY_FILE : preset.getCustomFileName());
        toggleCustomMode();
    }

    // --------------------------------------------------------------- aceptar

    private void accept() {
        boolean custom = customCheck.isSelected();
        String customQuery = "";
        String customFileName = "";
        List<String> tables = null;

        if (custom) {
            try {
                customQuery = CsvExporter.checkedQuery(queryArea.getText());
            } catch (java.sql.SQLException e) {
                warn(e.getMessage());
                return;
            }
            customFileName = customFileField.getText().trim();
            if (customFileName.isEmpty()) {
                customFileName = DEFAULT_QUERY_FILE;
            }
        } else {
            tables = CsvExporter.parseTableList(tablesArea.getText());
            if (tables.isEmpty()) {
                warn("Escribe al menos una tabla (separadas por comas)\n"
                        + "o activa la consulta personalizada.");
                return;
            }
        }

        File dir = checkedDirectory();
        if (dir == null) {
            return;
        }
        if (history != null) {
            history.setLastOutputDirectory(dir);
        }

        int maxRows;
        try {
            maxRows = Integer.parseInt(maxRowsField.getText().trim());
            if (maxRows < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            warn("El maximo de filas debe ser un entero >= 0 (0 = sin limite).");
            return;
        }

        request = new Request(tables, dir, whereField.getText().trim(),
                maxRows, skipCheck.isSelected(), custom, customQuery,
                customFileName, null);
        dispose();
    }

    /** Carpeta validada (la crea si el usuario lo confirma) o null. */
    private File checkedDirectory() {
        String dirText = dirField.getText().trim();
        if (dirText.isEmpty()) {
            warn("Elige la carpeta de salida donde dejar los CSV.");
            return null;
        }
        File dir = new File(dirText);
        if (!dir.exists()) {
            if (JOptionPane.showConfirmDialog(this,
                    "La carpeta no existe:\n" + dir + "\n\n¿Crearla?",
                    getTitle(), JOptionPane.YES_NO_OPTION)
                    != JOptionPane.YES_OPTION || !dir.mkdirs()) {
                warn("La carpeta de salida no existe y no se pudo crear.");
                return null;
            }
        }
        if (!dir.isDirectory()) {
            warn("La ruta de salida no es una carpeta.");
            return null;
        }
        return dir;
    }

    private int parseMaxRows(int fallback) {
        try {
            return Integer.parseInt(maxRowsField.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(),
                JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Historial del usuario; null si el almacen de preferencias no
     * esta disponible (el boton "Recientes" ni siquiera se muestra).
     */
    private static PresetHistory createHistory() {
        try {
            return PresetHistory.userDefault();
        } catch (Throwable e) {
            return null;
        }
    }
}
