package meshconsole.analysis;

import java.util.*;

/** Minimal JSON parser (objects → Map, arrays → List, numbers → Double, null → null). No dependencies. */
public final class Json {
    private final String s; private int i;
    private Json(String s) { this.s = s; }

    public static Object parse(String text) { Json j = new Json(text); j.ws(); Object v = j.value(); return v; }

    private Object value() {
        ws();
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("bad json at " + i);
        return Double.parseDouble(s.substring(st, i));
    }
    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws(); String k = string(); ws(); i++; // ':'
            m.put(k, value()); ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }
    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(value()); ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
        }
    }
    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else b.append(c);
        }
    }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    public static double num(Object o, double dflt) {
        if (o instanceof Double d) return d;
        if (o instanceof String str) { try { return Double.parseDouble(str.replace("+", "")); } catch (NumberFormatException e) { return dflt; } }
        return dflt;
    }
    public static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
