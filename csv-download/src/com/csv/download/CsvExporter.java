package com.csv.download;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exporta los datos de una lista de tablas a archivos CSV individuales
 * (un archivo por tabla) usando solo JDBC: nada de esta clase depende de
 * las APIs de SQL Developer, asi que se puede probar fuera del IDE.
 *
 * Formato fijo del CSV:
 *  - separador de columnas ';'
 *  - primera linea con los nombres de columna de la tabla
 *  - valores que contienen ';', '"' o saltos de linea entrecomillados con '"'
 *    (las comillas internas se duplican)
 *  - NULL como celda vacia
 *  - numeros con punto decimal y fechas en ISO (tal cual los devuelve JDBC)
 *  - codificacion windows-1252
 */
public final class CsvExporter {

    /** Separador de columnas del CSV. */
    public static final char SEPARATOR = ';';

    /** Codificacion de los archivos generados. */
    public static final Charset CHARSET = Charset.forName("windows-1252");

    private static final int FETCH_SIZE = 500;
    private static final int PROGRESS_EVERY_ROWS = 200;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Codigos de tipo propios de Oracle no cubiertos por java.sql.Types. */
    private static final int ORA_TIMESTAMP_TZ = -101;
    private static final int ORA_TIMESTAMP_LTZ = -102;

    private CsvExporter() {
    }

    /**
     * Callback de progreso y cancelacion: se invoca al empezar cada tabla y
     * cada {@link #PROGRESS_EVERY_ROWS} filas. Devolver {@code false} cancela
     * la exportacion (la tabla en curso se descarta).
     */
    public interface Progress {
        boolean onProgress(String table, int index, int total, long rows);
    }

    /** Resultado de la exportacion de una tabla. */
    public static final class TableResult {

        private final String table;
        private final String fileName;
        private final long rows;
        private final boolean truncated;
        private final String error;
        private final List<String> warnings;

        private TableResult(String table, String fileName, long rows,
                            boolean truncated, String error, List<String> warnings) {
            this.table = table;
            this.fileName = fileName;
            this.rows = rows;
            this.truncated = truncated;
            this.error = error;
            this.warnings = warnings;
        }

        public String getTable() {
            return table;
        }

        /** Nombre del CSV generado; null si la tabla fallo o se cancelo. */
        public String getFileName() {
            return fileName;
        }

        public long getRows() {
            return rows;
        }

        /** True si se alcanzo el limite de filas y la tabla tiene mas datos. */
        public boolean isTruncated() {
            return truncated;
        }

        /** Mensaje de error; null si la tabla se exporto bien. */
        public String getError() {
            return error;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean isOk() {
            return error == null && fileName != null;
        }
    }

    /**
     * Trocea el listado del usuario en nombres de tabla individuales. Se
     * admiten comas, punto y coma y saltos de linea como separadores, y cada
     * nombre puede ir cualificado como ESQUEMA.TABLA (con o sin comillas).
     * Devuelve los nombres sin duplicados ni entradas vacias.
     */
    public static List<String> parseTableList(String raw) {
        List<String> tables = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String token : raw.split("[,;\\r\\n]+")) {
            String name = token.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (seen.add(name.toUpperCase())) {
                tables.add(name);
            }
        }
        return tables;
    }

    /**
     * Normaliza un nombre de tabla del listado a un identificador listo para
     * SQL: cada parte separada por '.' (fuera de comillas) se pasa por
     * {@link #quoteIfNeeded(String)}, respetando el texto del usuario
     * (Oracle resuelve los identificadores sin comillas en mayusculas).
     * "HR.EMP" -> HR.EMP; "miTabla" -> "miTabla"; 'HR."Mi Tabla"' se conserva.
     */
    public static String qualifiedName(String token) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == '.' && !quoted) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());

        StringBuilder qualified = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (i > 0) {
                qualified.append('.');
            }
            qualified.append(part.startsWith("\"") ? part : quoteIfNeeded(part));
        }
        return qualified.toString();
    }

    /**
     * Nombre de archivo CSV para una tabla del listado o para la consulta
     * personalizada: el texto sin comillas con los caracteres ilegales en
     * Windows sustituidos por '_' y la extension .csv garantizada.
     * "HR.EMP" -> "HR.EMP.csv"; "salida.csv" -> "salida.csv".
     */
    public static String fileNameFor(String token) {
        String name = token.replace("\"", "").trim();
        name = name.replaceAll("[\\\\/:*?<>|]", "_");
        if (name.isEmpty()) {
            name = "tabla";
        }
        if (!name.toLowerCase().endsWith(".csv")) {
            name += ".csv";
        }
        return name;
    }

    /**
     * Devuelve el identificador tal cual si es un nombre Oracle normal
     * (mayusculas, digitos, _, $, #); en caso contrario lo entrecomilla para
     * preservar mayusculas/minusculas y caracteres especiales.
     */
    public static String quoteIfNeeded(String identifier) {
        if (identifier != null && identifier.matches("[A-Z0-9_$#]+")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * Exporta cada tabla del listado a {@code <tabla>.csv} en el directorio
     * indicado. Una tabla que falla no detiene el lote: queda registrada con
     * su error en el resultado y se sigue con la siguiente.
     *
     * @param tables   nombres tal cual los escribio el usuario (ya troceados
     *                 con {@link #parseTableList(String)})
     * @param where    condicion opcional aplicada a todas las tablas
     *                 (sin la palabra WHERE); vacia o null = tabla completa
     * @param maxRows  maximo de filas por tabla; &lt;= 0 = sin limite
     * @param dir      carpeta de salida (debe existir)
     * @param progress callback de progreso/cancelacion; null = sin callback
     * @return un TableResult por tabla, en el orden del listado; la lista se
     *         corta si el usuario cancela
     */
    public static List<TableResult> export(Connection connection,
                                           List<String> tables, String where,
                                           int maxRows, File dir,
                                           Progress progress) {
        List<TableResult> results = new ArrayList<TableResult>();
        String condition = where == null ? "" : where.trim();
        int index = 0;
        for (String token : tables) {
            index++;
            if (progress != null
                    && !progress.onProgress(token, index, tables.size(), 0)) {
                break;
            }
            String qualified = qualifiedName(token);
            results.add(exportTable(connection, token, qualified, condition,
                    maxRows, dir, index, tables.size(), progress));
        }
        return results;
    }

    /**
     * Ejecuta una consulta personalizada (SELECT o WITH ... SELECT) y vuelca
     * su resultado a un unico CSV. Pensada para el input puntual del dialogo:
     * no usa el listado de tablas ni el WHERE libre, pero si el limite de
     * filas (maxRows &lt;= 0 = sin limite).
     *
     * @param fileName nombre del CSV (se sanitiza con {@link #fileNameFor});
     *        vacio o null = "consulta.csv"
     */
    public static List<TableResult> exportQuery(Connection connection,
                                                String sql, int maxRows,
                                                File dir, String fileName,
                                                Progress progress) {
        List<TableResult> results = new ArrayList<TableResult>();
        String checked;
        try {
            checked = checkedQuery(sql);
        } catch (SQLException e) {
            results.add(new TableResult("consulta", null, 0, false,
                    e.getMessage(), new ArrayList<String>()));
            return results;
        }
        String name = fileName == null || fileName.trim().isEmpty()
                ? "consulta" : fileName;
        File out = new File(dir, fileNameFor(name));
        results.add(runSelect(connection, checked, maxRows, out, out.getName(),
                1, 1, progress));
        return results;
    }

    /**
     * Normaliza la consulta del usuario: sin ';' final (el driver la rechaza)
     * y validando que sea un SELECT (o WITH ... SELECT), admitiendo
     * comentarios iniciales.
     */
    public static String checkedQuery(String raw) throws SQLException {
        String sql = raw == null ? "" : raw.trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
            throw new SQLException("La consulta personalizada esta vacia.");
        }
        String head = sql;
        while (true) {
            if (head.startsWith("--")) {
                int eol = head.indexOf('\n');
                head = eol < 0 ? "" : head.substring(eol + 1).trim();
            } else if (head.startsWith("/*")) {
                int end = head.indexOf("*/");
                head = end < 0 ? "" : head.substring(end + 2).trim();
            } else {
                break;
            }
        }
        if (!startsWithWord(head, "SELECT") && !startsWithWord(head, "WITH")) {
            throw new SQLException("La consulta personalizada debe ser un SELECT"
                    + " (o WITH ... SELECT).");
        }
        return sql;
    }

    private static boolean startsWithWord(String sql, String word) {
        return sql.length() >= word.length()
                && sql.regionMatches(true, 0, word, 0, word.length())
                && (sql.length() == word.length()
                        || !isWordChar(sql.charAt(word.length())));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static TableResult exportTable(Connection connection, String token,
                                           String qualified, String where,
                                           int maxRows, File dir, int index,
                                           int total, Progress progress) {
        String sql = "SELECT * FROM " + qualified
                + (where.isEmpty() ? "" : " WHERE " + where);
        File out = new File(dir, fileNameFor(token));
        return runSelect(connection, sql, maxRows, out, qualified,
                index, total, progress);
    }

    /** Nucleo comun: ejecuta el SELECT y vuelca el ResultSet al CSV. */
    private static TableResult runSelect(Connection connection, String sql,
                                         int maxRows, File out,
                                         String label, int index, int total,
                                         Progress progress) {
        List<String> warnings = new ArrayList<String>();
        Statement st = null;
        ResultSet rs = null;
        Writer writer = null;
        long rows = 0;
        boolean truncated = false;
        boolean cancelled = false;
        String error = null;
        try {
            st = connection.createStatement();
            if (maxRows > 0) {
                // una fila extra solo para detectar que la tabla se trunco
                st.setMaxRows(maxRows + 1);
                st.setFetchSize(Math.min(FETCH_SIZE, maxRows + 1));
            } else {
                st.setFetchSize(FETCH_SIZE);
            }
            rs = st.executeQuery(sql);
            ResultSetMetaData md = rs.getMetaData();
            int columns = md.getColumnCount();

            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(out), CHARSET));
            writeHeader(writer, md, columns);

            Set<String> warned = new LinkedHashSet<String>();
            while (rs.next()) {
                rows++;
                if (maxRows > 0 && rows > maxRows) {
                    truncated = true;
                    rows--;
                    break;
                }
                writeRow(writer, rs, md, columns, warnings, warned);
                if (progress != null && rows % PROGRESS_EVERY_ROWS == 0
                        && !progress.onProgress(label, index, total, rows)) {
                    cancelled = true;
                    break;
                }
            }
            if (truncated) {
                warnings.add("Se exportaron solo " + maxRows + " filas de "
                        + label + "; el origen tiene mas datos.");
            }
        } catch (Exception e) {
            error = errorText(e, sql);
        } finally {
            // cerrar antes de poder borrar el CSV parcial (Windows lo exige)
            closeQuietly(writer);
            closeQuietly(rs);
            closeQuietly(st);
        }
        if (error != null) {
            deleteQuietly(out);
            return new TableResult(label, null, rows, false, error, warnings);
        }
        if (cancelled) {
            deleteQuietly(out);
            return new TableResult(label, null, rows, false,
                    "Cancelado por el usuario.", warnings);
        }
        return new TableResult(label, out.getName(), rows, truncated,
                null, warnings);
    }

    private static void writeHeader(Writer writer, ResultSetMetaData md,
                                    int columns) throws IOException, SQLException {
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                writer.write(SEPARATOR);
            }
            writer.write(escape(md.getColumnLabel(i)));
        }
        writer.write("\r\n");
    }

    private static void writeRow(Writer writer, ResultSet rs,
                                 ResultSetMetaData md, int columns,
                                 List<String> warnings, Set<String> warned)
            throws IOException, SQLException {
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                writer.write(SEPARATOR);
            }
            writer.write(escape(cellText(rs, md, i, warnings, warned)));
        }
        writer.write("\r\n");
    }

    /**
     * Valor de una celda como texto CSV (tal cual lo devuelve el driver):
     * numeros con punto decimal, fechas ISO, RAW en hexadecimal, NULL y
     * BLOB como celda vacia (con aviso una vez por columna).
     */
    private static String cellText(ResultSet rs, ResultSetMetaData md, int i,
                                   List<String> warnings, Set<String> warned)
            throws SQLException {
        int type = md.getColumnType(i);
        String column = md.getColumnLabel(i);
        String text;
        switch (type) {
            case Types.DATE:
            case Types.TIMESTAMP:
                Timestamp ts = rs.getTimestamp(i);
                text = ts == null ? null : timestampText(ts);
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                byte[] bytes = rs.getBytes(i);
                text = bytes == null ? null : toHex(bytes);
                break;
            case Types.BLOB:
                rs.getBytes(i); // fuerza wasNull
                if (!rs.wasNull()) {
                    warnOnce(warned, "blob:" + column, warnings,
                            "La columna " + column + " es BLOB y no se exporta; "
                                    + "queda vacia en el CSV.");
                }
                return "";
            default:
                // VARCHAR/NUMBER/CLOB/...: getString da la representacion
                // canonica del driver (punto decimal, sin formato local)
                text = rs.getString(i);
        }
        if (text == null || rs.wasNull()) {
            return "";
        }
        return text;
    }

    private static String timestampText(Timestamp ts) {
        String text = ts.toLocalDateTime().format(DATE_TIME);
        if (ts.getNanos() != 0) {
            text += '.' + String.format("%09d", ts.getNanos());
        }
        return text;
    }

    /**
     * Entrecomilla el valor si contiene el separador, comillas o saltos de
     * linea; dentro, las comillas se duplican. Es publico para probarlo
     * desde SelfTest.
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf(SEPARATOR) < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString().toUpperCase();
    }

    private static void warnOnce(Set<String> warned, String key,
                                 List<String> warnings, String message) {
        if (warned.add(key)) {
            warnings.add(message);
        }
    }

    private static String errorText(Exception e, String sql) {
        String message = e.getMessage();
        return (message == null ? e.getClass().getSimpleName() : message)
                + " [SQL: " + sql + "]";
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Throwable ignored) {
            // el aviso ya queda en el error de la tabla
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            if (resource != null) {
                resource.close();
            }
        } catch (Throwable ignored) {
            // cierre lo mejor posible; el resultado ya se calculo
        }
    }
}
