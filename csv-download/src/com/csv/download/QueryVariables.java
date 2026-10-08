package com.csv.download;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variables de consulta definidas por el usuario (texto, numero, fecha o
 * fecha-hora) que se usan como {@code :NOMBRE} en el WHERE, el ORDER BY o
 * la consulta personalizada.
 *
 * Cada {@code :NOMBRE} fuera de literales, comentarios e identificadores
 * entre comillas se sustituye por un parametro JDBC ({@code ?}) y su valor
 * se enlaza con el tipo correcto: no hay inyeccion SQL ni dependencia del
 * NLS de la sesion. Las fechas se envian como texto ISO dentro de
 * {@code TO_DATE(?, '...')} para que Oracle compare DATE contra DATE y no
 * pierda los indices (enlazar un Timestamp contra una columna DATE la
 * convierte a TIMESTAMP).
 *
 * Valores de fecha: literal ISO ({@code 2026-01-31}, {@code 2026-01-31
 * 13:45[:00]}) o relativo: {@code HOY}, {@code AYER}, {@code MANANA},
 * {@code AHORA}, {@code INICIO_SEMANA}, {@code FIN_SEMANA},
 * {@code INICIO_MES}, {@code FIN_MES}, {@code INICIO_TRIMESTRE},
 * {@code FIN_TRIMESTRE}, {@code INICIO_ANIO}, {@code FIN_ANIO}, con
 * desplazamientos opcionales {@code +N}/{@code -N} y unidad {@code D}
 * (dias, por defecto), {@code S} (semanas), {@code M} (meses) o
 * {@code A} (anios). Los meses/anios se aplican ANTES del ancla y los
 * dias/semanas DESPUES: {@code FIN_MES-1M} es siempre el ultimo dia del
 * mes anterior e {@code INICIO_MES-1D} el ultimo dia del mes anterior.
 * En fecha-hora los {@code FIN_*} valen 23:59:59 y el resto 00:00:00
 * (salvo {@code AHORA}). Valor vacio = NULL.
 *
 * Inmutable. Java puro: se puede probar fuera de SQL Developer.
 */
public final class QueryVariables {

    /** Sin variables. */
    public static final QueryVariables EMPTY =
            new QueryVariables(Collections.<Variable>emptyList());

    private static final Pattern NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");
    private static final Pattern RELATIVE =
            Pattern.compile("([A-Z_]+)((?:[+-]\\d+[DSMA]?)*)");
    private static final Pattern OFFSET =
            Pattern.compile("([+-])(\\d+)([DSMA]?)");
    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter ISO_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    /** Valores relativos admitidos en las variables de fecha. */
    public static final String RELATIVE_HELP = "HOY, AYER, MANANA, AHORA,"
            + " INICIO_SEMANA, FIN_SEMANA, INICIO_MES, FIN_MES,"
            + " INICIO_TRIMESTRE, FIN_TRIMESTRE, INICIO_ANIO, FIN_ANIO"
            + " (+N/-N con unidad D, S, M o A; p.ej. INICIO_MES-1M)";

    /** Tipo de una variable: decide como se valida y se enlaza. */
    public enum Type {
        TEXT("Texto"), NUMBER("Numero"), DATE("Fecha"),
        DATETIME("Fecha-hora");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        /** Nombre para mostrar en la interfaz. */
        @Override
        public String toString() {
            return label;
        }

        /** Tipo por nombre de constante (JSON); TEXT si no se reconoce. */
        public static Type parse(String raw) {
            for (Type type : values()) {
                if (type.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) {
                    return type;
                }
            }
            return TEXT;
        }
    }

    /** Una variable: nombre (en mayusculas), tipo, valor y descripcion. */
    public static final class Variable {

        private final String name;
        private final Type type;
        private final String value;
        private final String description;

        public Variable(String name, Type type, String value,
                        String description) {
            this.name = name == null ? "" : name.trim().toUpperCase();
            this.type = type == null ? Type.TEXT : type;
            this.value = value == null ? "" : value;
            this.description = description == null ? "" : description;
        }

        public String getName() {
            return name;
        }

        public Type getType() {
            return type;
        }

        /** Valor tal cual lo escribio el usuario (puede ser relativo). */
        public String getValue() {
            return value;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Consulta lista para JDBC: el SQL con {@code ?} en lugar de cada
     * {@code :NOMBRE} y los valores a enlazar en orden.
     */
    public static final class Bound {

        private final String sql;
        private final List<Type> types;
        private final List<Object> values;

        private Bound(String sql, List<Type> types, List<Object> values) {
            this.sql = sql;
            this.types = types;
            this.values = values;
        }

        public String getSql() {
            return sql;
        }

        /** Valores en orden: String, BigDecimal o null. */
        public List<Object> getValues() {
            return values;
        }

        /** True si el SQL tiene variables (hace falta PreparedStatement). */
        public boolean hasParameters() {
            return !values.isEmpty();
        }

        /** Enlaza los valores en el PreparedStatement creado con getSql(). */
        public void apply(PreparedStatement ps) throws SQLException {
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                if (value == null) {
                    ps.setNull(i + 1, types.get(i) == Type.NUMBER
                            ? Types.NUMERIC : Types.VARCHAR);
                } else if (value instanceof BigDecimal) {
                    ps.setBigDecimal(i + 1, (BigDecimal) value);
                } else {
                    ps.setString(i + 1, value.toString());
                }
            }
        }
    }

    private final List<Variable> variables;
    /** Momento fijo para los valores relativos; null = el reloj actual. */
    private final LocalDateTime now;

    /**
     * Crea el conjunto; si un nombre se repite gana la ultima definicion.
     * Las variables sin nombre se descartan.
     */
    public QueryVariables(List<Variable> list) {
        this(list, null);
    }

    private QueryVariables(List<Variable> list, LocalDateTime now) {
        Map<String, Variable> byName = new LinkedHashMap<String, Variable>();
        for (Variable variable : list) {
            if (!variable.getName().isEmpty()) {
                byName.remove(variable.getName());
                byName.put(variable.getName(), variable);
            }
        }
        this.variables = Collections.unmodifiableList(
                new ArrayList<Variable>(byName.values()));
        this.now = now;
    }

    public List<Variable> getVariables() {
        return variables;
    }

    public boolean isEmpty() {
        return variables.isEmpty();
    }

    /** Variable por nombre (sin distinguir mayusculas); null si no existe. */
    public Variable get(String name) {
        for (Variable variable : variables) {
            if (variable.getName().equalsIgnoreCase(name)) {
                return variable;
            }
        }
        return null;
    }

    /**
     * Estas variables sobrescritas por {@code other}: las de mismo nombre
     * toman la definicion de {@code other} y las nuevas se anaden al final.
     * Es como se combinan las globales con las de un preset.
     */
    public QueryVariables overriddenBy(QueryVariables other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        Map<String, Variable> merged = new LinkedHashMap<String, Variable>();
        for (Variable variable : variables) {
            merged.put(variable.getName(), variable);
        }
        for (Variable variable : other.variables) {
            merged.put(variable.getName(), variable);
        }
        return new QueryVariables(new ArrayList<Variable>(merged.values()),
                now);
    }

    /**
     * Copia que resuelve los valores relativos siempre contra {@code at}:
     * una exportacion fija el momento al empezar para que todas sus tablas
     * usen las mismas fechas.
     */
    public QueryVariables at(LocalDateTime at) {
        return new QueryVariables(variables, at);
    }

    /** True si el nombre es valido para una variable ({@code :NOMBRE}). */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name.trim()).matches();
    }

    // ------------------------------------------------------------ valores

    /**
     * Valor listo para enlazar: String (texto, o fecha en ISO), BigDecimal
     * (numero) o null (valor vacio).
     *
     * @throws IllegalArgumentException con un mensaje para el usuario si el
     *         valor no es valido para el tipo
     */
    public static Object resolve(Type type, String raw, LocalDateTime now) {
        String value = raw == null ? "" : raw.trim();
        if (type == Type.TEXT) {
            return raw == null || raw.isEmpty() ? null : raw;
        }
        if (value.isEmpty()) {
            return null;
        }
        if (type == Type.NUMBER) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + value
                        + "' no es un numero (usa punto decimal, sin"
                        + " separador de miles).");
            }
        }
        LocalDateTime when = Character.isDigit(value.charAt(0))
                ? literalDate(value, type)
                : relativeDate(value, type == Type.DATETIME,
                        now == null ? LocalDateTime.now() : now);
        return type == Type.DATE ? when.toLocalDate().format(ISO_DATE)
                : when.format(ISO_DATE_TIME);
    }

    /**
     * Texto del valor resuelto para mostrarlo en el dialogo (p.ej.
     * "2026-09-01" para INICIO_MES-1M), "NULL" si esta vacio o
     * "error: ..." si no es valido.
     */
    public static String preview(Type type, String raw, LocalDateTime now) {
        try {
            Object value = resolve(type, raw, now);
            return value == null ? "NULL" : value.toString();
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    private static LocalDateTime literalDate(String value, Type type) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value, ISO_DATE).atStartOfDay();
            }
            if (type == Type.DATETIME) {
                String text = value.replace('T', ' ');
                if (text.length() == 16) {
                    text += ":00";
                }
                return LocalDateTime.parse(text, ISO_DATE_TIME);
            }
        } catch (DateTimeParseException e) {
            // mensaje comun abajo
        }
        throw new IllegalArgumentException("'" + value + "' no es una fecha"
                + (type == Type.DATETIME
                        ? " valida (AAAA-MM-DD o AAAA-MM-DD HH:MM[:SS])."
                        : " valida (AAAA-MM-DD)."));
    }

    private static LocalDateTime relativeDate(String value, boolean dateTime,
                                              LocalDateTime now) {
        String text = value.toUpperCase().replace(" ", "");
        Matcher m = RELATIVE.matcher(text);
        if (!m.matches()) {
            throw unknownRelative(value);
        }
        int months = 0;
        int days = 0;
        Matcher offset = OFFSET.matcher(m.group(2));
        while (offset.find()) {
            int n = Integer.parseInt(offset.group(2));
            if ("-".equals(offset.group(1))) {
                n = -n;
            }
            switch (offset.group(3)) {
                case "S": days += 7 * n; break;
                case "M": months += n; break;
                case "A": months += 12 * n; break;
                default: days += n;
            }
        }
        LocalDate anchor = now.toLocalDate().plusMonths(months);
        LocalDate date;
        LocalTime time = LocalTime.MIDNIGHT;
        switch (m.group(1)) {
            case "HOY": date = anchor; break;
            case "AYER": date = anchor.minusDays(1); break;
            case "MANANA": date = anchor.plusDays(1); break;
            case "AHORA":
                date = anchor;
                time = now.toLocalTime().withNano(0);
                break;
            case "INICIO_SEMANA":
                date = anchor.with(
                        TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                break;
            case "FIN_SEMANA":
                date = anchor.with(
                        TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                time = END_OF_DAY;
                break;
            case "INICIO_MES": date = anchor.withDayOfMonth(1); break;
            case "FIN_MES":
                date = anchor.with(TemporalAdjusters.lastDayOfMonth());
                time = END_OF_DAY;
                break;
            case "INICIO_TRIMESTRE":
                date = quarterStart(anchor);
                break;
            case "FIN_TRIMESTRE":
                date = quarterStart(anchor).plusMonths(2)
                        .with(TemporalAdjusters.lastDayOfMonth());
                time = END_OF_DAY;
                break;
            case "INICIO_ANIO": date = anchor.withDayOfYear(1); break;
            case "FIN_ANIO":
                date = anchor.with(TemporalAdjusters.lastDayOfYear());
                time = END_OF_DAY;
                break;
            default:
                throw unknownRelative(value);
        }
        return LocalDateTime.of(date.plusDays(days),
                dateTime ? time : LocalTime.MIDNIGHT);
    }

    private static LocalDate quarterStart(LocalDate date) {
        int firstMonth = (date.getMonthValue() - 1) / 3 * 3 + 1;
        return LocalDate.of(date.getYear(), firstMonth, 1);
    }

    private static IllegalArgumentException unknownRelative(String value) {
        return new IllegalArgumentException("'" + value + "' no es una fecha"
                + " ISO ni un valor relativo conocido: " + RELATIVE_HELP + ".");
    }

    // ------------------------------------------------------------- parser

    /**
     * Nombres (en mayusculas, sin repetir, en orden de aparicion) de las
     * variables {@code :NOMBRE} que usa el SQL.
     */
    public static List<String> referencedNames(String sql) {
        Set<String> names = new LinkedHashSet<String>();
        String text = sql == null ? "" : sql;
        for (int[] token : tokens(text)) {
            names.add(text.substring(token[0] + 1, token[1]).toUpperCase());
        }
        return new ArrayList<String>(names);
    }

    /**
     * Prepara el SQL para JDBC: cada {@code :NOMBRE} pasa a {@code ?}
     * ({@code TO_DATE(?, ...)} en las fechas) con su valor en
     * {@link Bound#getValues()}. Sin variables en el SQL, devuelve el SQL
     * intacto y sin parametros.
     *
     * @throws SQLException si usa una variable no definida o con un valor
     *         invalido para su tipo
     */
    public Bound bind(String sql) throws SQLException {
        List<int[]> tokens = tokens(sql);
        List<Type> types = new ArrayList<Type>();
        List<Object> values = new ArrayList<Object>();
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int[] token : tokens) {
            Variable variable = required(sql, token);
            Object value = resolved(variable);
            out.append(sql, last, token[0]);
            switch (variable.getType()) {
                case DATE: out.append("TO_DATE(?, 'YYYY-MM-DD')"); break;
                case DATETIME:
                    out.append("TO_DATE(?, 'YYYY-MM-DD HH24:MI:SS')");
                    break;
                default: out.append('?');
            }
            types.add(variable.getType());
            values.add(value);
            last = token[1];
        }
        out.append(sql.substring(last));
        return new Bound(tokens.isEmpty() ? sql : out.toString(), types,
                values);
    }

    /**
     * SQL con las variables definidas sustituidas por literales Oracle
     * (para abrirlo en una hoja de trabajo y ejecutarlo tal cual). Las
     * variables no definidas se dejan como {@code :NOMBRE} (la hoja pedira
     * su valor).
     *
     * @throws SQLException si alguna variable tiene un valor invalido
     */
    public String inline(String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (int[] token : tokens(sql)) {
            Variable variable = get(sql.substring(token[0] + 1, token[1]));
            if (variable == null) {
                continue;
            }
            Object value = resolved(variable);
            out.append(sql, last, token[0]);
            if (value == null) {
                out.append("NULL");
            } else if (value instanceof BigDecimal) {
                out.append(((BigDecimal) value).toPlainString());
            } else {
                String literal = "'" + value.toString().replace("'", "''")
                        + "'";
                switch (variable.getType()) {
                    case DATE:
                        out.append("TO_DATE(").append(literal)
                                .append(", 'YYYY-MM-DD')");
                        break;
                    case DATETIME:
                        out.append("TO_DATE(").append(literal)
                                .append(", 'YYYY-MM-DD HH24:MI:SS')");
                        break;
                    default: out.append(literal);
                }
            }
            last = token[1];
        }
        return out.append(sql.substring(last)).toString();
    }

    private Variable required(String sql, int[] token) throws SQLException {
        String name = sql.substring(token[0] + 1, token[1]);
        Variable variable = get(name);
        if (variable == null) {
            throw new SQLException("La variable :" + name.toUpperCase()
                    + " no esta definida (boton 'Variables...').");
        }
        return variable;
    }

    private Object resolved(Variable variable) throws SQLException {
        try {
            return resolve(variable.getType(), variable.getValue(), now);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Variable :" + variable.getName() + ": "
                    + e.getMessage());
        }
    }

    /**
     * Posiciones [inicio, fin) de cada {@code :NOMBRE} del SQL, saltando
     * literales ('...', q'[...]'), identificadores entre comillas dobles y
     * comentarios (-- y /* *&#47;): asi {@code 'HH24:MI'} no es una variable.
     */
    private static List<int[]> tokens(String sql) {
        List<int[]> tokens = new ArrayList<int[]>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if ((c == 'q' || c == 'Q') && next == '\''
                    && i + 2 < n && startsLiteralPrefix(sql, i)) {
                char open = sql.charAt(i + 2);
                int idx = "([{<".indexOf(open);
                char close = idx < 0 ? open : ")]}>".charAt(idx);
                int end = sql.indexOf(close + "'", i + 3);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++;
            } else if (c == '"') {
                int end = sql.indexOf('"', i + 1);
                i = end < 0 ? n : end + 1;
            } else if (c == ':' && isAsciiLetter(next)
                    && (i == 0 || sql.charAt(i - 1) != ':')) {
                int j = i + 1;
                while (j < n && isNameChar(sql.charAt(j))) {
                    j++;
                }
                tokens.add(new int[]{i, j});
                i = j;
            } else {
                i++;
            }
        }
        return tokens;
    }

    /** La 'q' de q'..' no forma parte de un identificador (admite nq'..'). */
    private static boolean startsLiteralPrefix(String sql, int i) {
        if (i == 0 || !isNameChar(sql.charAt(i - 1))) {
            return true;
        }
        char prev = sql.charAt(i - 1);
        return (prev == 'n' || prev == 'N')
                && (i == 1 || !isNameChar(sql.charAt(i - 2)));
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isNameChar(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9')
                || c == '_' || c == '$' || c == '#';
    }

    // --------------------------------------------------------------- JSON

    /** Variables como array JSON [{name,type,value,description},...]. */
    public String toJson() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Variable variable : variables) {
            items.add(toMap(variable));
        }
        return JsonUtil.arrayToJson(items);
    }

    /** Una variable como objeto JSON plano. */
    public static String toJson(Variable variable) {
        return JsonUtil.objectToJson(toMap(variable));
    }

    private static Map<String, Object> toMap(Variable variable) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", variable.getName());
        item.put("type", variable.getType().name());
        item.put("value", variable.getValue());
        item.put("description", variable.getDescription());
        return item;
    }

    /** Reconstruye las variables desde {@link #toJson()}; null = vacias. */
    public static QueryVariables fromJson(String json) {
        List<Variable> list = new ArrayList<Variable>();
        for (Map<String, String> item : JsonUtil.parseArray(json)) {
            list.add(fromMap(item));
        }
        return list.isEmpty() ? EMPTY : new QueryVariables(list);
    }

    /** Una variable desde un objeto JSON plano ({@link #toJson(Variable)}). */
    public static Variable variableFromJson(String json) {
        return fromMap(JsonUtil.parseObject(json));
    }

    private static Variable fromMap(Map<String, String> item) {
        return new Variable(item.get("name"), Type.parse(item.get("type")),
                item.get("value"), item.get("description"));
    }
}
