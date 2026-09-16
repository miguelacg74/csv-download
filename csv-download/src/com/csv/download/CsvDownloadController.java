package com.csv.download;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * navegador de conexiones (usa la conexion del nodo seleccionado).
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
        CsvExportDialog.Request request =
                CsvExportDialog.ask(Ide.getMainWindow(), connectionName);
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
        final JLabel status = new JLabel(" ");
        final JDialog progress = progressDialog(status, cancelled);

        SwingWorker<List<CsvExporter.TableResult>, String> worker =
                new SwingWorker<List<CsvExporter.TableResult>, String>() {
            private List<CsvExporter.TableResult> results;
            private String error;

            @Override
            protected List<CsvExporter.TableResult> doInBackground() {
                Connection connection = connectionOf(dbObject, worksheet);
                if (connection == null) {
                    error = "No hay conexion disponible.\nAbre una hoja de trabajo "
                            + "con conexion o usa el menu contextual sobre una "
                            + "conexion del navegador.";
                    return null;
                }
                CsvExporter.Progress progress = new CsvExporter.Progress() {
                    public boolean onProgress(String table, int index,
                                              int total, long rows) {
                        publish(table + "  (" + index + "/" + total + ")"
                                + (rows > 0 ? "  " + rows + " filas" : ""));
                        return !cancelled.get();
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
                showSummary(results, request.getDirectory(), cancelled.get());
            }
        };
        worker.execute();
        progress.setVisible(true);
    }

    private static JDialog progressDialog(JLabel status,
                                          final AtomicBoolean cancelled) {
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
