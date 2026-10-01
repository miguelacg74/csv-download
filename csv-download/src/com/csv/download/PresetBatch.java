package com.csv.download;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ejecuta en lote todos los presets de exportacion ({@code *.json}) de una
 * carpeta sobre una misma conexion: cada preset se carga con
 * {@link ExportPreset#load(File)} y se ejecuta segun su modo (listado de
 * tablas o consulta personalizada), volcando a la carpeta de salida guardada
 * en el propio preset (se crea si no existe).
 *
 * Un preset que no se puede leer, validar o ejecutar no detiene el lote:
 * queda registrado con su error y se sigue con el siguiente.
 *
 * Java puro (JDBC + io + colecciones): no usa APIs del IDE, asi que se puede
 * probar fuera de SQL Developer con SelfTest.
 */
public final class PresetBatch {

    /**
     * Callback de progreso y cancelacion a nivel de preset y de tabla:
     * {@code presetFile}/{@code presetIndex}/{@code presetTotal} identifican
     * el preset en curso y el resto de parametros son los de
     * {@link CsvExporter.Progress}. Devolver {@code false} cancela todo el
     * lote (la tabla en curso se descarta y no se abren mas presets).
     */
    public interface Progress {
        boolean onProgress(String presetFile, int presetIndex, int presetTotal,
                           String table, int index, int total, long rows);

        /**
         * Reenvio de {@link CsvExporter.Progress#onStatement(Statement)}:
         * permite cancelar la consulta en curso con {@code st.cancel()}.
         */
        default void onStatement(Statement statement) {
        }

        /**
         * Reenvio de {@link CsvExporter.Progress#onTotalRows(String, long)}:
         * estimacion de filas de la tabla en curso (-1 = desconocida).
         */
        default void onTotalRows(String presetFile, int presetIndex,
                                 int presetTotal, String table,
                                 long estimatedRows) {
        }
    }

    /** Resultado de ejecutar un preset del lote. */
    public static final class PresetResult {

        private final String fileName;
        private final File outputDir;
        private final String error;
        private final List<CsvExporter.TableResult> tables;

        private PresetResult(String fileName, File outputDir, String error,
                             List<CsvExporter.TableResult> tables) {
            this.fileName = fileName;
            this.outputDir = outputDir;
            this.error = error;
            this.tables = tables;
        }

        /** Nombre del archivo de preset (p.ej. "ventas.json"). */
        public String getFileName() {
            return fileName;
        }

        /**
         * Carpeta de salida indicada por el preset; null si el preset no
         * llego a ejecutarse (error de lectura o validacion).
         */
        public File getOutputDir() {
            return outputDir;
        }

        /**
         * Error de lectura o validacion del preset; null si se ejecuto
         * (los errores por tabla quedan en {@link #getTables()}).
         */
        public String getError() {
            return error;
        }

        /** Un TableResult por tabla/consulta del preset; vacio si fallo antes. */
        public List<CsvExporter.TableResult> getTables() {
            return tables;
        }

        /** True si el preset se leyo y se ejecuto (aunque alguna tabla falle). */
        public boolean isOk() {
            return error == null;
        }
    }

    private PresetBatch() {
    }

    /**
     * Archivos {@code *.json} de la carpeta (no recursivo), ordenados por
     * nombre para un orden de ejecucion determinista.
     */
    public static List<File> listPresets(File dir) {
        List<File> presets = new ArrayList<File>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()
                        && file.getName().toLowerCase().endsWith(".json")) {
                    presets.add(file);
                }
            }
        }
        presets.sort(new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return presets;
    }

    /**
     * Ejecuta los presets indicados, en orden, sobre la misma conexion.
     * La lista de resultados se corta si el usuario cancela via
     * {@link Progress}.
     *
     * @param skipExisting true = omitir los CSV existentes en TODO el lote
     *                     (se suma al "no reemplazar" propio de cada preset:
     *                     basta con que uno de los dos lo pida)
     */
    public static List<PresetResult> run(Connection connection,
                                         List<File> presetFiles,
                                         boolean skipExisting,
                                         Progress progress) {
        List<PresetResult> results = new ArrayList<PresetResult>();
        boolean[] cancelled = {false};
        int total = presetFiles.size();
        for (int i = 0; i < total; i++) {
            PresetResult result = runOne(connection, presetFiles.get(i),
                    i + 1, total, skipExisting, progress, cancelled);
            if (result != null) {
                results.add(result);
            }
            if (cancelled[0]) {
                break;
            }
        }
        return results;
    }

    private static PresetResult runOne(Connection connection, File file,
                                       int presetIndex, int presetTotal,
                                       boolean batchSkip,
                                       Progress progress,
                                       boolean[] cancelled) {
        List<CsvExporter.TableResult> none =
                new ArrayList<CsvExporter.TableResult>();
        if (progress != null
                && !progress.onProgress(file.getName(), presetIndex,
                        presetTotal, "", 0, 0, 0)) {
            cancelled[0] = true;
            return null;
        }
        ExportPreset preset;
        try {
            preset = ExportPreset.load(file);
        } catch (Exception e) {
            String message = e.getMessage();
            return new PresetResult(file.getName(), null,
                    "No se pudo leer el preset: "
                            + (message == null ? e.toString() : message),
                    none);
        }

        String dirText = preset.getDirectory().trim();
        if (dirText.isEmpty()) {
            return new PresetResult(file.getName(), null,
                    "El preset no indica carpeta de salida.", none);
        }
        File dir = new File(dirText);
        if (!dir.exists() && !dir.mkdirs()) {
            return new PresetResult(file.getName(), null,
                    "No se pudo crear la carpeta de salida: " + dir, none);
        }
        if (!dir.isDirectory()) {
            return new PresetResult(file.getName(), null,
                    "La ruta de salida no es una carpeta: " + dir, none);
        }

        List<String> tables = preset.isUseCustomQuery()
                ? null : CsvExporter.parseTableList(preset.getTables());
        if (!preset.isUseCustomQuery() && tables.isEmpty()) {
            return new PresetResult(file.getName(), dir,
                    "El preset no tiene tablas ni consulta personalizada.",
                    none);
        }
        try {
            if (!connection.isValid(5)) {
                return new PresetResult(file.getName(), dir,
                        "La conexion se perdio o no responde.", none);
            }
        } catch (Throwable e) {
            return new PresetResult(file.getName(), dir,
                    "La conexion se perdio o no responde: " + e, none);
        }

        CsvExporter.Progress inner = null;
        if (progress != null) {
            final String presetName = file.getName();
            inner = new CsvExporter.Progress() {
                public boolean onProgress(String table, int index,
                                          int total, long rows) {
                    boolean go = progress.onProgress(presetName, presetIndex,
                            presetTotal, table, index, total, rows);
                    if (!go) {
                        cancelled[0] = true;
                    }
                    return go;
                }

                public void onStatement(Statement statement) {
                    progress.onStatement(statement);
                }

                public void onPart(String table, int index, int total,
                                   int part, String fileName) {
                    progress.onProgress(presetName, presetIndex, presetTotal,
                            table + " -> " + fileName, index, total, 0);
                }

                public void onTotalRows(String table, long estimatedRows) {
                    progress.onTotalRows(presetName, presetIndex,
                            presetTotal, table, estimatedRows);
                }
            };
        }

        boolean skipExisting = batchSkip || preset.isSkipExisting();
        if (preset.isUseCustomQuery()) {
            return new PresetResult(file.getName(), dir, null,
                    CsvExporter.exportQuery(connection, preset.getCustomQuery(),
                            preset.getMaxRows(), preset.getRowsPerFile(), dir,
                            preset.getCustomFileName(), skipExisting,
                            preset.isResume(), inner));
        }
        return new PresetResult(file.getName(), dir, null,
                CsvExporter.export(connection, tables, preset.getWhere(),
                        preset.getOrderBy(), preset.getMaxRows(),
                        preset.getRowsPerFile(), dir, skipExisting,
                        preset.isResume(), inner));
    }
}
