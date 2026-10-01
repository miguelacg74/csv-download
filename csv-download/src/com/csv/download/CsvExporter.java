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
import java.util.Collections;
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
     * Callback de progreso y cancelacion: se invoca al empezar cada tabla,
     * justo antes de cada {@code executeQuery} y cada
     * {@link #PROGRESS_EVERY_ROWS} filas. Devolver {@code false} cancela la
     * exportacion (la tabla en curso se descarta).
     */
    public interface Progress {
        boolean onProgress(String table, int index, int total, long rows);

        /**
         * Se invoca al crear cada Statement, antes de ejecutarlo: permite
         * que quien muestra el progreso guarde la referencia y llame a
         * {@link Statement#cancel()} para interrumpir un executeQuery largo
         * o bloqueado (el driver lanza ORA-01013, que se reporta como
         * cancelacion). Implementacion vacia por defecto.
         */
        default void onStatement(Statement statement) {
        }

        /**
         * Se invoca al abrir cada parte cuando la salida se parte en varios
         * archivos (filas por archivo &gt; 0): permite mostrar en el
         * progreso el CSV en curso. Implementacion vacia por defecto.
         */
        default void onPart(String table, int index, int total,
                            int part, String fileName) {
        }
    }

    /** Resultado de la exportacion de una tabla. */
    public static final class TableResult {

        private final String table;
        private final List<String> fileNames;
        private final long rows;
        private final boolean truncated;
        private final boolean skipped;
        private final String error;
        private final List<String> warnings;

        private TableResult(String table, List<String> fileNames, long rows,
                            boolean truncated, boolean skipped,
                            String error, List<String> warnings) {
            this.table = table;
            this.fileNames = fileNames;
            this.rows = rows;
            this.truncated = truncated;
            this.skipped = skipped;
            this.error = error;
            this.warnings = warnings;
        }

        public String getTable() {
            return table;
        }

        /**
         * Nombre del primer CSV de la tabla (o del existente si se omitio);
         * null si no quedo ningun archivo.
         */
        public String getFileName() {
            return fileNames.isEmpty() ? null : fileNames.get(0);
        }

        /**
         * Nombres de todos los CSV de la tabla: varios si la salida se
         * partio por filas ({@code <tabla>_0001.csv}, {@code _0002}...);
         * las partes completas conservadas si se cancelo o fallo a mitad;
         * el existente si se omitio. Vacia si no quedo ningun archivo.
         */
        public List<String> getFileNames() {
            return fileNames;
        }

        public long getRows() {
            return rows;
        }

        /** True si se alcanzo el limite de filas y la tabla tiene mas datos. */
        public boolean isTruncated() {
            return truncated;
        }

        /**
         * True si la tabla se omitio porque su CSV ya existia (opcion
         * "no reemplazar"): no es un error, pero tampoco se genero nada.
         */
        public boolean isSkipped() {
            return skipped;
        }

        /** Mensaje de error; null si la tabla se exporto bien o se omitio. */
        public String getError() {
            return error;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        /** True si el CSV se escribio en esta ejecucion. */
        public boolean isOk() {
            return error == null && !fileNames.isEmpty() && !skipped;
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
     * @param rowsPerFile maximo de filas por archivo: &gt; 0 reparte la
     *                 salida de cada tabla en {@code <tabla>_0001.csv},
     *                 {@code <tabla>_0002.csv}... (un solo SELECT en
     *                 streaming; cada parte lleva su cabecera y, si la
     *                 tabla cabe en una sola parte, el archivo queda con
     *                 el nombre limpio {@code <tabla>.csv})
     * @param dir      carpeta de salida (debe existir)
     * @param skipExisting true = omitir las tablas cuyo CSV ya existe en
     *                 {@code dir} (no se consultan ni se reemplazan; quedan
     *                 marcadas con {@link TableResult#isSkipped()})
     * @param progress callback de progreso/cancelacion; null = sin callback
     * @return un TableResult por tabla, en el orden del listado; la lista se
     *         corta si el usuario cancela
     */
    public static List<TableResult> export(Connection connection,
                                           List<String> tables, String where,
                                           int maxRows, int rowsPerFile,
                                           File dir, boolean skipExisting,
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
                    maxRows, rowsPerFile, dir, skipExisting, index,
                    tables.size(), progress));
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
     * @param rowsPerFile como en {@link #export}: &gt; 0 parte la salida en
     *        {@code <nombre>_0001.csv}, {@code _0002}...
     */
    public static List<TableResult> exportQuery(Connection connection,
                                                String sql, int maxRows,
                                                int rowsPerFile, File dir,
                                                String fileName,
                                                boolean skipExisting,
                                                Progress progress) {
        List<TableResult> results = new ArrayList<TableResult>();
        String checked;
        try {
            checked = checkedQuery(sql);
        } catch (SQLException e) {
            results.add(new TableResult("consulta", new ArrayList<String>(),
                    0, false, false, e.getMessage(), new ArrayList<String>()));
            return results;
        }
        String name = fileName == null || fileName.trim().isEmpty()
                ? "consulta" : fileName;
        File out = new File(dir, fileNameFor(name));
        results.add(runSelect(connection, checked, maxRows, rowsPerFile, out,
                out.getName(), 1, 1, skipExisting, progress));
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
                                           int maxRows, int rowsPerFile,
                                           File dir, boolean skipExisting,
                                           int index, int total,
                                           Progress progress) {
        String sql = "SELECT * FROM " + qualified
                + (where.isEmpty() ? "" : " WHERE " + where);
        File out = new File(dir, fileNameFor(token));
        return runSelect(connection, sql, maxRows, rowsPerFile, out,
                qualified, index, total, skipExisting, progress);
    }

    /**
     * Nucleo comun: ejecuta UN solo SELECT y vuelca el ResultSet en
     * streaming al CSV. Con {@code rowsPerFile} &gt; 0 el escritor rota de
     * archivo cada tantas filas ({@code <base>_0001.csv}, {@code _0002}...),
     * repitiendo la cabecera en cada parte; si solo se llego a una parte,
     * el archivo se renombra al nombre limpio {@code <base>.csv}.
     *
     * Si se cancela o falla a mitad se borra solo la parte en curso y se
     * conservan las partes ya completas (quedan en
     * {@link TableResult#getFileNames()} con un aviso).
     */
    private static TableResult runSelect(Connection connection, String sql,
                                         int maxRows, int rowsPerFile,
                                         File out, String label,
                                         int index, int total,
                                         boolean skipExisting,
                                         Progress progress) {
        File firstPart = rowsPerFile > 0 ? partFile(out, 1) : out;
        if (skipExisting && (out.isFile() || firstPart.isFile())) {
            // el CSV ya existe (entero o su primera parte): ni se consulta
            // la tabla ni se toca el archivo (sirve para reanudar lotes
            // cancelados o con fallos)
            String existing = out.isFile() ? out.getName()
                    : firstPart.getName();
            return new TableResult(label,
                    Collections.singletonList(existing), 0, false, true,
                    null, new ArrayList<String>());
        }
        List<String> warnings = new ArrayList<String>();
        List<String> fileNames = new ArrayList<String>();
        Statement st = null;
        ResultSet rs = null;
        Writer writer = null;
        File current = firstPart;
        long rows = 0;
        int rowsInPart = 0;
        boolean truncated = false;
        boolean cancelled = false;
        String error = null;
        try {
            st = connection.createStatement();
            if (progress != null) {
                progress.onStatement(st);
            }
            if (maxRows > 0) {
                // una fila extra solo para detectar que la tabla se trunco
                st.setMaxRows(maxRows + 1);
                st.setFetchSize(Math.min(FETCH_SIZE, maxRows + 1));
            } else {
                st.setFetchSize(FETCH_SIZE);
            }
            if (progress != null
                    && !progress.onProgress(label, index, total, 0)) {
                cancelled = true;
            } else {
                rs = st.executeQuery(sql);
                ResultSetMetaData md = rs.getMetaData();
                int columns = md.getColumnCount();

                writer = openWriter(current);
                fileNames.add(current.getName());
                writeHeader(writer, md, columns);
                if (rowsPerFile > 0 && progress != null) {
                    progress.onPart(label, index, total, 1,
                            current.getName());
                }

                Set<String> warned = new LinkedHashSet<String>();
                while (rs.next()) {
                    rows++;
                    if (maxRows > 0 && rows > maxRows) {
                        truncated = true;
                        rows--;
                        break;
                    }
                    if (rowsPerFile > 0 && rowsInPart >= rowsPerFile) {
                        // parte llena: se cierra y se abre la siguiente
                        // (solo si hay fila que escribir: nunca queda una
                        // parte vacia con solo la cabecera)
                        closeQuietly(writer);
                        current = partFile(out, fileNames.size() + 1);
                        writer = openWriter(current);
                        fileNames.add(current.getName());
                        writeHeader(writer, md, columns);
                        rowsInPart = 0;
                        if (progress != null) {
                            progress.onPart(label, index, total,
                                    fileNames.size(), current.getName());
                        }
                    }
                    writeRow(writer, rs, md, columns, warnings, warned);
                    rowsInPart++;
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
            }
        } catch (Exception e) {
            if (isCancel(e)) {
                cancelled = true;
            } else {
                error = errorText(e, sql);
            }
        } finally {
            // cerrar antes de poder borrar el CSV parcial (Windows lo exige)
            closeQuietly(writer);
            closeQuietly(rs);
            closeQuietly(st);
        }
        if (error != null || cancelled) {
            // la parte en curso quedo incompleta y se borra; las partes ya
            // cerradas se conservan para no perder el trabajo hecho
            fileNames.remove(current.getName());
            deleteQuietly(current);
            if (!fileNames.isEmpty()) {
                warnings.add("Se conservaron " + fileNames.size()
                        + " parte(s) ya completas de " + label + ".");
            }
            return new TableResult(label, fileNames, rows, false, false,
                    error != null ? error : "Cancelado por el usuario.",
                    warnings);
        }
        if (rowsPerFile > 0 && fileNames.size() == 1) {
            // una sola parte: se queda con el nombre limpio <tabla>.csv
            deleteQuietly(out);
            if (firstPart.renameTo(out)) {
                fileNames.set(0, out.getName());
            }
        }
        return new TableResult(label, fileNames, rows, truncated,
                false, null, warnings);
    }

    /** {@code <base>.csv} -&gt; {@code <base>_0001.csv} para la parte N. */
    private static File partFile(File base, int part) {
        String name = base.getName();
        String stem = name.toLowerCase().endsWith(".csv")
                ? name.substring(0, name.length() - 4) : name;
        return new File(base.getParentFile(),
                stem + "_" + String.format("%04d", part) + ".csv");
    }

    private static Writer openWriter(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), CHARSET));
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

    /** ORA-01013: la consulta se interrumpio desde el boton Cancelar. */
    private static boolean isCancel(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("ORA-01013");
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
