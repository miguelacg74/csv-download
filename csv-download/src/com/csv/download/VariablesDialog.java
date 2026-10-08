package com.csv.download;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Dialogo modal para definir las variables de consulta
 * ({@link QueryVariables}) en dos ambitos:
 *  - Globales: se guardan en las preferencias del usuario y valen para
 *    todas las exportaciones y lotes de presets.
 *  - Del preset: se guardan dentro del preset JSON y sobrescriben a las
 *    globales del mismo nombre.
 *
 * La pestana Globales tiene "Exportar..."/"Importar..." para llevar las
 * variables a otro equipo en un archivo JSON portable
 * ({@link QueryVariables#toFileJson()}); lo importado se fusiona en la
 * tabla (gana el archivo) y solo se guarda al pulsar Aceptar.
 *
 * Cada fila tiene nombre, tipo, valor y descripcion; la columna "Valor
 * hoy" muestra como se resuelve el valor en este momento (util para los
 * valores relativos como INICIO_MES-1M) o el error si no es valido.
 *
 * Swing puro: no usa APIs del IDE.
 */
final class VariablesDialog extends JDialog {

    private static final int COL_NAME = 0;
    private static final int COL_TYPE = 1;
    private static final int COL_VALUE = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_PREVIEW = 4;
    private static final String[] COLUMN_TIPS = {
            "Nombre sin ':' (letra inicial y luego letras, digitos o _);"
                    + " se usa como :NOMBRE",
            "Texto, Numero, Fecha o Fecha-hora: decide como se valida y se"
                    + " enlaza el valor",
            "Valor (numeros con punto decimal; fechas AAAA-MM-DD o relativas"
                    + " como INICIO_MES-1M); vacio = NULL",
            "Nota libre para recordar para que sirve la variable",
            "Como se resuelve el valor ahora mismo (o el error si no es"
                    + " valido); solo lectura"};

    /** Variables editadas en los dos ambitos. */
    static final class Result {

        private final QueryVariables globals;
        private final QueryVariables preset;

        private Result(QueryVariables globals, QueryVariables preset) {
            this.globals = globals;
            this.preset = preset;
        }

        QueryVariables getGlobals() {
            return globals;
        }

        QueryVariables getPreset() {
            return preset;
        }
    }

    /** Nombre propuesto al exportar las variables globales. */
    private static final String DEFAULT_FILE = "variables-globales.json";
    /** Ultima carpeta usada al exportar/importar (dura la sesion). */
    private static File lastDirectory;

    private final VariableTable globalTable;
    private final VariableTable presetTable;
    private Result result;

    private VariablesDialog(Window owner, QueryVariables globals,
                            QueryVariables preset, boolean globalsPersist) {
        super(owner, "Variables de consulta", ModalityType.APPLICATION_MODAL);
        globalTable = new VariableTable(globals);
        presetTable = new VariableTable(preset);
        globalTable.other = presetTable;
        presetTable.other = globalTable;

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Globales", globalTable.panel("Copiar al preset", true));
        tabs.setToolTipTextAt(0, globalsPersist
                ? "Se guardan en tus preferencias y aplican a todas las"
                        + " exportaciones y lotes de presets"
                : "Solo duran esta sesion (no hay almacen de preferencias)");
        tabs.addTab("Del preset", presetTable.panel("Copiar a globales",
                false));
        tabs.setToolTipTextAt(1, "Se guardan con 'Guardar preset...' y"
                + " sobrescriben a las globales del mismo nombre");

        JLabel help = new JLabel("<html>Usa <b>:NOMBRE</b> en el WHERE, el"
                + " ORDER BY o la consulta personalizada (p.ej."
                + " <tt>FECHA &gt;= :DESDE AND ESTADO = :ESTADO</tt>); se"
                + " enlazan como parametros con su tipo.<br>"
                + "Numeros con punto decimal. Fechas AAAA-MM-DD (fecha-hora:"
                + " AAAA-MM-DD HH:MM[:SS]) o relativas: "
                + QueryVariables.RELATIVE_HELP + ". Valor vacio = NULL.<br>"
                + "Las del preset sobrescriben a las globales con el mismo"
                + " nombre.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 4));
        help.setPreferredSize(new Dimension(640, 80));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton ok = new JButton("Aceptar");
        ok.setToolTipText("Valida y aplica las variables de las dos"
                + " pestanas (las globales se guardan en tus preferencias)");
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                accept();
            }
        });
        JButton cancel = new JButton("Cancelar");
        cancel.setToolTipText("Cierra sin aplicar ningun cambio (incluidas"
                + " las variables importadas)");
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        actions.add(ok);
        actions.add(cancel);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(help, BorderLayout.CENTER);
        south.add(actions, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(tabs, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(ok);
        pack();
        setMinimumSize(new Dimension(680, 420));
        setLocationRelativeTo(owner);
    }

    /**
     * Abre el dialogo y devuelve las variables editadas, o null si el
     * usuario cancelo.
     */
    static Result edit(Component parent, QueryVariables globals,
                       QueryVariables preset, boolean globalsPersist) {
        Window owner = parent instanceof Window ? (Window) parent
                : SwingUtilities.getWindowAncestor(parent);
        VariablesDialog dialog = new VariablesDialog(owner, globals, preset,
                globalsPersist);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void accept() {
        QueryVariables globals;
        QueryVariables preset;
        try {
            globals = globalTable.collect("Globales");
            preset = presetTable.collect("Del preset");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        result = new Result(globals, preset);
        dispose();
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(),
                JOptionPane.WARNING_MESSAGE);
    }

    /** Tabla editable de un ambito con sus botones. */
    private final class VariableTable {

        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Nombre", "Tipo", "Valor", "Descripcion",
                        "Valor hoy"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column != COL_PREVIEW;
            }
        };
        private final JTable table = new JTable(model) {
            @Override
            protected JTableHeader createDefaultTableHeader() {
                return new JTableHeader(columnModel) {
                    @Override
                    public String getToolTipText(MouseEvent e) {
                        int view = columnAtPoint(e.getPoint());
                        return view < 0 ? null : COLUMN_TIPS[
                                convertColumnIndexToModel(view)];
                    }
                };
            }
        };
        private VariableTable other;

        VariableTable(QueryVariables variables) {
            for (QueryVariables.Variable v : variables.getVariables()) {
                addRow(v.getName(), v.getType(), v.getValue(),
                        v.getDescription());
            }
            model.addTableModelListener(new TableModelListener() {
                public void tableChanged(TableModelEvent e) {
                    if (e.getColumn() == COL_PREVIEW
                            || e.getType() == TableModelEvent.DELETE) {
                        return;
                    }
                    int last = Math.min(e.getLastRow(),
                            model.getRowCount() - 1);
                    for (int row = Math.max(0, e.getFirstRow());
                         row <= last; row++) {
                        refreshPreview(row);
                    }
                }
            });
            for (int row = 0; row < model.getRowCount(); row++) {
                refreshPreview(row);
            }
            JTextField editorField = new JTextField();
            EditPopup.install(editorField, false);
            table.setDefaultEditor(Object.class,
                    new DefaultCellEditor(editorField));
            table.getColumnModel().getColumn(COL_TYPE).setCellEditor(
                    new DefaultCellEditor(new JComboBox<QueryVariables.Type>(
                            QueryVariables.Type.values())));
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            table.setSelectionMode(
                    ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setRowHeight(Math.max(table.getRowHeight(), 22));
            int[] widths = {110, 85, 150, 160, 130};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i)
                        .setPreferredWidth(widths[i]);
            }
        }

        JPanel panel(String copyLabel, boolean fileButtons) {
            JButton add = new JButton("Agregar");
            add.setToolTipText("Anade una fila vacia y empieza a editar su"
                    + " nombre");
            add.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    stopEditing();
                    addRow("", QueryVariables.Type.TEXT, "", "");
                    int row = model.getRowCount() - 1;
                    table.setRowSelectionInterval(row, row);
                    if (table.editCellAt(row, COL_NAME)
                            && table.getEditorComponent() != null) {
                        table.getEditorComponent().requestFocusInWindow();
                    }
                }
            });
            JButton remove = new JButton("Quitar");
            remove.setToolTipText("Elimina las filas seleccionadas");
            remove.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    stopEditing();
                    int[] rows = table.getSelectedRows();
                    for (int i = rows.length - 1; i >= 0; i--) {
                        model.removeRow(rows[i]);
                    }
                }
            });
            JButton copy = new JButton(copyLabel);
            copy.setToolTipText("Copia las filas seleccionadas al otro"
                    + " ambito (reemplaza las del mismo nombre)");
            copy.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    copySelectionToOther();
                }
            });
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.add(add);
            buttons.add(remove);
            buttons.add(copy);
            if (fileButtons) {
                JButton export = new JButton("Exportar...");
                export.setToolTipText("Guarda las variables de esta tabla en"
                        + " un archivo .json para llevarlas a otro equipo");
                export.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        exportFile();
                    }
                });
                JButton importButton = new JButton("Importar...");
                importButton.setToolTipText("Carga variables de un archivo"
                        + " .json (o de un preset): agrega las nuevas y"
                        + " reemplaza las del mismo nombre. Pulsa Aceptar"
                        + " para guardarlas");
                importButton.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        importFile();
                    }
                });
                buttons.add(export);
                buttons.add(importButton);
            }

            JPanel panel = new JPanel(new BorderLayout(0, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(640, 200));
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.SOUTH);
            return panel;
        }

        /**
         * Exporta las filas actuales (validadas, con lo editado y aun no
         * aceptado) a un archivo portable.
         */
        private void exportFile() {
            QueryVariables variables;
            try {
                variables = collect("Globales");
            } catch (IllegalArgumentException e) {
                warn(e.getMessage());
                return;
            }
            if (variables.isEmpty()) {
                warn("No hay variables que exportar.");
                return;
            }
            JFileChooser chooser = fileChooser("Exportar variables globales");
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(),
                    DEFAULT_FILE));
            if (chooser.showSaveDialog(VariablesDialog.this)
                    != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getParentFile(), file.getName() + ".json");
            }
            if (file.exists() && JOptionPane.showConfirmDialog(
                    VariablesDialog.this, "El archivo ya existe:\n" + file
                            + "\n\n¿Reemplazarlo?", getTitle(),
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                variables.saveFile(file);
                lastDirectory = file.getParentFile();
                JOptionPane.showMessageDialog(VariablesDialog.this,
                        "Se exportaron " + variables.getVariables().size()
                                + " variable(s) a:\n" + file, getTitle(),
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                warn("No se pudo exportar:\n" + e.getMessage());
            }
        }

        /**
         * Importa un archivo de variables a esta tabla: agrega las nuevas
         * y reemplaza las del mismo nombre. Solo se persiste al Aceptar.
         */
        private void importFile() {
            JFileChooser chooser = fileChooser("Importar variables globales");
            if (chooser.showOpenDialog(VariablesDialog.this)
                    != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            QueryVariables imported;
            try {
                imported = QueryVariables.loadFile(file);
            } catch (Exception e) {
                warn("No se pudo importar " + file.getName() + ":\n"
                        + e.getMessage());
                return;
            }
            lastDirectory = file.getParentFile();
            if (imported.isEmpty()) {
                warn("El archivo no contiene variables.");
                return;
            }
            stopEditing();
            int replaced = 0;
            for (QueryVariables.Variable v : imported.getVariables()) {
                for (int r = model.getRowCount() - 1; r >= 0; r--) {
                    if (v.getName().equalsIgnoreCase(text(r, COL_NAME))) {
                        model.removeRow(r);
                        replaced++;
                    }
                }
                addRow(v.getName(), v.getType(), v.getValue(),
                        v.getDescription());
                refreshPreview(model.getRowCount() - 1);
            }
            int total = imported.getVariables().size();
            JOptionPane.showMessageDialog(VariablesDialog.this,
                    "Se importaron " + total + " variable(s): "
                            + (total - replaced) + " nueva(s) y " + replaced
                            + " reemplazada(s).\n\nRevisa la tabla y pulsa"
                            + " Aceptar para guardarlas.", getTitle(),
                    JOptionPane.INFORMATION_MESSAGE);
        }

        private JFileChooser fileChooser(String title) {
            JFileChooser chooser = lastDirectory == null
                    ? new JFileChooser() : new JFileChooser(lastDirectory);
            chooser.setDialogTitle(title);
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Variables JSON (*.json)", "json"));
            return chooser;
        }

        private void addRow(String name, QueryVariables.Type type,
                            String value, String description) {
            model.addRow(new Object[]{name, type, value, description, ""});
        }

        private void refreshPreview(int row) {
            String value = rawValue(row);
            String preview = text(row, COL_NAME).isEmpty()
                    && value.trim().isEmpty()
                    ? "" : QueryVariables.preview(type(row), value,
                            LocalDateTime.now());
            if (!preview.equals(model.getValueAt(row, COL_PREVIEW))) {
                model.setValueAt(preview, row, COL_PREVIEW);
            }
        }

        private void copySelectionToOther() {
            stopEditing();
            other.stopEditing();
            for (int row : table.getSelectedRows()) {
                String name = text(row, COL_NAME).toUpperCase();
                if (name.isEmpty()) {
                    continue;
                }
                for (int r = other.model.getRowCount() - 1; r >= 0; r--) {
                    if (name.equalsIgnoreCase(other.text(r, COL_NAME))) {
                        other.model.removeRow(r);
                    }
                }
                other.addRow(name, type(row), rawValue(row),
                        text(row, COL_DESCRIPTION));
            }
        }

        /**
         * Variables validadas de la tabla; las filas totalmente vacias se
         * ignoran.
         *
         * @throws IllegalArgumentException con el problema de la primera
         *         fila invalida
         */
        QueryVariables collect(String scope) {
            stopEditing();
            List<QueryVariables.Variable> list =
                    new ArrayList<QueryVariables.Variable>();
            Set<String> seen = new HashSet<String>();
            LocalDateTime now = LocalDateTime.now();
            for (int row = 0; row < model.getRowCount(); row++) {
                String name = text(row, COL_NAME);
                String value = rawValue(row);
                String description = text(row, COL_DESCRIPTION);
                if (name.isEmpty() && text(row, COL_VALUE).isEmpty()
                        && description.isEmpty()) {
                    continue;
                }
                String where = scope + ", fila " + (row + 1) + ": ";
                if (!QueryVariables.isValidName(name)) {
                    throw new IllegalArgumentException(where + "el nombre '"
                            + name + "' no es valido (letra inicial y luego"
                            + " letras, digitos o _).");
                }
                if (!seen.add(name.toUpperCase())) {
                    throw new IllegalArgumentException(where + "la variable "
                            + name.toUpperCase() + " esta repetida.");
                }
                try {
                    QueryVariables.resolve(type(row), value, now);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + ":"
                            + name.toUpperCase() + " " + e.getMessage());
                }
                list.add(new QueryVariables.Variable(name, type(row), value,
                        description));
            }
            return list.isEmpty() ? QueryVariables.EMPTY
                    : new QueryVariables(list);
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private String text(int row, int column) {
            return rawText(row, column).trim();
        }

        /** Valor sin recortar: en las de texto los espacios cuentan. */
        private String rawValue(int row) {
            return rawText(row, COL_VALUE);
        }

        private String rawText(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value == null ? "" : value.toString();
        }

        private QueryVariables.Type type(int row) {
            Object value = model.getValueAt(row, COL_TYPE);
            return value instanceof QueryVariables.Type
                    ? (QueryVariables.Type) value : QueryVariables.Type.TEXT;
        }
    }
}
