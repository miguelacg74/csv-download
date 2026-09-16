package com.csv.download;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializador/parser JSON minimo hecho a mano (no hay libreria JSON en el
 * proyecto). Soporta lo que necesitan los presets: un objeto plano con
 * valores de tipo string, numero o booleano.
 *
 * El parser es tolerante con claves desconocidas (las ignora quien lee el
 * mapa) y lanza {@link IllegalArgumentException} con un mensaje claro si el
 * JSON esta mal formado.
 */
public final class JsonUtil {

    private JsonUtil() {
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
        if (value instanceof Number || value instanceof Boolean) {
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
        return new Parser(json).object();
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Map<String, String> object() {
            Map<String, String> map = new LinkedHashMap<String, String>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
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
            skipWhitespace();
            if (pos != text.length()) {
                fail("sobra texto tras el objeto");
            }
            return map;
        }

        private String value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (c == '{' || c == '[') {
                skipCompound(c);
                return null;
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
