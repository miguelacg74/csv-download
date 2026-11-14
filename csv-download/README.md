# Descarga de tablas a CSV para Oracle SQL Developer 24.3

Extension (plugin) para SQL Developer clasico que exporta los datos de un
listado de tablas a archivos CSV individuales (un archivo por tabla).

Probado en Oracle SQL Developer 24.3.1.347.1826.

Version actual: **1.1.0**

## Que hace

- Pegas o escribes un **listado de tablas separadas por comas** (tambien
  admite `;` y saltos de linea), con o sin esquema (`EMP`, `HR.EMP`,
  `"Mi Tabla"`). Sin comillas da igual mayusculas o minusculas (`table1`
  = `TABLE1`, como en Oracle); usa comillas solo para tablas creadas con
  minusculas o espacios (`"miTabla"`).
- Eliges la **carpeta de salida**; cada tabla genera un `<tabla>.csv`.
- Opcionalmente aplicas a todas las tablas un **WHERE libre** y un
  **limite de filas** (0 = sin limite).
- Marca **"No reemplazar los CSV que ya existen"** para omitir las tablas
  cuyo `<tabla>.csv` ya este en la carpeta de salida: no se consultan ni
  se tocan los archivos previos y en el resumen salen como `[OMITIDA]`.
  Sirve para reanudar una descarga cancelada o con fallos sin repetir el
  trabajo ya hecho. En el lote de presets, el checkbox fuerza la omision
  en TODOS los presets ejecutados (ademas del flag guardado en cada JSON).
- Alternativa puntual: activa **"Ejecutar consulta personalizada"**, pega tu
  `SELECT`/`WITH` y descarga su resultado a un unico CSV (el listado y el
  WHERE se ignoran; el limite sigue aplicando).
- Todo el estado del dialogo se puede **guardar/recargar como preset JSON**
  (botones *Guardar preset...* / *Cargar preset...*). El boton
  **Recientes** despliega un historial con los ultimos presets usados
  para cargarlos en dos clics, sin volver a buscar la ruta; ademas se
  recuerdan las ultimas carpetas usadas (salida de CSV y lote de
  presets) como punto de partida de los selectores.
- **Ejecucion en lote de presets**: *Ejecutar presets de carpeta...* corre
  todos los `*.json` de una carpeta (de tablas o de consulta, se mezclan
  libremente) sobre la misma conexion; cada preset vuelca a su propia
  carpeta de salida y el que falla no detiene el lote.
- **Variables de consulta** (opcional): el boton *Variables...* define
  variables de tipo texto, numero, fecha o fecha-hora que se usan como
  `:NOMBRE` en el WHERE, el ORDER BY o la consulta personalizada
  (`FECHA >= :DESDE AND ESTADO = :ESTADO`). Se envian como parametros
  JDBC con su tipo (sin inyeccion SQL ni dependencia del NLS; las fechas
  van en `TO_DATE(?, ...)` para no perder indices). Hay dos ambitos:
  **globales** (se guardan en tus preferencias y aplican a todo, incluido
  el lote de presets) y **del preset** (se guardan en el JSON y
  sobrescriben a la global del mismo nombre). Las fechas admiten valores
  relativos que se calculan al exportar: `HOY`, `AYER`, `MANANA`,
  `AHORA`, `INICIO_SEMANA`/`FIN_SEMANA`, `INICIO_MES`/`FIN_MES`,
  `INICIO_TRIMESTRE`/`FIN_TRIMESTRE`, `INICIO_ANIO`/`FIN_ANIO`, con
  desplazamientos `+N`/`-N` en `D` (dias), `S` (semanas), `M` (meses) o
  `A` (anios). Ej.: `INICIO_MES-1M` y `FIN_MES-1M` = mes anterior
  completo. Valor vacio = NULL. *Abrir en hoja SQL* sustituye las
  variables por literales.
- **Version y actualizaciones**: en la esquina superior derecha del
  dialogo se ve la version instalada (`v1.1.0 (al dia)` o
  `v1.1.0 - disponible vX.Y.Z`), un icono de descarga y el logo de
  GitHub. Al abrir el dialogo se consulta en segundo plano la ultima
  release publicada en GitHub (sin bloquear nada; el resultado se
  reutiliza 30 minutos). El icono de descarga vuelve a comprobar o, si
  hay version nueva, explica como actualizar y descarga el JAR; el logo
  abre el repositorio; el texto de la version abre "Acerca de".
- La exportacion corre en segundo plano con progreso y boton de cancelar;
  una tabla que falla no detiene el lote (queda en el resumen con su error).
  Cancelar tambien interrumpe la consulta en curso (no hay que esperar a que
  termine).

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
  tabla o cualquier nodo de ella): usa la conexion de ese nodo. Si hay
  varias tablas/vistas seleccionadas (Ctrl/clic o Mayus/clic), el listado
  del dialogo se pre-rellena con sus nombres separados por comas
  (calificados con esquema); se puede editar antes de descargar.

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

## Publicar una version

```bat
cd <carpeta del repo>\csv-download
release.bat 1.2.0 "notas de la version"
```

Requiere `git`, GitHub CLI (`gh auth login`) y `SQLDEV_HOME`. Pone el
numero en `MANIFEST.MF`, `UpdateChecker.java` y este README, compila,
crea el tag `v1.2.0`, hace push y publica la Release con
`com.csv.download.jar` adjunto (siempre con ese nombre, para que funcione
el enlace fijo `releases/latest/download/com.csv.download.jar`).

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
- Las variables solo sustituyen valores (no nombres de tabla/columna) y
  una variable es un unico valor: no sirve para listas `IN (:LISTA)`.
  Los valores relativos usan la fecha/hora del equipo, no `SYSDATE`.

- El aviso de version nueva usa la API anonima de GitHub: mientras el
  repositorio sea **privado** (o no tenga releases) la consulta no ve
  nada y el dialogo solo muestra `v1.1.0` (sin "al dia"); el logo de
  GitHub abre el repositorio solo si tu navegador tiene sesion con
  acceso. Sin internet o con proxy que bloquee GitHub se muestra la
  version sin aviso (el tooltip del icono indica el error).

## Historial de versiones

- **1.1.0**: version instalada, aviso de version nueva, descarga del JAR
  y enlace al repositorio de GitHub en la esquina superior derecha del
  dialogo (con "Acerca de"); script `release.bat` para publicar
  versiones en GitHub Releases.
- **1.0.0**: version inicial (exportacion por listado y consulta
  personalizada, presets, lote de presets, variables, reanudacion y
  salida partida).

## Estructura

```
src/com/csv/download/
  CsvExporter.java            nucleo JDBC + escritura CSV (Java puro)
  CsvExportDialog.java        dialogo Swing de entrada (tablas, carpeta, WHERE,
                              limite, consulta personalizada, presets)
  CsvDownloadController.java  accion del IDE (Tools, editor y navegador)
  ExportPreset.java           estado del dialogo <-> JSON (save/load)
  JsonUtil.java               serializador/parser JSON propio (sin librerias)
  PresetBatch.java            ejecucion en lote de presets de una carpeta
  PresetHistory.java          historial MRU de presets, ultimas carpetas y
                              variables globales (java.util.prefs)
  QueryVariables.java         variables :NOMBRE: valores (relativos),
                              parser SQL, enlace JDBC (Java puro)
  VariablesDialog.java        editor Swing de variables (globales/preset)
  UpdateChecker.java          ultima release de GitHub y comparacion de
                              versiones (Java puro)
  UpdateBar.java              version, aviso de actualizacion y enlace a
                              GitHub (esquina superior derecha, Swing)
META-INF/MANIFEST.MF       bundle OSGi
META-INF/extension.xml     descriptor de la extension (accion + menus)
test/SelfTest.java         prueba manual del exportador y los presets
release.bat / release.ps1  publica una version en GitHub Releases
```
