package com.csv.download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializador/parser JSON minimo hecho a mano (no hay libreria JSON en el
 * proyecto). Soporta lo que necesitan los presets: un objeto plano con
 * valores de tipo string, numero o booleano, y arrays de objetos planos
 * (las variables de consulta, ver {@link QueryVariables}).
 *
 * El parser es tolerante con claves desconocidas (las ignora quien lee el
 * mapa) y lanza {@link IllegalArgumentException} con un mensaje claro si el
 * JSON esta mal formado.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * Fragmento JSON ya serializado (p.ej. un array): {@link #objectToJson}
     * lo escribe tal cual en vez de entrecomillarlo.
     */
    public static final class Raw {

        private final String json;

        public Raw(String json) {
            this.json = json;
        }

        @Override
        public String toString() {
            return json;
        }
    }

    /** Serializa un array de objetos planos [{...},{...}] en una sola linea. */
    public static String arrayToJson(List<? extends Map<String, ?>> items) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(objectToJson(items.get(i)));
        }
        return json.append(']').toString();
    }

    /** Serializa un objeto plano {"clave":valor,...} en una sola linea. */
    public static String objectToJson(Map<String, ?> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':')
                    .append(valueToJson(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean
                || value instanceof Raw) {
            return value.toString();
        }
        return quote(value.toString());
    }

    /** Cadena JSON entrecomillada con los escapes habituales. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Parsea un objeto JSON plano a un mapa clave -> valor. Los strings
     * llegan ya decodificados; numeros y booleanos llegan como su literal
     * ("123", "true"). Null llega como null. Arrays y objetos anidados se
     * saltan (se ignoran con tolerancia).
     */
    public static Map<String, String> parseObject(String json) {
        return new Parser(json, false).object();
    }

    /**
     * Como {@link #parseObject(String)}, pero los arrays y objetos
     * anidados llegan como su texto JSON sin interpretar (para pasarlos a
     * {@link #parseArray(String)}).
     */
    public static Map<String, String> parseObjectRaw(String json) {
        return new Parser(json, true).object();
    }

    /**
     * Parsea un array de objetos planos [{...},...]; cada objeto llega como
     * en {@link #parseObject(String)}. Null o texto vacio = lista vacia.
     */
    public static List<Map<String, String>> parseArray(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<Map<String, String>>();
        }
        return new Parser(json, false).array();
    }

    private static final class Parser {

        private final String text;
        private final boolean keepCompound;
        private int pos;

        Parser(String text, boolean keepCompound) {
            this.text = text == null ? "" : text;
            this.keepCompound = keepCompound;
        }

        List<Map<String, String>> array() {
            List<Map<String, String>> items =
                    new ArrayList<Map<String, String>>();
            skipWhitespace();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    items.add(objectBody());
                    skipWhitespace();
                    char c = peek();
                    pos++;
                    if (c == ']') {
                        break;
                    }
                    if (c != ',') {
                        pos--;
                        fail("se esperaba ',' o ']'");
                    }
                }
            }
            skipWhitespace();
            if (pos != text.length()) {
                fail("sobra texto tras el array");
            }
            return items;
        }

        Map<String, String> object() {
            Map<String, String> map = objectBody();
            skipWhitespace();
            if (pos != text.length()) {
                fail("sobra texto tras el objeto");
            }
            return map;
        }

        private Map<String, String> objectBody() {
            Map<String, String> map = new LinkedHashMap<String, String>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (pos < text.length()) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
                fail("se esperaba ',' o '}'");
            }
            return map;
        }

        private String value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (c == '{' || c == '[') {
                int start = pos;
                skipCompound(c);
                return keepCompound ? text.substring(start, pos) : null;
            }
            return literal();
        }

        /** Literal sin comillas: numero, true, false o null. */
        private String literal() {
            int start = pos;
            while (pos < text.length() && ",}] \t\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            if (token.isEmpty()) {
                fail("se esperaba un valor");
            }
            return "null".equals(token) ? null : token;
        }

        /** Salta un objeto o array completo respetando strings anidados. */
        private void skipCompound(char open) {
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '"') {
                    string(); // consume el string completo
                    continue;
                }
                pos++;
                if (c == open) {
                    depth++;
                } else if (c == close && --depth == 0) {
                    return;
                }
            }
            fail("valor compuesto sin cerrar");
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        break;
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                fail("escape \\u incompleto");
                            }
                            out.append((char) Integer.parseInt(
                                    text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            fail("escape desconocido \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            fail("string sin cerrar");
            return null; // inalcanzable
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void expect(char expected) {
            if (peek() != expected) {
                fail("se esperaba '" + expected + "'");
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < text.length()
                    && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private void fail(String detail) {
            throw new IllegalArgumentException(
                    "JSON invalido: " + detail + " (posicion " + pos + ")");
        }
    }
}
