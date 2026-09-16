# Descarga de tablas a CSV para Oracle SQL Developer 24.3

Extension (plugin) para SQL Developer clasico que exporta los datos de un
listado de tablas a archivos CSV individuales (un archivo por tabla).

Probado en Oracle SQL Developer 24.3.1.347.1826.

## Que hace

- Pegas o escribes un **listado de tablas separadas por comas** (tambien
  admite `;` y saltos de linea), con o sin esquema (`EMP`, `HR.EMP`,
  `"Mi Tabla"`).
- Eliges la **carpeta de salida**; cada tabla genera un `<tabla>.csv`.
- Opcionalmente aplicas a todas las tablas un **WHERE libre** y un
  **limite de filas** (0 = sin limite).
- Alternativa puntual: activa **"Ejecutar consulta personalizada"**, pega tu
  `SELECT`/`WITH` y descarga su resultado a un unico CSV (el listado y el
  WHERE se ignoran; el limite sigue aplicando).
- Todo el estado del dialogo se puede **guardar/recargar como preset JSON**
  (botones *Guardar preset...* / *Cargar preset...*).
- La exportacion corre en segundo plano con progreso y boton de cancelar;
  una tabla que falla no detiene el lote (queda en el resumen con su error).

## Formato del CSV

- Separador de columnas `;`, primera linea con los nombres de columna.
- Valores con `;`, comillas o saltos de linea entrecomillados con `"`
  (las comillas internas se duplican).
- `NULL` como celda vacia; `BLOB` no se exporta (celda vacia + aviso).
- Numeros con punto decimal y fechas en ISO (`2024-01-31 14:30:00`), tal
  cual los devuelve el driver JDBC.
- Codificacion **windows-1252**.

## Uso

- **Tools -> Descargar tablas a CSV...** o clic derecho en el editor de la
  hoja de trabajo: usa la conexion de la hoja activa.
- **Clic derecho en el navegador de conexiones** (sobre la conexion, una
  tabla o cualquier nodo de ella): usa la conexion de ese nodo.

## Compilar e instalar (Windows)

```bat
cd C:\Users\compr\Documents\proyectos\plugin\csv-download\csv-download
build.bat
copy /y dist\com.csv.download.jar C:\app\sqldeveloper\dropins\
```

Reinicia SQL Developer. `build.bat` asume `SQLDEV_HOME=C:\app\sqldeveloper` y
el JDK en `%SQLDEV_HOME%\jdk\jre\bin`; cambia esas variables si tu instalacion
esta en otra ruta.

El JAR es un bundle OSGi y **debe ir en `dropins\`**, no en
`sqldeveloper\extensions\` (esa carpeta solo carga los bundles registrados en
`configuration\bundles.info`).

En Linux/macOS/Git Bash: `SQLDEV_HOME=/ruta/sqldeveloper ./build.sh`.

## Prueba sin SQL Developer

```bash
javac -d out -cp build test/SelfTest.java && java -cp "out;build" SelfTest
```

Comprueba el troceado del listado, el escapeo CSV y la exportacion completa
contra una conexion JDBC falsa (Proxy), sin base de datos. En Windows usa `;`
como separador del classpath.

## Limitaciones actuales

- El WHERE libre se aplica igual a todas las tablas: si alguna no tiene las
  columnas de la condicion, esa tabla falla (queda en el resumen) y el resto
  sigue. Para filtros distintos por tabla usa la consulta personalizada.
- La consulta personalizada solo admite `SELECT`/`WITH` (se valida antes de
  ejecutar) y genera un solo CSV.
- Los `BLOB` no se exportan (celda vacia + aviso); los `RAW` van en hex.
- No hay seleccion de columnas en el modo listado: se exporta la tabla
  completa (`SELECT *`).

## Estructura

```
src/com/csv/download/
  CsvExporter.java            nucleo JDBC + escritura CSV (Java puro)
  CsvExportDialog.java        dialogo Swing de entrada (tablas, carpeta, WHERE,
                              limite, consulta personalizada, presets)
  CsvDownloadController.java  accion del IDE (Tools, editor y navegador)
  ExportPreset.java           estado del dialogo <-> JSON (save/load)
  JsonUtil.java               serializador/parser JSON propio (sin librerias)
META-INF/MANIFEST.MF       bundle OSGi
META-INF/extension.xml     descriptor de la extension (accion + menus)
test/SelfTest.java         prueba manual del exportador y los presets
```
