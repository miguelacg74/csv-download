import com.csv.download.CsvExporter;
import com.csv.download.ExportPreset;
import com.csv.download.PresetBatch;
import com.csv.download.PresetHistory;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

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
                CsvExporter.export(fake, Arrays.asList("EMP"), "", "", 0, 0,
                        dir, false, false, null);
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
                "ID > 1", "", 2, 0, dir, false, false, null);
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
                "", "", 0, 0, dir, false, false, null);
        check(results.size() == 2, "deberia seguir tras el error");
        check(!results.get(0).isOk()
                && results.get(0).getError().contains("ORA-00942"),
                "el error deberia registrarse: " + results.get(0).getError());
        check(results.get(0).getError().contains("SELECT * FROM BAD"),
                "el error deberia incluir el SQL");
        check(results.get(1).isOk(), "la segunda tabla deberia exportarse");
        check(!new File(dir, "BAD.csv").exists(),
                "no deberia quedar CSV de la tabla fallida");

        // ---- omitir CSV existentes: el archivo previo se conserva y la
        // tabla no llega a consultarse (BAD lanzaria ORA-00942 si se
        // ejecutara, asi que un salto correcto es lo unico que la salva)
        File existing = new File(dir, "BAD.csv");
        Files.write(existing.toPath(), "previo\r\n".getBytes("windows-1252"));
        executedSql[0] = null;
        results = CsvExporter.export(fake, Arrays.asList("OTRA", "BAD"),
                "", "", 0, 0, dir, true, false, null);
        check(results.size() == 2, "deberia haber un resultado por tabla");
        check(results.get(0).isOk() && !results.get(0).isSkipped(),
                "OTRA deberia exportarse con normalidad");
        CsvExporter.TableResult omitida = results.get(1);
        check(omitida.isSkipped() && !omitida.isOk(),
                "BAD deberia quedar omitida, no ejecutada: "
                        + omitida.getError());
        check("BAD.csv".equals(omitida.getFileName()),
                "la omitida deberia indicar el CSV existente");
        check("SELECT * FROM OTRA".equals(executedSql[0]),
                "solo la tabla nueva deberia ejecutar el SELECT: "
                        + executedSql[0]);
        check("previo\r\n".equals(readFile(existing)),
                "el CSV existente no deberia tocarse");

        // sin la opcion, un CSV existente se reemplaza como siempre
        // (y BAD sigue fallando y dejando el archivo borrado)
        results = CsvExporter.export(fake, Arrays.asList("BAD"), "", "", 0, 0,
                dir, false, false, null);
        check(!results.get(0).isOk() && !results.get(0).isSkipped(),
                "sin la opcion, BAD deberia fallar como siempre");
        check(!existing.exists(),
                "sin la opcion, el CSV parcial de BAD deberia borrarse");

        // ---- cancelacion entre tablas
        final AtomicInteger calls = new AtomicInteger();
        results = CsvExporter.export(fake, Arrays.asList("EMP", "DEPT"),
                "", "", 0, 0, dir, false, false, new CsvExporter.Progress() {
                    public boolean onProgress(String t, int i, int n, long rows) {
                        return calls.incrementAndGet() < 2; // cancela en la 2a
                    }
                });
        check(results.size() == 1,
                "al cancelar en la segunda tabla solo deberia quedar la primera");

        // ---- salida partida en varios archivos ("filas por archivo" > 0):
        // un solo SELECT vuelca a partes secuenciales, cada una con su
        // cabecera; el limite total se reparte entre partes y, si la tabla
        // cabe en una sola, el archivo queda con el nombre limpio
        Object[][] many = new Object[450][3];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Object[]{new BigDecimal(i + 1),
                    "fila" + (i + 1), null};
        }
        Connection fakeMany = fakeConnection(many, executedSql);
        File splitDir = Files.createTempDirectory("csvsplit").toFile();
        try {
            results = CsvExporter.export(fakeMany, Arrays.asList("GRANDE"),
                    "", "", 0, 100, splitDir, false, false, null);
            r = results.get(0);
            check(r.isOk() && r.getRows() == 450,
                    "la exportacion partida fallo: " + r.getError());
            check(r.getFileNames().size() == 5,
                    "450 filas en partes de 100 deberian dar 5 archivos: "
                            + r.getFileNames());
            check("GRANDE_0001.csv".equals(r.getFileName()),
                    "el primer archivo deberia ser GRANDE_0001.csv: "
                            + r.getFileName());
            csv = readFile(new File(splitDir, "GRANDE_0001.csv"));
            check(csv.split("\r\n").length == 101
                    && csv.startsWith("ID;NOMBRE;FEC"),
                    "la parte 1 deberia tener cabecera + 100 filas");
            csv = readFile(new File(splitDir, "GRANDE_0005.csv"));
            check(csv.split("\r\n").length == 51
                    && csv.startsWith("ID;NOMBRE;FEC"),
                    "la ultima parte deberia tener cabecera + 50 filas");

            // el limite total se reparte entre partes (250 = 100+100+50)
            results = CsvExporter.export(fakeMany, Arrays.asList("TOPE"),
                    "", "", 250, 100, splitDir, false, false, null);
            r = results.get(0);
            check(r.isOk() && r.isTruncated() && r.getRows() == 250,
                    "el limite deberia truncar a 250 filas repartidas");
            check(r.getFileNames().size() == 3,
                    "250 filas en partes de 100 deberian dar 3 archivos: "
                            + r.getFileNames());

            // si cabe en una sola parte queda el nombre limpio, sin _0001
            results = CsvExporter.export(fake, Arrays.asList("EMP"), "",
                    "", 0, 100, splitDir, false, false, null);
            r = results.get(0);
            check(r.isOk() && "EMP.csv".equals(r.getFileName()),
                    "una sola parte deberia quedar como EMP.csv: "
                            + r.getFileName());
            check(new File(splitDir, "EMP.csv").exists()
                    && !new File(splitDir, "EMP_0001.csv").exists(),
                    "no deberia quedar EMP_0001.csv suelto");

            // cancelacion a mitad: se borra la parte incompleta y se
            // conservan las ya cerradas (partes de 300: al cancelar en la
            // fila 400 la parte 1 esta completa y la 2 a medias)
            results = CsvExporter.export(fakeMany, Arrays.asList("CORTA"),
                    "", "", 0, 300, splitDir, false, false,
                    new CsvExporter.Progress() {
                        public boolean onProgress(String t, int i, int n,
                                                  long rows) {
                            return rows < 400;
                        }
                    });
            r = results.get(0);
            check(!r.isOk() && r.getError() != null
                    && r.getError().contains("Cancelado"),
                    "la tabla deberia quedar cancelada: " + r.getError());
            check(new File(splitDir, "CORTA_0001.csv").exists()
                    && !new File(splitDir, "CORTA_0002.csv").exists(),
                    "deberia conservarse la parte completa y borrarse "
                            + "la incompleta");
            check(r.getFileNames().equals(Arrays.asList("CORTA_0001.csv")),
                    "el resultado deberia listar la parte conservada: "
                            + r.getFileNames());

            // "no reemplazar" detecta una exportacion partida previa por
            // su primera parte y no llega a ejecutar el SELECT
            executedSql[0] = null;
            results = CsvExporter.export(fakeMany, Arrays.asList("CORTA"),
                    "", "", 0, 300, splitDir, true, false, null);
            check(results.get(0).isSkipped()
                    && "CORTA_0001.csv".equals(results.get(0).getFileName()),
                    "con CORTA_0001.csv existente deberia omitirse");
            check(executedSql[0] == null,
                    "una tabla omitida no deberia ejecutar el SELECT");

            // ---- reanudar: con las partes 1-2 ya escritas (llenas) se
            // descartan sus 200 filas del ResultSet y se sigue en la 3
            writePart(new File(splitDir, "RETOM_0001.csv"), 100);
            writePart(new File(splitDir, "RETOM_0002.csv"), 100);
            results = CsvExporter.export(fakeMany, Arrays.asList("RETOM"),
                    "", "ID", 0, 100, splitDir, false, true, null);
            r = results.get(0);
            check(r.isOk() && r.getRows() == 450,
                    "la reanudacion fallo: " + r.getError());
            check("SELECT * FROM RETOM ORDER BY ID".equals(executedSql[0]),
                    "el ORDER BY no se aplico: " + executedSql[0]);
            check(r.getFileNames().size() == 5,
                    "reanudada deberia listar las 5 partes: "
                            + r.getFileNames());
            check("RETOM_0003.csv".equals(r.getFileNames().get(2)),
                    "la escritura deberia seguir en la parte 3: "
                            + r.getFileNames());
            csv = readFile(new File(splitDir, "RETOM_0005.csv"));
            check(csv.split("\r\n").length == 51 && csv.contains("fila450"),
                    "la parte 5 deberia tener cabecera + las 50 filas "
                            + "nuevas: " + csv);
            boolean resumed = false;
            for (String w : r.getWarnings()) {
                resumed |= w.contains("Reanudada");
            }
            check(resumed, "la reanudacion deberia avisar: "
                    + r.getWarnings());

            // reexportar en limpio (sin reanudar ni omitir) borra las
            // partes viejas para que no queden _NNNN obsoletos
            results = CsvExporter.export(fake, Arrays.asList("RETOM"), "",
                    "", 0, 100, splitDir, false, false, null);
            check(results.get(0).isOk()
                    && new File(splitDir, "RETOM.csv").exists()
                    && !new File(splitDir, "RETOM_0002.csv").exists(),
                    "en limpio deberia quedar RETOM.csv sin partes viejas");

            // si la ultima parte existente no esta llena, la exportacion
            // anterior ya termino: la tabla se omite sin tocar la BD
            writePart(new File(splitDir, "LISTA_0001.csv"), 37);
            executedSql[0] = null;
            results = CsvExporter.export(fakeMany, Arrays.asList("LISTA"),
                    "", "", 0, 100, splitDir, false, true, null);
            r = results.get(0);
            check(r.isSkipped()
                    && "LISTA_0001.csv".equals(r.getFileName()),
                    "con la ultima parte incompleta la tabla ya estaba "
                            + "completa");
            check(executedSql[0] == null,
                    "una tabla ya completa no deberia ejecutar el SELECT");
            check(!new File(splitDir, "LISTA_0002.csv").exists(),
                    "no deberia crearse una parte nueva");

            // con el nombre limpio ya presente, reanudar tambien la omite
            // (EMP.csv quedo del chequeo de parte unica)
            executedSql[0] = null;
            results = CsvExporter.export(fake, Arrays.asList("EMP"), "",
                    "", 0, 100, splitDir, false, true, null);
            check(results.get(0).isSkipped() && executedSql[0] == null,
                    "con EMP.csv existente, reanudar deberia omitirla");
        } finally {
            deleteTree(splitDir);
        }

        // ---- estimacion de filas via NUM_ROWS del diccionario (alimenta
        // el % de la barra de progreso)
        final long[] estimated = {-2};
        CsvExporter.export(fake, Arrays.asList("EMP"), "", "", 0, 0, dir,
                false, false, new CsvExporter.Progress() {
                    public boolean onProgress(String t, int i, int n,
                                              long rows) {
                        return true;
                    }

                    public void onTotalRows(String t, long est) {
                        estimated[0] = est;
                    }
                });
        check(estimated[0] == 3,
                "la estimacion deberia ser 3 filas, fue " + estimated[0]);

        // ---- consulta personalizada: se ejecuta el SQL del usuario
        // (sin ';' final) y se vuelca a un unico CSV
        results = CsvExporter.exportQuery(fake,
                "  SELECT id, nombre FROM otra WHERE x > 0;  ",
                0, 0, dir, "puntual", false, false, null);
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
                0, 0, dir, "  ", false, false, null);
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
                0, 0, dir, "x", false, false, null);
        check(!results.get(0).isOk(),
                "una consulta no-SELECT deberia devolver error sin ejecutar");
        check(!new File(dir, "x.csv").exists(),
                "no deberia generarse CSV de una consulta invalida");

        // la consulta personalizada tambien respeta el "no reemplazar":
        // puntual.csv ya existe y no se vuelve a ejecutar el SELECT
        executedSql[0] = null;
        results = CsvExporter.exportQuery(fake, "SELECT 1 FROM dual",
                0, 0, dir, "puntual", true, false, null);
        check(results.get(0).isSkipped(),
                "la consulta con CSV ya existente deberia omitirse");
        check(executedSql[0] == null,
                "una consulta omitida no deberia ejecutar ningun SELECT");

        // ---- preset: ida y vuelta por JSON conservando todo el estado
        ExportPreset src = new ExportPreset();
        src.setTables("EMP,\nHR.DEPT");
        src.setDirectory("C:\\salida\\csv");
        src.setWhere("ID > 1 AND NOMBRE LIKE 'A%'");
        src.setOrderBy("ID DESC");
        src.setMaxRows(500);
        src.setRowsPerFile(10000);
        src.setSkipExisting(true);
        src.setResume(true);
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
        check(back.getMaxRows() == 500 && back.getRowsPerFile() == 10000
                && back.isUseCustomQuery() && back.isSkipExisting()
                && back.isResume(),
                "limite/filas por archivo/modo/no-reemplazar/reanudar del "
                        + "preset mal leidos");
        check("ID DESC".equals(back.getOrderBy()),
                "orderBy del preset mal leido");
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
                && !parcial.isUseCustomQuery() && !parcial.isSkipExisting(),
                "preset parcial mal leido");

        // JSON invalido -> error claro en vez de un preset corrupto
        try {
            ExportPreset.fromJson("{no es json");
            check(false, "un JSON invalido deberia fallar");
        } catch (IllegalArgumentException e) {
            check(e.getMessage() != null && e.getMessage().contains("JSON"),
                    "el error deberia explicar que el JSON es invalido");
        }

        // ---- historial de presets recientes (MRU persistido en un
        // nodo de preferencias aislado, que se borra al terminar)
        Preferences testNode =
                Preferences.userRoot().node("com/csv/download/selftest");
        try {
            PresetHistory history = new PresetHistory(testNode);
            history.clear();
            check(history.entries().isEmpty(),
                    "el historial deberia quedar vacio tras clear");

            File pa = new File(dir, "a.json").getAbsoluteFile();
            File pb = new File(dir, "b.json").getAbsoluteFile();
            history.add(pa);
            history.add(pb);
            check(history.entries().equals(Arrays.asList(pb, pa)),
                    "el ultimo preset usado deberia ir primero: "
                            + history.entries());
            history.add(pa);
            check(history.entries().equals(Arrays.asList(pa, pb)),
                    "reanadir un preset deberia subirlo al frente: "
                            + history.entries());
            for (int i = 0; i < PresetHistory.MAX_ENTRIES + 5; i++) {
                history.add(new File(dir, "extra" + i + ".json"));
            }
            check(history.entries().size() == PresetHistory.MAX_ENTRIES,
                    "el historial deberia recortarse a "
                            + PresetHistory.MAX_ENTRIES + " entradas");
            history.remove(history.entries().get(0));
            check(history.entries().size() == PresetHistory.MAX_ENTRIES - 1,
                    "remove deberia sacar la entrada del historial");

            // persistencia: otra instancia sobre el mismo nodo ve lo mismo
            check(new PresetHistory(testNode).entries()
                    .equals(history.entries()),
                    "el historial no se conservo en las preferencias");

            // ultimas carpetas recordadas (lote de presets y salida)
            history.setLastBatchDirectory(new File("C:\\lotes"));
            history.setLastOutputDirectory(new File("C:\\salida"));
            PresetHistory reloaded = new PresetHistory(testNode);
            check("C:\\lotes".equals(reloaded.lastBatchDirectory()),
                    "la ultima carpeta de lote no se recordo");
            check("C:\\salida".equals(reloaded.lastOutputDirectory()),
                    "la ultima carpeta de salida no se recordo");
        } finally {
            testNode.removeNode();
        }

        // ---- lote de presets: cada preset vuelca a su propia carpeta
        // (se crea si no existe) y los que fallan no detienen el lote
        File batchDir = Files.createTempDirectory("csvpresets").toFile();
        File out1 = new File(batchDir, "salida1");
        File out2 = new File(batchDir, "salida2");

        ExportPreset p1 = new ExportPreset();
        p1.setTables("EMP, BAD");           // BAD falla: el preset sigue
        p1.setWhere("ID > 1");
        p1.setMaxRows(2);
        p1.setSkipExisting(true);           // los CSV ya hechos se omiten
        p1.setDirectory(out1.getAbsolutePath());
        p1.save(new File(batchDir, "a_tablas.json"));

        ExportPreset p2 = new ExportPreset();
        p2.setUseCustomQuery(true);
        p2.setCustomQuery("SELECT id FROM t;");
        p2.setCustomFileName("consulta_lote");
        p2.setDirectory(out2.getAbsolutePath());
        p2.save(new File(batchDir, "b_consulta.json"));

        Files.write(new File(batchDir, "c_roto.json").toPath(),
                "{mal json".getBytes("UTF-8"));

        ExportPreset p4 = new ExportPreset();   // sin tablas ni consulta
        p4.setDirectory(out1.getAbsolutePath());
        p4.save(new File(batchDir, "d_vacio.json"));

        Files.write(new File(batchDir, "ignorame.txt").toPath(),
                "no es preset".getBytes("UTF-8"));

        List<File> presets = PresetBatch.listPresets(batchDir);
        check(presets.size() == 4,
                "deberian encontrarse 4 *.json, hubo " + presets.size());
        check("a_tablas.json".equals(presets.get(0).getName()),
                "los presets deberian ir ordenados por nombre: " + presets);

        final List<String> presetStarts = new ArrayList<String>();
        final AtomicInteger statements = new AtomicInteger();
        PresetBatch.Progress rec = new PresetBatch.Progress() {
            public boolean onProgress(String presetFile, int pi, int pt,
                                      String table, int i, int n, long rows) {
                if (table.isEmpty()) {
                    presetStarts.add(presetFile);
                }
                return true;
            }

            public void onStatement(Statement st) {
                statements.incrementAndGet();
            }
        };
        List<PresetBatch.PresetResult> batch =
                PresetBatch.run(fake, presets, false, rec);
        check(batch.size() == 4,
                "deberia haber un resultado por preset, hubo " + batch.size());
        check(presetStarts.size() == 4
                && "a_tablas.json".equals(presetStarts.get(0)),
                "deberia avisarse al empezar cada preset: " + presetStarts);
        check(statements.get() >= 3,
                "onStatement deberia invocarse por cada consulta ejecutada: "
                        + statements.get());

        PresetBatch.PresetResult r1 = batch.get(0);
        check(r1.isOk(), "el preset de tablas fallo: " + r1.getError());
        check(out1.equals(r1.getOutputDir()),
                "carpeta de salida del preset de tablas inesperada");
        check(r1.getTables().size() == 2 && r1.getTables().get(0).isOk()
                && !r1.getTables().get(1).isOk(),
                "dentro del preset, BAD deberia fallar y EMP exportarse");
        check(new File(out1, "EMP.csv").exists(),
                "el preset de tablas no genero EMP.csv en su carpeta");

        PresetBatch.PresetResult r2 = batch.get(1);
        check(r2.isOk() && r2.getTables().get(0).isOk(),
                "el preset de consulta fallo: " + r2.getError());
        check(new File(out2, "consulta_lote.csv").exists(),
                "el preset de consulta no genero su CSV");

        check(batch.get(2).getError() != null,
                "el JSON roto deberia registrar un error de lectura");
        check(batch.get(3).getError() != null,
                "el preset sin tablas ni consulta deberia dar error");

        // segunda pasada del lote: con skipExisting del preset, el CSV ya
        // generado se omite (BAD sigue fallando: su CSV no existe)
        batch = PresetBatch.run(fake, presets, false, null);
        check(batch.get(0).getTables().get(0).isSkipped(),
                "con skipExisting del preset, EMP ya descargada se omite");
        check(!batch.get(0).getTables().get(1).isOk()
                && !batch.get(0).getTables().get(1).isSkipped(),
                "BAD deberia seguir fallando en la segunda pasada");
        check(batch.get(1).getTables().get(0).isOk(),
                "el preset de consulta (sin la opcion) reexporta su CSV");

        // override del dialogo: TODO el lote omite los CSV existentes,
        // aunque el preset no tenga la opcion guardada
        batch = PresetBatch.run(fake, presets, true, null);
        check(batch.get(0).getTables().get(0).isSkipped(),
                "con el override, EMP deberia omitirse");
        check(batch.get(1).getTables().get(0).isSkipped(),
                "con el override, la consulta se omite aunque el preset "
                        + "no tenga la opcion");

        // cancelacion: devolver false en el primer aviso (inicio del primer
        // preset) corta el lote sin ejecutar nada
        batch = PresetBatch.run(fake, presets, false,
                new PresetBatch.Progress() {
                    public boolean onProgress(String presetFile, int pi,
                                              int pt, String table, int i,
                                              int n, long rows) {
                        return false;
                    }
                });
        check(batch.isEmpty(),
                "al cancelar al inicio no deberia ejecutarse ningun preset");

        System.out.println("OK: todas las comprobaciones pasaron");
        deleteTree(dir);
        deleteTree(batchDir);
    }

    private static String readFile(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, Charset.forName("windows-1252"));
    }

    private static void deleteTree(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
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
        // PreparedStatement falso para la estimacion via NUM_ROWS del
        // diccionario: una unica fila con el numero de filas simuladas
        // (reusable: el cursor se reinicia en cada executeQuery)
        final int[] estSeen = {0};
        final ResultSet estRs = (ResultSet) proxy(ResultSet.class,
                new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("next".equals(m.getName())) {
                    return estSeen[0]++ == 0;
                }
                if ("getLong".equals(m.getName())) {
                    return (long) rows.length;
                }
                return defaultValue(m.getReturnType());
            }
        });
        final PreparedStatement ps = (PreparedStatement) proxy(
                PreparedStatement.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("executeQuery".equals(m.getName())) {
                    estSeen[0] = 0;
                    return estRs;
                }
                return defaultValue(m.getReturnType());
            }
        });
        return (Connection) proxy(Connection.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if ("createStatement".equals(m.getName())) {
                    return st;
                }
                if ("prepareStatement".equals(m.getName())) {
                    return ps;
                }
                if ("isValid".equals(m.getName())) {
                    return true;
                }
                return defaultValue(m.getReturnType());
            }
        });
    }

    /** Parte CSV preexistente ({@code <tabla>_000N.csv}) para las pruebas. */
    private static void writePart(File file, int dataLines) throws Exception {
        StringBuilder sb = new StringBuilder("ID;NOMBRE;FEC\r\n");
        for (int i = 0; i < dataLines; i++) {
            sb.append(i + 1).append(";x;\r\n");
        }
        Files.write(file.toPath(), sb.toString().getBytes("windows-1252"));
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
