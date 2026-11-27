// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stringified NBT as the server writes and reads it ({@code /data}, item components): parsed into
 * plain Java values (compound {@code Map<String, Object>}, list {@code List<Object>}, {@code byte[]},
 * {@code int[]}, {@code long[]}, {@code String}, boxed numbers, {@code Boolean}) and written back.
 */
public final class Snbt {
    private final String s;
    private int i;

    private Snbt(String s) {
        this.s = s;
    }

    /** Parses SNBT; throws {@link IllegalArgumentException} for malformed text. */
    public static Object parse(String text) {
        Snbt p = new Snbt(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("trailing text");
        }
        return v;
    }

    /** Parses an SNBT compound, an empty map for empty or malformed text. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return parse(text) instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (IllegalArgumentException e) {
            return new LinkedHashMap<>();
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("SNBT: " + what + " at " + i + " in " + (s.length() > 200 ? s.substring(0, 200) + "..." : s));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("value expected");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return compoundValue();
        }
        if (c == '[') {
            return listValue();
        }
        if (c == '"' || c == '\'') {
            return quoted();
        }
        return scalar(bare());
    }

    private Map<String, Object> compoundValue() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = peek() == '"' || peek() == '\'' ? quoted() : bare();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw error("',' or '}' expected");
            }
        }
    }

    private Object listValue() {
        i++;
        ws();
        if (i + 1 < s.length() && s.charAt(i + 1) == ';' && "BIL".indexOf(s.charAt(i)) >= 0) {
            char type = s.charAt(i);
            i += 2;
            List<Object> items = new ArrayList<>();
            ws();
            while (peek() != ']') {
                ws();
                items.add(scalar(bare()));
                ws();
                if (peek() == ',') {
                    i++;
                }
                ws();
            }
            i++;
            int n = items.size();
            switch (type) {
                case 'B' -> {
                    byte[] a = new byte[n];
                    for (int k = 0; k < n; k++) {
                        a[k] = ((Number) items.get(k)).byteValue();
                    }
                    return a;
                }
                case 'I' -> {
                    int[] a = new int[n];
                    for (int k = 0; k < n; k++) {
                        a[k] = ((Number) items.get(k)).intValue();
                    }
                    return a;
                }
                default -> {
                    long[] a = new long[n];
                    for (int k = 0; k < n; k++) {
                        a[k] = ((Number) items.get(k)).longValue();
                    }
                    return a;
                }
            }
        }
        List<Object> list = new ArrayList<>();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("',' or ']' expected");
            }
        }
    }

    private String quoted() {
        char q = next();
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == q) {
                return b.toString();
            }
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 's' -> b.append(' ');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private String bare() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        if (start == i) {
            throw error("value expected");
        }
        return s.substring(start, i);
    }

    private static Object scalar(String t) {
        if (t.equals("true")) {
            return Boolean.TRUE;
        }
        if (t.equals("false")) {
            return Boolean.FALSE;
        }
        try {
            char last = Character.toLowerCase(t.charAt(t.length() - 1));
            String body = t.substring(0, t.length() - 1);
            String num = body.endsWith("u") || body.endsWith("U") ? body.substring(0, body.length() - 1) : body;
            switch (last) {
                case 'b':
                    return (byte) Integer.parseInt(num);
                case 's':
                    return (short) Integer.parseInt(num);
                case 'l':
                    return Long.parseLong(num);
                case 'f':
                    return Float.parseFloat(body);
                case 'd':
                    return Double.parseDouble(body);
                default:
                    break;
            }
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                return Double.parseDouble(t);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return t;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("'" + c + "' expected");
        }
    }

    // ----- Writing

    /** Writes a value as SNBT. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        switch (v) {
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) {
                        b.append(',');
                    }
                    first = false;
                    key(b, e.getKey().toString());
                    b.append(':');
                    write(b, e.getValue());
                }
                b.append('}');
            }
            case List<?> l -> {
                b.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) {
                        b.append(',');
                    }
                    write(b, l.get(k));
                }
                b.append(']');
            }
            case byte[] a -> {
                b.append("[B;");
                for (int k = 0; k < a.length; k++) {
                    b.append(k > 0 ? "," : "").append(a[k]).append('b');
                }
                b.append(']');
            }
            case int[] a -> {
                b.append("[I;");
                for (int k = 0; k < a.length; k++) {
                    b.append(k > 0 ? "," : "").append(a[k]);
                }
                b.append(']');
            }
            case long[] a -> {
                b.append("[L;");
                for (int k = 0; k < a.length; k++) {
                    b.append(k > 0 ? "," : "").append(a[k]).append('L');
                }
                b.append(']');
            }
            case String str -> quote(b, str);
            case Byte n -> b.append(n).append('b');
            case Short n -> b.append(n).append('s');
            case Long n -> b.append(n).append('L');
            case Float n -> b.append(n).append('f');
            case Double n -> b.append(n).append('d');
            case Boolean bool -> b.append(bool ? "true" : "false");
            case Number n -> b.append(n);
            case null -> b.append("{}");
            default -> quote(b, v.toString());
        }
    }

    private static void key(StringBuilder b, String k) {
        boolean bare = !k.isEmpty();
        for (int n = 0; n < k.length() && bare; n++) {
            char c = k.charAt(n);
            bare = c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+');
        }
        if (bare) {
            b.append(k);
        } else {
            quote(b, k);
        }
    }

    /** A string as an SNBT (and JSON) string literal. */
    public static String quote(String str) {
        StringBuilder b = new StringBuilder();
        quote(b, str);
        return b.toString();
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (int n = 0; n < str.length(); n++) {
            char c = str.charAt(n);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        b.append('"');
    }

    // ----- JSON (text components)

    /** A parsed SNBT value as JSON, e.g. a text component; bytes 0 and 1 become booleans. */
    public static JsonElement json(Object v) {
        return switch (v) {
            case Map<?, ?> m -> {
                JsonObject o = new JsonObject();
                m.forEach((k, x) -> o.add(k.toString(), json(x)));
                yield o;
            }
            case List<?> l -> {
                JsonArray a = new JsonArray();
                l.forEach(x -> a.add(json(x)));
                yield a;
            }
            case String str -> new JsonPrimitive(str);
            case Byte n when n == 0 || n == 1 -> new JsonPrimitive(n == 1);
            case Boolean bool -> new JsonPrimitive(bool);
            case Number n -> new JsonPrimitive(n);
            case null -> JsonNull.INSTANCE;
            default -> new JsonPrimitive(v.toString());
        };
    }
}
