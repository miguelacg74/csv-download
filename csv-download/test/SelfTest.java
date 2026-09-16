import com.csv.download.CsvExporter;
import com.csv.download.ExportPreset;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prueba manual del exportador de CSV (no requiere SQL Developer).
 * Simula una tabla con una conexion JDBC falsa (java.lang.reflect.Proxy).
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        // ---- troceado del listado de tablas
        List<String> tables = CsvExporter.parseTableList(
                " EMP , HR.DEPT ;\n emp\n\n  , \"Mi Tabla\" ");
        check(tables.size() == 3,
                "se esperaban 3 tablas sin duplicados, hubo " + tables);
        check("EMP".equals(tables.get(0)), "primer nombre mal troceado");
        check("HR.DEPT".equals(tables.get(1)), "segundo nombre mal troceado");
        check("\"Mi Tabla\"".equals(tables.get(2)), "nombre comillado mal troceado");
        check(CsvExporter.parseTableList("  , ; \n ").isEmpty(),
                "un listado vacio no deberia dar tablas");

        // ---- nombres cualificados y de archivo
        check("HR.EMP".equals(CsvExporter.qualifiedName("HR.EMP")),
                "qualifiedName con esquema mal armado");
        check("EMP".equals(CsvExporter.qualifiedName("EMP")),
                "qualifiedName simple mal armado");
        check("\"miTabla\"".equals(CsvExporter.qualifiedName("miTabla")),
                "identificador case-sensitive sin comillas");
        check("HR.\"Mi Tabla\"".equals(CsvExporter.qualifiedName("HR.\"Mi Tabla\"")),
                "parte ya comillada deberia conservarse");
        check("HR.EMP.csv".equals(CsvExporter.fileNameFor("HR.EMP")),
                "nombre de archivo mal armado");
        check("a_b.csv".equals(CsvExporter.fileNameFor("a/b")),
                "caracter ilegal no sustituido");
        check("salida.csv".equals(CsvExporter.fileNameFor("salida.csv")),
                "la extension .csv no deberia duplicarse");

        // ---- escapeo de celdas
        check("abc".equals(CsvExporter.escape("abc")), "valor simple mal escapado");
        check("\"a;b\"".equals(CsvExporter.escape("a;b")),
                "valor con separador sin comillas");
        check("\"di\"\"cho\"".equals(CsvExporter.escape("di\"cho")),
                "comillas internas no duplicadas");
        check("\"a\nb\"".equals(CsvExporter.escape("a\nb")),
                "salto de linea sin comillas");
        check("".equals(CsvExporter.escape(null)) && "".equals(CsvExporter.escape("")),
                "vacio/null deberia ser celda vacia");

        // ---- exportacion contra una conexion JDBC falsa (Proxy)
        // la tabla simulada tiene ID (NUMBER), NOMBRE (VARCHAR2), FEC (DATE)
        final String[] executedSql = new String[1];
        Object[][] rows = {
                {new BigDecimal("12.30"), "ana", Timestamp.valueOf("2024-01-31 14:30:00")},
                {new BigDecimal("2"), "a;b", null},
                {new BigDecimal("3"), "di\"cho", Timestamp.valueOf("2024-02-01 00:00:00.5")}
        };
        Connection fake = fakeConnection(rows, executedSql);
        File dir = Files.createTempDirectory("csvselftest").toFile();

        List<CsvExporter.TableResult> results =
                CsvExporter.export(fake, Arrays.asList("EMP"), "", 0, dir, null);
        check(results.size() == 1, "deberia haber un resultado");
        CsvExporter.TableResult r = results.get(0);
        check(r.isOk(), "la exportacion fallo: " + r.getError());
        check(r.getRows() == 3, "se esperaban 3 filas, hubo " + r.getRows());
        check(!r.isTruncated(), "sin limite no deberia truncar");
        check("SELECT * FROM EMP".equals(executedSql[0]),
                "SQL ejecutado inesperado: " + executedSql[0]);

        String csv = readFile(new File(dir, "EMP.csv"));
        String[] lines = csv.split("\r\n");
        check(lines.length == 4, "el CSV deberia tener cabecera + 3 filas: " + csv);
        check("ID;NOMBRE;FEC".equals(lines[0]),
                "cabecera mal escrita: " + lines[0]);
        check("12.30;ana;2024-01-31 14:30:00".equals(lines[1]),
                "primera fila mal escrita: " + lines[1]);
        check("2;\"a;b\";".equals(lines[2]),
                "segunda fila mal escrita (separador en dato o NULL): " + lines[2]);
        check(lines[3].startsWith("3;\"di\"\"cho\";2024-02-01 00:00:00."),
                "tercera fila mal escrita: " + lines[3]);

        // ---- WHERE y limite aplicados a todas las tablas
        results = CsvExporter.export(fake, Arrays.asList("HR.EMP", "DEPT"),
                "ID > 1", 2, dir, null);
        check("SELECT * FROM DEPT WHERE ID > 1".equals(executedSql[0]),
                "el WHERE no se aplico a todas las tablas: " + executedSql[0]);
        check(results.size() == 2 && results.get(0).isOk()
                && results.get(1).isOk(), "deberian exportarse las 2 tablas");
        check(results.get(0).getRows() == 2 && results.get(0).isTruncated(),
                "el limite deberia truncar a 2 filas");
        csv = readFile(new File(dir, "HR.EMP.csv"));
        check(csv.split("\r\n").length == 3,
                "el CSV truncado deberia tener cabecera + 2 filas");
        check(!results.get(0).getWarnings().isEmpty(),
                "la truncada deberia avisar");

        // ---- una tabla que falla no detiene el lote
        results = CsvExporter.export(fake, Arrays.asList("BAD", "EMP"),
                "", 0, dir, null);
        check(results.size() == 2, "deberia seguir tras el error");
        check(!results.get(0).isOk()
                && results.get(0).getError().contains("ORA-00942"),
                "el error deberia registrarse: " + results.get(0).getError());
        check(results.get(0).getError().contains("SELECT * FROM BAD"),
                "el error deberia incluir el SQL");
        check(results.get(1).isOk(), "la segunda tabla deberia exportarse");
        check(!new File(dir, "BAD.csv").exists(),
                "no deberia quedar CSV de la tabla fallida");

        // ---- cancelacion entre tablas
        final AtomicInteger calls = new AtomicInteger();
        results = CsvExporter.export(fake, Arrays.asList("EMP", "DEPT"),
                "", 0, dir, new CsvExporter.Progress() {
                    public boolean onProgress(String t, int i, int n, long rows) {
                        return calls.incrementAndGet() < 2; // cancela en la 2a
                    }
                });
        check(results.size() == 1,
                "al cancelar en la segunda tabla solo deberia quedar la primera");

        // ---- consulta personalizada: se ejecuta el SQL del usuario
        // (sin ';' final) y se vuelca a un unico CSV
        results = CsvExporter.exportQuery(fake,
                "  SELECT id, nombre FROM otra WHERE x > 0;  ",
                0, dir, "puntual", null);
        check(results.size() == 1 && results.get(0).isOk(),
                "la consulta personalizada fallo: " + results.get(0).getError());
        check("SELECT id, nombre FROM otra WHERE x > 0".equals(executedSql[0]),
                "el SQL personalizado deberia ejecutarse sin ';': "
                        + executedSql[0]);
        check(results.get(0).getRows() == 3,
                "la consulta deberia devolver las 3 filas del fake");
        check(new File(dir, "puntual.csv").exists(),
                "deberia existir puntual.csv");

        results = CsvExporter.exportQuery(fake, "select 1 from dual",
                0, dir, "  ", null);
        check("consulta.csv".equals(results.get(0).getFileName()),
                "nombre vacio deberia dar consulta.csv");

        // WITH tambien se admite y los comentarios iniciales no estorban
        check("-- nota\nWITH x AS (SELECT 1 a FROM dual) SELECT * FROM x"
                .equals(CsvExporter.checkedQuery(
                        "-- nota\nWITH x AS (SELECT 1 a FROM dual) SELECT * FROM x;")),
                "WITH deberia validarse (conservando el comentario)");
        try {
            CsvExporter.checkedQuery("DELETE FROM t");
            check(false, "una consulta no-SELECT deberia fallar");
        } catch (SQLException e) {
            check(e.getMessage() != null && e.getMessage().contains("SELECT"),
                    "el error deberia pedir un SELECT: " + e.getMessage());
        }
        results = CsvExporter.exportQuery(fake, "DELETE FROM t",
                0, dir, "x", null);
        check(!results.get(0).isOk(),
                "una consulta no-SELECT deberia devolver error sin ejecutar");
        check(!new File(dir, "x.csv").exists(),
                "no deberia generarse CSV de una consulta invalida");

        // ---- preset: ida y vuelta por JSON conservando todo el estado
        ExportPreset src = new ExportPreset();
        src.setTables("EMP,\nHR.DEPT");
        src.setDirectory("C:\\salida\\csv");
        src.setWhere("ID > 1 AND NOMBRE LIKE 'A%'");
        src.setMaxRows(500);
        src.setUseCustomQuery(true);
        src.setCustomQuery("SELECT id AS \"Mi Col\", nombre\nFROM v;");
        src.setCustomFileName("puntual");

        ExportPreset back = ExportPreset.fromJson(src.toJson());
        check("EMP,\nHR.DEPT".equals(back.getTables()),
                "tablas del preset mal leidas");
        check("C:\\salida\\csv".equals(back.getDirectory()),
                "carpeta del preset mal leida");
        check("ID > 1 AND NOMBRE LIKE 'A%'".equals(back.getWhere()),
                "where del preset mal leido");
        check(back.getMaxRows() == 500 && back.isUseCustomQuery(),
                "limite/modo del preset mal leidos");
        check(src.getCustomQuery().equals(back.getCustomQuery()),
                "consulta del preset mal leida: " + back.getCustomQuery());
        check("puntual".equals(back.getCustomFileName()),
                "nombre de archivo del preset mal leido");

        // preset guardado y leido de disco
        File presetFile = File.createTempFile("preset", ".json");
        try {
            src.save(presetFile);
            ExportPreset loaded = ExportPreset.load(presetFile);
            check(src.getCustomQuery().equals(loaded.getCustomQuery()),
                    "preset en disco mal leido");
        } finally {
            presetFile.delete();
        }

        // claves desconocidas se ignoran; las que faltan quedan por defecto
        ExportPreset parcial = ExportPreset.fromJson(
                "{\"version\":2,\"tables\":\"T\",\"futuro\":{\"a\":1},"
                + "\"lista\":[1,2]}");
        check("T".equals(parcial.getTables()) && parcial.getMaxRows() == 0
                && !parcial.isUseCustomQuery(),
                "preset parcial mal leido");

        // JSON invalido -> error claro en vez de un preset corrupto
        try {
            ExportPreset.fromJson("{no es json");
            check(false, "un JSON invalido deberia fallar");
        } catch (IllegalArgumentException e) {
            check(e.getMessage() != null && e.getMessage().contains("JSON"),
                    "el error deberia explicar que el JSON es invalido");
        }

        System.out.println("OK: todas las comprobaciones pasaron");
        deleteTree(dir);
    }

    private static String readFile(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, Charset.forName("windows-1252"));
    }

    private static void deleteTree(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    /**
     * Conexion JDBC falsa via Proxy: la tabla simulada tiene columnas
     * ID (NUMBER), NOMBRE (VARCHAR2) y FEC (DATE), y las filas indicadas.
     * Cualquier SQL que mencione la tabla BAD lanza ORA-00942.
     * Guarda el ultimo SQL ejecutado en executedSql[0].
     */
    private static Connection fakeConnection(final Object[][] rows,
                                             final String[] executedSql) {
        final int[] cursor = {-1};
        final int[] lastCol = {0};
        final ResultSetMetaData md = metaData();
        final ResultSet rs = (ResultSet) proxy(ResultSet.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                String name = m.getName();
                if ("next".equals(name)) {
                    return ++cursor[0] < rows.length;
                }
                if ("getString".equals(name) || "getTimestamp".equals(name)
                        || "getBytes".equals(name) || "getObject".equals(name)) {
                    lastCol[0] = (Integer) a[0];
                    Object v = rows[cursor[0]][lastCol[0] - 1];
                    if (v == null) {
                        return null;
                    }
                    if ("getString".equals(name)) {
                        return v.toString();
                    }
                    return v;
                }
                if ("wasNull".equals(name)) {
                    return lastCol[0] == 0
                            || rows[cursor[0]][lastCol[0] - 1] == null;
                }
                if ("getMetaData".equals(name)) {
                    return md;
                }
                return defaultValue(m.getReturnType());
            }
        });
        final Statement st = (Statement) proxy(Statement.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                if ("executeQuery".equals(m.getName())) {
                    executedSql[0] = (String) a[0];
                    if (executedSql[0].contains("BAD")) {
                        throw new SQLException("ORA-00942: la tabla o vista no existe");
                    }
                    cursor[0] = -1;
                    return rs;
                }
                return defaultValue(m.getReturnType());
            }
        });
        return (Connection) proxy(Connection.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("createStatement".equals(m.getName())) {
                    return st;
                }
                return defaultValue(m.getReturnType());
            }
        });
    }

    /** Metadata falsa: ID NUMBER, NOMBRE VARCHAR2, FEC DATE. */
    private static ResultSetMetaData metaData() {
        final String[] labels = {"ID", "NOMBRE", "FEC"};
        final int[] types = {Types.NUMERIC, Types.VARCHAR, Types.DATE};
        return (ResultSetMetaData) proxy(
                ResultSetMetaData.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getColumnCount": return labels.length;
                    case "getColumnLabel":
                        return labels[((Integer) a[0]) - 1];
                    case "getColumnType":
                        return types[((Integer) a[0]) - 1];
                    case "getColumnTypeName":
                        return new String[]{"NUMBER", "VARCHAR2", "DATE"}[
                                ((Integer) a[0]) - 1];
                    default: return defaultValue(m.getReturnType());
                }
            }
        });
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(SelfTest.class.getClassLoader(),
                new Class<?>[]{iface}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
