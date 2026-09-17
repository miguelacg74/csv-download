package com.csv.download;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import oracle.dbtools.raptor.utils.Connections;
import oracle.dbtools.raptor.utils.DBObject;
import oracle.dbtools.worksheet.editor.Worksheet;
import oracle.ide.Context;
import oracle.ide.Ide;
import oracle.ide.controller.Controller;
import oracle.ide.controller.IdeAction;
import oracle.ide.model.Element;

/**
 * Accion "Descargar tablas a CSV": pide un listado de tablas y una carpeta
 * de salida, y exporta cada tabla a un CSV con {@link CsvExporter}.
 *
 * Se puede invocar desde el menu Tools, el menu contextual del editor (usa
 * la conexion de la hoja de trabajo activa) o el menu contextual del
 * navegador de conexiones (usa la conexion del nodo seleccionado y
 * pre-rellena el listado con las tablas/vistas de la seleccion).
 * Desde el dialogo tambien se puede lanzar un lote de presets (*.json)
 * de una carpeta ({@link PresetBatch}) sobre la misma conexion.
 *
 * La exportacion se hace en segundo plano con un dialogo de progreso
 * cancelable para no congelar el IDE.
 */
public class CsvDownloadController implements Controller {

    private static final String TITLE = "Descargar tablas a CSV";

    @Override
    public boolean handleEvent(IdeAction action, Context context) {
        final DBObject dbObject = dbObjectOf(context);
        final Worksheet worksheet = worksheetOf(context);

        String connectionName = dbObject == null ? null : dbObject.getConnectionName();
        String initialTables = joinComma(selectedObjectNames(context));
        CsvExportDialog.Request request = CsvExportDialog.ask(
                Ide.getMainWindow(), connectionName, initialTables);
        if (request == null) {
            return true;
        }

        exportInBackground(request, dbObject, worksheet);
        return true;
    }

    @Override
    public boolean update(IdeAction action, Context context) {
        action.setEnabled(true);
        return true;
    }

    // ------------------------------------------------------------ exportacion

    /**
     * Resuelve la conexion en segundo plano (abrirla puede pedir credenciales)
     * y ejecuta el lote completo; el progreso se muestra en un dialogo modal
     * con boton de cancelar.
     */
    private void exportInBackground(final CsvExportDialog.Request request,
                                    final DBObject dbObject,
                                    final Worksheet worksheet) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<Statement> running =
                new AtomicReference<Statement>();
        final JLabel status = new JLabel(" ");
        final JDialog progress = progressDialog(status, cancelled, running);

        SwingWorker<Object, String> worker =
                new SwingWorker<Object, String>() {
            private List<CsvExporter.TableResult> results;
            private List<PresetBatch.PresetResult> presetResults;
            private String error;

            @Override
            protected Object doInBackground() {
                Connection connection = connectionOf(dbObject, worksheet);
                if (connection == null) {
                    error = "No hay conexion disponible.\nAbre una hoja de trabajo "
                            + "con conexion o usa el menu contextual sobre una "
                            + "conexion del navegador.";
                    return null;
                }
                if (request.isPresetBatch()) {
                    PresetBatch.Progress batchProgress =
                            new PresetBatch.Progress() {
                        public boolean onProgress(String presetFile,
                                                  int presetIndex,
                                                  int presetTotal,
                                                  String table, int index,
                                                  int total, long rows) {
                            String message = "preset " + presetIndex + "/"
                                    + presetTotal + " " + presetFile;
                            if (table != null && !table.isEmpty()) {
                                message += "  ::  " + table + "  (" + index
                                        + "/" + total + ")"
                                        + (rows > 0 ? "  " + rows + " filas"
                                                : "");
                            }
                            publish(message);
                            return !cancelled.get();
                        }

                        public void onStatement(Statement statement) {
                            running.set(statement);
                        }
                    };
                    presetResults = PresetBatch.run(connection,
                            request.getPresetFiles(), batchProgress);
                    return presetResults;
                }
                CsvExporter.Progress progress = new CsvExporter.Progress() {
                    public boolean onProgress(String table, int index,
                                              int total, long rows) {
                        publish(table + "  (" + index + "/" + total + ")"
                                + (rows > 0 ? "  " + rows + " filas" : ""));
                        return !cancelled.get();
                    }

                    public void onStatement(Statement statement) {
                        running.set(statement);
                    }
                };
                results = request.isUseCustomQuery()
                        ? CsvExporter.exportQuery(connection,
                                request.getCustomQuery(), request.getMaxRows(),
                                request.getDirectory(),
                                request.getCustomFileName(), progress)
                        : CsvExporter.export(connection, request.getTables(),
                                request.getWhere(), request.getMaxRows(),
                                request.getDirectory(), progress);
                return results;
            }

            @Override
            protected void process(List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progress.dispose();
                if (error != null) {
                    JOptionPane.showMessageDialog(Ide.getMainWindow(), error,
                            TITLE, JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (presetResults != null) {
                    showPresetSummary(presetResults, cancelled.get());
                } else {
                    showSummary(results, request.getDirectory(),
                            cancelled.get());
                }
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private static JDialog progressDialog(JLabel status,
                                          final AtomicBoolean cancelled,
                                          final AtomicReference<Statement> running) {
        Frame owner = Ide.getMainWindow();
        final JDialog progress = new JDialog(owner, TITLE, true);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        panel.add(new JLabel("Exportando tablas a CSV..."), BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        panel.add(status, BorderLayout.CENTER);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.SOUTH);
        JButton cancel = new JButton("Cancelar");
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                cancelled.set(true);
                // interrumpe la consulta en curso (ORA-01013), si la hay:
                // el callback de progreso solo se consulta entre tablas o
                // cada 200 filas, esto corta tambien un executeQuery largo
                Statement st = running.get();
                if (st != null) {
                    try {
                        st.cancel();
                    } catch (Throwable ignored) {
                        // el worker seguira cortando al ver cancelled=true
                    }
                }
                progress.dispose();
            }
        });
        JPanel south = new JPanel(new BorderLayout());
        south.add(cancel, BorderLayout.EAST);
        panel.add(south, BorderLayout.SOUTH);
        progress.setContentPane(panel);
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progress.pack();
        progress.setLocationRelativeTo(owner);
        return progress;
    }

    /** Resumen por tabla con opcion de abrir la carpeta de salida. */
    private static void showSummary(List<CsvExporter.TableResult> results,
                                    final File dir, boolean cancelled) {
        StringBuilder text = new StringBuilder();
        int ok = 0;
        int failed = 0;
        if (results == null) {
            results = Collections.emptyList();
        }
        for (CsvExporter.TableResult r : results) {
            if (r.isOk()) {
                ok++;
                text.append("[OK]   ").append(r.getTable())
                        .append(" -> ").append(r.getFileName())
                        .append(" (").append(r.getRows()).append(" filas")
                        .append(r.isTruncated() ? ", truncada" : "")
                        .append(")\n");
            } else {
                failed++;
                text.append("[ERROR] ").append(r.getTable())
                        .append(" -> ").append(r.getError()).append('\n');
            }
            for (String warning : r.getWarnings()) {
                text.append("        aviso: ").append(warning).append('\n');
            }
        }
        String head = "Se generaron " + ok + " CSV en\n" + dir + "\n\n";
        if (cancelled) {
            head = "Exportacion cancelada. " + head;
        }
        if (failed > 0) {
            head += failed + " tabla(s) con error.\n\n";
        }

        JTextArea area = new JTextArea(head + text.toString(), 16, 60);
        area.setEditable(false);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);

        if (ok == 0) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(), scroll,
                    TITLE, failed > 0 ? JOptionPane.ERROR_MESSAGE
                            : JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showOptionDialog(Ide.getMainWindow(), scroll,
                TITLE, JOptionPane.YES_NO_OPTION,
                failed > 0 ? JOptionPane.WARNING_MESSAGE
                        : JOptionPane.INFORMATION_MESSAGE,
                null, new String[]{"Abrir carpeta", "Cerrar"}, "Cerrar");
        if (choice == JOptionPane.YES_OPTION) {
            openFolder(dir);
        }
    }

    /**
     * Resumen del lote de presets: un bloque por archivo de preset con su
     * carpeta de salida y el resultado de cada tabla/consulta. Ofrece abrir
     * la carpeta solo si todos los CSV cayeron en la misma.
     */
    private static void showPresetSummary(
            List<PresetBatch.PresetResult> results, boolean cancelled) {
        StringBuilder text = new StringBuilder();
        int presetsRun = 0;
        int presetsFailed = 0;
        int csvOk = 0;
        int csvFailed = 0;
        Set<File> outDirs = new LinkedHashSet<File>();
        if (results == null) {
            results = Collections.emptyList();
        }
        for (PresetBatch.PresetResult preset : results) {
            text.append(preset.getFileName());
            if (!preset.isOk()) {
                presetsFailed++;
                text.append(" -> [ERROR] ").append(preset.getError())
                        .append("\n\n");
                continue;
            }
            presetsRun++;
            text.append(" -> ").append(preset.getOutputDir()).append('\n');
            for (CsvExporter.TableResult r : preset.getTables()) {
                if (r.isOk()) {
                    csvOk++;
                    outDirs.add(preset.getOutputDir());
                    text.append("   [OK]   ").append(r.getTable())
                            .append(" -> ").append(r.getFileName())
                            .append(" (").append(r.getRows())
                            .append(" filas")
                            .append(r.isTruncated() ? ", truncada" : "")
                            .append(")\n");
                } else {
                    csvFailed++;
                    text.append("   [ERROR] ").append(r.getTable())
                            .append(" -> ").append(r.getError()).append('\n');
                }
                for (String warning : r.getWarnings()) {
                    text.append("           aviso: ").append(warning)
                            .append('\n');
                }
            }
            text.append('\n');
        }
        int totalErrors = presetsFailed + csvFailed;
        String head = "Se ejecutaron " + presetsRun + " de " + results.size()
                + " presets: " + csvOk + " CSV generados"
                + (totalErrors > 0 ? ", " + totalErrors + " con error" : "")
                + ".\n\n";
        if (cancelled) {
            head = "Lote cancelado. " + head;
        }

        JTextArea area = new JTextArea(head + text.toString(), 18, 60);
        area.setEditable(false);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        int icon = totalErrors > 0 ? JOptionPane.WARNING_MESSAGE
                : JOptionPane.INFORMATION_MESSAGE;

        if (outDirs.size() != 1) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(), scroll,
                    TITLE, csvOk == 0 && totalErrors > 0
                            ? JOptionPane.ERROR_MESSAGE : icon);
            return;
        }
        int choice = JOptionPane.showOptionDialog(Ide.getMainWindow(), scroll,
                TITLE, JOptionPane.YES_NO_OPTION, icon, null,
                new String[]{"Abrir carpeta", "Cerrar"}, "Cerrar");
        if (choice == JOptionPane.YES_OPTION) {
            openFolder(outDirs.iterator().next());
        }
    }

    private static void openFolder(File dir) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Throwable e) {
            JOptionPane.showMessageDialog(Ide.getMainWindow(),
                    "No se pudo abrir la carpeta:\n" + e.getMessage(),
                    TITLE, JOptionPane.WARNING_MESSAGE);
        }
    }

    // ---------------------------------------------------------- conexiones

    /** Conexion del contexto: primero el nodo del navegador, luego la hoja. */
    private static Connection connectionOf(DBObject dbObject, Worksheet worksheet) {
        Connection connection = connectionOf(dbObject);
        if (connection == null) {
            connection = connectionOf(worksheet);
        }
        return connection;
    }

    /**
     * Nombres de las tablas/vistas de la seleccion del navegador
     * (multiseleccion via {@link Context#getSelection()}), calificados con
     * el esquema cuando se conoce. Lista vacia si la seleccion no contiene
     * objetos exportables.
     */
    private static List<String> selectedObjectNames(Context context) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        try {
            Element[] selection = context == null
                    ? null : context.getSelection();
            if (selection == null || selection.length == 0) {
                Element single = context == null
                        ? null : context.getElement();
                selection = single == null ? new Element[0]
                        : new Element[]{single};
            }
            for (Element element : selection) {
                addObjectName(element, names);
            }
        } catch (Throwable ignored) {
            names.clear();
        }
        return new ArrayList<String>(names);
    }

    /** Anade el nombre del objeto a {@code names} si es tabla o vista. */
    private static void addObjectName(Element element, Set<String> names) {
        if (element == null) {
            return;
        }
        try {
            DBObject obj = new DBObject(element);
            String type = obj.getObjectType();
            String name = obj.getObjectName();
            if (name == null || name.trim().isEmpty()) {
                return;
            }
            if (!"TABLE".equalsIgnoreCase(type)
                    && !"VIEW".equalsIgnoreCase(type)) {
                return;
            }
            String schema = obj.getSchemaName();
            names.add(schema == null || schema.trim().isEmpty()
                    ? name : schema + "." + name);
        } catch (Throwable ignored) {
            // nodo que no representa un objeto exportable: se ignora
        }
    }

    private static String joinComma(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** Objeto del navegador del contexto, o null si no lo hay. */
    private static DBObject dbObjectOf(Context context) {
        try {
            Element element = context == null ? null : context.getElement();
            if (element == null) {
                return null;
            }
            DBObject obj = new DBObject(element);
            return obj.getConnectionName() == null ? null : obj;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Conexion del nodo del navegador; si no esta abierta se intenta abrir. */
    private static Connection connectionOf(DBObject dbObject) {
        if (dbObject == null) {
            return null;
        }
        try {
            Connection connection = dbObject.getConnection();
            if (connection != null && !connection.isClosed()) {
                return connection;
            }
        } catch (Throwable ignored) {
            // se intenta por nombre de conexion
        }
        String name = dbObject.getConnectionName();
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return Connections.getInstance().getConnection(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Worksheet worksheetOf(Context context) {
        try {
            return context == null ? null
                    : Worksheet.getWorksheetFromContext(context);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Connection connectionOf(Worksheet worksheet) {
        if (worksheet == null) {
            return null;
        }
        try {
            return worksheet.getConnection();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
