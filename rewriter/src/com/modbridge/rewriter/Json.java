package com.modbridge.rewriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/writer for the mixin resource passes (refmaps, mixin configs).
 * Deliberately dependency-free and order-preserving: objects map to LinkedHashMap,
 * arrays to ArrayList, numbers to Long/Double. Written output is pretty-printed with
 * 2-space indent; only structural fidelity matters (Mixin parses it with gson).
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------ parse

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < s.length()) throw new JsonError("trailing content at " + p.i);
        return v;
    }

    @SuppressWarnings("serial")
    public static class JsonError extends RuntimeException {
        public JsonError(String msg) { super(msg); }
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        Object value() {
            if (i >= s.length()) throw new JsonError("unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new JsonError("expected key at " + i);
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new JsonError("expected ':' at " + i);
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw new JsonError("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new JsonError("expected ',' or '}' at " + (i - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw new JsonError("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new JsonError("expected ',' or ']' at " + (i - 1));
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new JsonError("bad unicode escape at " + i);
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new JsonError("bad escape '\\" + e + "' at " + i);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonError("unterminated string");
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new JsonError("unexpected char at " + i);
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try {
                    return Long.parseLong(n);
                } catch (NumberFormatException ignored) {
                    // fall through to double for out-of-range integers
                }
            }
            return Double.parseDouble(n);
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw new JsonError("expected '" + word + "' at " + i);
            i += word.length();
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    // ------------------------------------------------------------ write

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(((Number) v).doubleValue());
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writeValue(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeValue(sb, o, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            throw new JsonError("cannot write value of " + v.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }
}
