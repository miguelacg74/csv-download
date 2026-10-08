# Reglas del proyecto: Descarga de tablas a CSV (plugin SQL Developer)

Extension OSGi para Oracle SQL Developer clasico (probado en 24.3.1.347.1826).
Codigo y documentacion en espanol, sin tildes en fuentes/comentarios (ASCII).

## Build, instalacion y pruebas

- Compilar: `csv-download\build.bat` (Windows). Asume `SQLDEV_HOME=C:\app\sqldeveloper`
  y el JDK en `%SQLDEV_HOME%\jdk\jre\bin`. En bash: `SQLDEV_HOME=... ./build.sh`.
- Instalar: copiar `dist\com.csv.download.jar` a `%SQLDEV_HOME%\dropins\`
  (NUNCA a `sqldeveloper\extensions\`, esa carpeta solo carga bundles registrados
  en `configuration\bundles.info`) y reiniciar SQL Developer.
- Prueba sin IDE: `javac -d out -cp build test\SelfTest.java` y
  `java -cp "out;build" SelfTest`. Ojo: `java.exe` es binario Windows; usa rutas
  Windows y `;` como separador (las rutas `/tmp` de Git Bash no resuelven).
- `SelfTest` usa una conexion JDBC falsa (`java.lang.reflect.Proxy`) que simula
  una tabla con filas: ejercita `CsvExporter.export` SIN base de datos. Al cambiar
  la extraccion/escritura, actualizar esa simulacion y mantener verdes los chequeos
  de contenido del CSV, WHERE/limite, error por tabla y cancelacion.
  `prepareStatement` de la conexion falsa devuelve el estimador si el SQL
  contiene `NUM_ROWS` y, si no, un PreparedStatement de datos que guarda
  los valores enlazados en `SelfTest.BOUND` (pruebas de variables).
- Con Git Bash, invocar el JDK como
  `"/c/app/sqldeveloper/jdk/jre/bin/javac.exe" ... test/SelfTest.java`
  (con `C:\...` y `test\...` bash se come las barras invertidas).
- `build/`, `dist/` y `out/` NO se versionan (estan en `.gitignore`); los dos
  primeros se regeneran con cada `build.bat` y `out/` con cada corrida de
  `SelfTest`. No re-añadirlos con `git add -f` ni committeandolos a mano.

## Arquitectura

Flujo unico: **CsvExportDialog -> CsvExportDialog.Request -> CsvExporter.export
-> resumen**. El controller (`CsvDownloadController`) solo resuelve la conexion
del contexto y orquesta; toda la logica de datos vive en `CsvExporter`.

- `CsvExporter` (Java puro): `parseTableList` (comas/`;`/saltos de linea,
  dedup case-insensitive), `qualifiedName` (partes separadas por '.' fuera de
  comillas; identificador simple sin comillas -> MAYUSCULAS como Oracle,
  `table1` = `TABLE1`; comillado se conserva; resto -> `quoteIfNeeded`), `fileNameFor` (sanitiza caracteres ilegales de
  Windows), `export` (lote: una tabla que falla no detiene el lote; cancelacion
  via `Progress` que devuelve false) y `escape`/celdas CSV.
  `export`/`exportQuery` admiten `skipExisting`: si el CSV de salida ya existe
  la tabla se omite SIN ejecutar el SELECT ni tocar el archivo (el resultado
  queda `isSkipped()`: ni `isOk()` ni error, y el resumen la muestra como
  `[OMITIDA]`). Sirve para reanudar lotes cancelados o con fallos.
- Salida partida: `export`/`exportQuery` admiten `rowsPerFile` (> 0 reparte
  la salida de cada tabla en `<tabla>_0001.csv`, `_0002`... con cabecera en
  cada parte, todo con UN solo SELECT en streaming — no hay paginacion). Si
  la tabla cabe en una parte el archivo queda con el nombre limpio
  `<tabla>.csv` (se renombra la unica `_0001` al cerrar). Si se cancela o
  falla a mitad se borra solo la parte en curso y se conservan las partes
  completas, que quedan en `TableResult.getFileNames()` (con aviso). El
  `skipExisting` detecta tanto `<tabla>.csv` como `<tabla>_0001.csv`. El
  `TableResult` lleva `getFileNames()` con todos los CSV de la tabla;
  `getFileName()` devuelve el primero (o el existente omitido).
- Reanudacion: `export`/`exportQuery` admiten `resume` (solo con
  `rowsPerFile` > 0): las partes consecutivas ya existentes se conservan y
  sus filas se descartan del ResultSet sin escribirse (sigue en `_000N+1`).
  Si la ultima parte existente tiene menos de `rowsPerFile` filas de datos
  era el final de una exportacion ya completa -> `[OMITIDA]` sin ejecutar
  el SELECT; igual si existe `<tabla>.csv`. Sin `resume`, una exportacion
  nueva borra las partes `_NNNN` viejas tras el `executeQuery` para no
  dejar archivos obsoletos mezclados. Para datos coherentes entre
  ejecuciones hace falta el `orderBy` libre (`SELECT * ... ORDER BY x`);
  sin el, Oracle no garantiza el mismo orden entre dos ejecuciones.
- Progreso con estimacion: `estimateRows` lee `NUM_ROWS` del diccionario
  (`USER_TABLES`, o `ALL_TABLES` si el nombre va cualificado) y lo notifica
  via `Progress.onTotalRows` (-1 = indeterminada: vistas, sinonimos o
  consulta personalizada). El controller muestra `~%` y filas/s y pone la
  barra determinada; nunca debe impedir la exportacion (todo try/catch).
- Formato fijo: separador `;`, cabecera con nombres de columna, `"` para valores
  con separador/comillas/saltos (comillas internas duplicadas), NULL y BLOB como
  celda vacia (BLOB con aviso), numeros con punto decimal y fechas ISO tal cual
  JDBC, charset windows-1252, fin de linea `\r\n`.
- Limite opcional: `setMaxRows(n+1)` para detectar truncado y avisar; WHERE y
  ORDER BY libres opcionales aplicados a TODAS las tablas (una tabla sin esas
  columnas falla y el lote sigue). El limite se combina con `rowsPerFile`
  (tope total repartido entre partes) y con `resume` (las filas descartadas
  cuentan para el tope).
- Consulta personalizada (`exportQuery`): SELECT/WITH validado por
  `checkedQuery` (sin ';' final, comentarios iniciales OK), vuelca a UN CSV;
  reusa el mismo nucleo `runSelect` que las tablas.
- Variables de consulta: `QueryVariables` (Java puro, inmutable) con tipos
  TEXT/NUMBER/DATE/DATETIME. `bind(sql)` cambia cada `:NOMBRE` (fuera de
  '...', q'[...]', "..." y comentarios) por `?` (DATE/DATETIME ->
  `TO_DATE(?, 'YYYY-MM-DD[ HH24:MI:SS]')`, para no perder indices) y
  `runSelect` usa `PreparedStatement` SOLO si hay variables en el SQL
  (si no, `createStatement` como siempre). Variable no definida o valor
  invalido -> SQLException -> error de esa tabla (el lote sigue). Vacio =
  NULL. Valores relativos (`HOY`, `INICIO_MES-1M`...): meses/anios se
  aplican antes del ancla, dias/semanas despues; `FIN_*` en DATETIME =
  23:59:59. `export`/`exportQuery` fijan el momento con `at(now)` una vez
  por llamada (todas las tablas usan las mismas fechas); las sobrecargas
  sin `QueryVariables` siguen existiendo. `inline(sql)` sustituye por
  literales para "Abrir en hoja SQL". Ambitos: globales en
  `PresetHistory` (una clave `var.N` por variable + `var.count`, por el
  limite de 8 KB de `Preferences`) y del preset (`ExportPreset.variables`,
  array JSON anidado; version 3); `overriddenBy` combina (gana el preset).
  `PresetBatch.run(..., globals, ...)` combina por preset. El dialogo
  valida las variables usadas antes de aceptar; editor en
  `VariablesDialog` (Swing puro, dos pestanas).
- Presets: `ExportPreset` guarda TODO el estado del dialogo (tablas, carpeta,
  WHERE, ORDER BY, limite, "filas por archivo", "no reemplazar CSV
  existentes", "reanudar partes", modo consulta, consulta, nombre de
  archivo y variables propias) en JSON via
  `JsonUtil` (serializador/parser propio: NO hay libreria JSON). Campo
  `version`; parser tolerante a claves desconocidas (las salta, incluidos
  arrays/objetos anidados). `parseObjectRaw` devuelve los compuestos como
  texto JSON, `parseArray` lee arrays de objetos planos y `JsonUtil.Raw`
  escribe un fragmento ya serializado.
- Historial de presets: `PresetHistory` (Java puro) guarda en
  `java.util.prefs` el MRU de presets cargados/guardados (maximo 10, el
  ultimo primero) y las ultimas carpetas usadas (salida de CSV y carpeta
  del lote de presets). El boton "Recientes" del dialogo despliega el MRU;
  cargar o guardar un preset lo apunta, y elegir un preset borrado ofrece
  quitarlo del historial. Si el almacen de preferencias no esta
  disponible, `history` queda a null y el boton ni se muestra.
- Deshacer: Swing no lo trae de serie. `EditPopup.install` añade un
  `UndoManager` por campo (Ctrl+Z, Ctrl+Y / Ctrl+Mayus+Z, agrupa rachas
  de <800 ms) y Deshacer/Rehacer al menu. `install(c, false)` lo omite
  (editor compartido de celdas en `VariablesDialog`).
  `EditPopup.clearHistory(c)` tras rellenar por codigo (tablas
  pre-rellenadas del navegador).
- Menu contextual de edicion: `EditPopup.install(JTextComponent)` (Swing
  puro) añade Cortar/Copiar/Pegar/Seleccionar todo con estados
  habilitado/deshabilitados segun seleccion, editabilidad y contenido del
  portapapeles. El dialogo lo instala en sus 6 campos de texto y el
  controller en las areas de resumen (solo lectura: solo Copiar y
  Seleccionar todo quedan utiles).
- "Abrir en hoja SQL" (junto al checkbox de consulta personalizada): el
  dialogo recibe un `CsvExportDialog.ScriptOpener` (null = boton oculto) y
  le pasa la consulta personalizada o un `SELECT ...;` por tabla
  (`CsvExporter.selectFor`, con WHERE/ORDER BY comunes); si abre, el dialogo
  se cierra (es modal). El controller lo implementa con
  `OpenWorksheetWizard.openNewTempWorksheet(connName, texto)` usando el
  nombre de conexion del nodo del navegador o de la hoja activa.
- Variables globales portables: las globales viven en `java.util.prefs`
  (registro de Windows, no se copian con el plugin). La pestana Globales de
  `VariablesDialog` tiene "Exportar..."/"Importar...":
  `QueryVariables.saveFile`/`loadFile` (`toFileJson`/`fromFileJson`, UTF-8,
  `{"format":"csv-download-variables","version":1,"variables":[...]}`).
  `fromFileJson` tolera BOM y acepta tambien un preset o un array suelto.
  Importar fusiona en la tabla (gana el archivo) y solo persiste al Aceptar.
- Tooltips: todo campo, checkbox y boton lleva `setToolTipText` (los campos
  en `CsvExportDialog.installFieldTips`); mantenerlo al anadir controles.
- Lote de presets: `PresetBatch` (Java puro) ejecuta los `*.json` de una
  carpeta (`listPresets` ordena por nombre, no recursivo) sobre la misma
  conexion; cada preset usa SU carpeta de salida (se crea si falta) y el que
  falla no detiene el lote. Se lanza con "Ejecutar presets de carpeta..." del
  dialogo, que devuelve un `Request` con `isPresetBatch()`. El checkbox "no
  reemplazar" del dialogo SI aplica al lote: `PresetBatch.run` recibe un
  `skipExisting` que se suma (OR) al flag propio de cada preset. Antes de cada
  preset avisa al callback (con `table` vacia) y valida la conexion con
  `connection.isValid(5)`.
- Progreso/cancelacion: `Progress.onProgress` se invoca al empezar cada tabla,
  antes de cada `executeQuery` y cada 200 filas; `Progress.onStatement` (hook
  opcional) expone el Statement en curso para que Cancelar llame a
  `st.cancel()` y corte un `executeQuery` largo/bloqueado (ORA-01013 se
  reporta como "Cancelado por el usuario"). `Progress.onPart` (hook opcional)
  avisa al abrir cada parte cuando la salida esta partida.
- En Windows un fichero abierto no se puede borrar: el CSV parcial de una tabla
  fallida/cancelada se borra DESPUES del finally que cierra el Writer (con
  salida partida solo se borra la parte en curso; las completas se conservan).

## Puntos de entrada y conexion

- Menu Tools + menu contextual del editor: conexion de la hoja activa
  (`Worksheet.getWorksheetFromContext(context).getConnection()`).
- Menu contextual `db_nav` (always-enabled): `Context.getElement()` ->
  `new DBObject(element)` da `getConnectionName()`/`getConnection()`;
  `Connections.getInstance().getConnection(name)` abre por nombre.
- Multiseleccion del navegador: `Context.getSelection()` devuelve
  `Element[]` con TODOS los nodos marcados; cada uno se envuelve en
  `DBObject` y los de tipo `TABLE`/`VIEW` (`getObjectType()`,
  `getObjectName()`, `getSchemaName()`) pre-rellenan el listado del
  dialogo (separados por comas, calificados con esquema si se conoce).
- El controller prueba primero el nodo del navegador y luego la hoja; sin
  conexion muestra aviso. La accion aparece en cualquier nodo del navegador
  (es a proposito: no hay regla fiable para filtrar nodos "con conexion"
  entre versiones).

## Sin dependencias externas

`CsvExporter`, `ExportPreset`, `JsonUtil`, `PresetBatch`,
`PresetHistory` y `QueryVariables` son Java puro (JDBC + colecciones +
io + prefs): deben seguir compilando y ejecutandose SIN los jars de SQL
Developer. `CsvExportDialog`, `VariablesDialog` y `EditPopup` son Swing
puro (el dialogo recibe el padre por parametro). Solo
`CsvDownloadController` usa APIs del IDE.
Mantener esa frontera es lo que permite probar la logica fuera del IDE.

## APIs internas de SQL Developer (fragiles entre versiones)

- Verificado contra 24.3. Ante clases `oracle.dbtools.raptor.*` usar siempre
  `try/catch (Throwable)` con fallback, como hace el controller.
- Si se usan paquetes nuevos, comprobar con `unzip -p <jar> META-INF/MANIFEST.MF`
  que el bundle que los exporta este en `Require-Bundle` de `MANIFEST.MF`
  (`oracle.dbtools.raptor.utils` lo exporta `oracle.sqldeveloper`,
  `oracle.dbtools.worksheet.editor` lo exporta `oracle.sqldeveloper.worksheet`;
  ambos ya requeridos). Compilar contra un jar no basta: OSGi solo ve paquetes
  exportados.

## extension.xml (romperlo rompe TODA la extension)

- `<rules>` es hijo de `<trigger-hooks>`, ANTES de `<triggers>` (son hermanos).
  Este plugin no usa rules propias: los dos context-menu-hook van con
  `always-enabled`.
- Sitios de menu contextual: `editor` (worksheet) y `db_nav` (navegador de
  conexiones). Para filtrar por tipo de objeto existe el rule-type
  `oracle.dbtools.raptor.utils.ObjectTypeRuleFunction` + param `ObjectType`.
- Cada accion necesita: `<action>` + `<controller>` con `update-rule` + item de
  menu/hook. Validar el XML completo tras editarlo; un error de sintaxis impide
  cargar el plugin entero.

## Concurrencia (EDT)

- Ninguna consulta JDBC ni escritura de ficheros en el hilo de interfaz: la
  exportacion va en `SwingWorker` con dialogo modal de progreso y boton
  Cancelar (AtomicBoolean consultado por el callback `Progress` + el hook
  `onStatement` guarda el Statement vivo en un AtomicReference para que
  Cancelar llame a `st.cancel()`). El callback corre en el hilo del worker:
  actualizar la UI via `publish`/`process` o `SwingUtilities.invokeLater`.
- `CsvExportDialog` es modal; devuelve la peticion validada o null.

## Generacion CSV (correctitud primero)

- Nunca truncar un valor para que "quepa": el CSV no tiene limite de literal.
- BLOB y tipos sin representacion de texto razonable -> celda vacia + aviso
  (mejor perder el dato con aviso que generar un CSV corrupto).
- RAW -> hexadecimal. TIMESTAMP conserva nanos (`yyyy-MM-dd HH:mm:ss.fffffffff`).

## Estilo

- Java 11 (`--release 11`), sin librerias de terceros, `StringBuilder` para
  concatenar, listeners anonimos, `try/finally` para recursos JDBC/IO.
  Comentarios de clase/metodo en espanol como el resto.
