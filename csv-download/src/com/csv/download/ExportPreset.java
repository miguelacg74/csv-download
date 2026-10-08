package com.csv.download;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preset de la descarga: el estado del dialogo (listado de tablas, carpeta,
 * WHERE, ORDER BY, limite de filas, filas por archivo, reanudar, consulta
 * personalizada y variables propias del preset) guardado como JSON en un
 * archivo
 * {@code *.json} para recargarlo despues.
 *
 * El JSON se escribe y se lee con {@link JsonUtil} (no hay libreria JSON en
 * el proyecto), en UTF-8 y con campo {@code version}; el parser es tolerante
 * a claves desconocidas para poder leer presets de versiones futuras.
 */
public final class ExportPreset {

    private static final int VERSION = 3;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private String tables = "";
    private String directory = "";
    private String where = "";
    private String orderBy = "";
    private int maxRows;
    private int rowsPerFile;
    private boolean skipExisting;
    private boolean resume;
    private boolean useCustomQuery;
    private String customQuery = "";
    private String customFileName = "";
    private QueryVariables variables = QueryVariables.EMPTY;

    public ExportPreset() {
    }

    /**
     * Variables propias del preset: al ejecutarlo sobrescriben a las
     * globales del mismo nombre ({@link QueryVariables#overriddenBy}).
     */
    public QueryVariables getVariables() {
        return variables;
    }

    public void setVariables(QueryVariables variables) {
        this.variables = variables == null ? QueryVariables.EMPTY : variables;
    }

    public String getTables() {
        return tables;
    }

    public void setTables(String tables) {
        this.tables = tables == null ? "" : tables;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory == null ? "" : directory;
    }

    public String getWhere() {
        return where;
    }

    public void setWhere(String where) {
        this.where = where == null ? "" : where;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy == null ? "" : orderBy;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getRowsPerFile() {
        return rowsPerFile;
    }

    public void setRowsPerFile(int rowsPerFile) {
        this.rowsPerFile = rowsPerFile;
    }

    public boolean isSkipExisting() {
        return skipExisting;
    }

    public void setSkipExisting(boolean skipExisting) {
        this.skipExisting = skipExisting;
    }

    public boolean isResume() {
        return resume;
    }

    public void setResume(boolean resume) {
        this.resume = resume;
    }

    public boolean isUseCustomQuery() {
        return useCustomQuery;
    }

    public void setUseCustomQuery(boolean useCustomQuery) {
        this.useCustomQuery = useCustomQuery;
    }

    public String getCustomQuery() {
        return customQuery;
    }

    public void setCustomQuery(String customQuery) {
        this.customQuery = customQuery == null ? "" : customQuery;
    }

    public String getCustomFileName() {
        return customFileName;
    }

    public void setCustomFileName(String customFileName) {
        this.customFileName = customFileName == null ? "" : customFileName;
    }

    /** Estado del preset como JSON en una sola linea. */
    public String toJson() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("version", VERSION);
        values.put("tables", tables);
        values.put("directory", directory);
        values.put("where", where);
        values.put("orderBy", orderBy);
        values.put("maxRows", maxRows);
        values.put("rowsPerFile", rowsPerFile);
        values.put("skipExisting", skipExisting);
        values.put("resume", resume);
        values.put("useCustomQuery", useCustomQuery);
        values.put("customQuery", customQuery);
        values.put("customFileName", customFileName);
        values.put("variables", new JsonUtil.Raw(variables.toJson()));
        return JsonUtil.objectToJson(values);
    }

    /**
     * Reconstruye un preset desde su JSON. Las claves desconocidas se
     * ignoran y las que faltan quedan con su valor por defecto.
     */
    public static ExportPreset fromJson(String json) {
        Map<String, String> values = JsonUtil.parseObject(json);
        ExportPreset preset = new ExportPreset();
        preset.tables = value(values, "tables", "");
        preset.directory = value(values, "directory", "");
        preset.where = value(values, "where", "");
        preset.orderBy = value(values, "orderBy", "");
        preset.maxRows = intValue(values.get("maxRows"), 0);
        preset.rowsPerFile = intValue(values.get("rowsPerFile"), 0);
        preset.skipExisting = "true".equals(values.get("skipExisting"));
        preset.resume = "true".equals(values.get("resume"));
        preset.useCustomQuery = "true".equals(values.get("useCustomQuery"));
        preset.customQuery = value(values, "customQuery", "");
        preset.customFileName = value(values, "customFileName", "");
        preset.variables = QueryVariables.fromJson(
                JsonUtil.parseObjectRaw(json).get("variables"));
        return preset;
    }

    /** Guarda el preset en disco (UTF-8). */
    public void save(File file) throws IOException {
        Files.write(file.toPath(), toJson().getBytes(UTF8));
    }

    /** Carga un preset de disco. */
    public static ExportPreset load(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return fromJson(new String(bytes, UTF8));
    }

    private static String value(Map<String, String> values, String key,
                                String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    private static int intValue(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
