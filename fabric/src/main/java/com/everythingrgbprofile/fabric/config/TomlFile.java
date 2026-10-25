package com.everythingrgbprofile.fabric.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.everythingrgbprofile.config.ConfigSpec;

/**
 * Just enough TOML for one config file, written by hand because the
 * alternative was a library mod players would have to go and install.
 *
 * <p>Writing copies NeoForge's output exactly (well, NightConfig's, which is
 * what NeoForge writes with): a tab before every value, comments as
 * {@code #text} with no space, {@code # Default:} and {@code # Range:} under
 * the comment of every ranged number, one blank line between sections. So a
 * config moved from one loader to the other reads like it was always there.
 *
 * <p>Reading takes the subset a person editing this file could plausibly
 * write: sections, {@code key = value}, booleans, integers, floats, and both
 * string kinds. Arrays, inline tables and multi-line strings get reported and
 * skipped instead of understood, because nothing in this file uses them and
 * a full TOML parser is a lot of code to carry for zero settings. A line that
 * makes no sense gets reported and skipped too, rather than taking the whole
 * file down with it.
 */
final class TomlFile {

    /**
     * Every value read, keyed by its dotted path ({@code general.enabled}),
     * plus the paths whose line was there but couldn't be read, so those get
     * called unreadable rather than missing.
     */
    record Parsed(Map<String, Object> values, Set<String> unreadable, List<String> problems) {
    }

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9][0-9_]*)");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(0|[1-9][0-9_]*)(\\.[0-9_]+)?([eE][+-]?[0-9_]+)?");

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    static Parsed parse(List<String> lines) {
        Map<String, Object> values = new HashMap<>();
        Set<String> unreadable = new HashSet<>();
        List<String> problems = new ArrayList<>();
        String table = "";
        boolean skippingTable = false;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            Cursor c = new Cursor(lines.get(i), lineNo);
            String path = null;
            c.skipSpace();
            if (c.atEnd() || c.peek() == '#') continue;

            try {
                if (c.peek() == '[') {
                    c.next();
                    if (!c.atEnd() && c.peek() == '[') {
                        // An array of tables. Nothing here is one, so the
                        // keys under it belong to nothing we know about.
                        problems.add("line " + lineNo + ": arrays of tables ([[...]]) aren't used in this file, skipped");
                        skippingTable = true;
                        continue;
                    }
                    c.skipSpace();
                    String name = c.readKey();
                    c.skipSpace();
                    c.expect(']');
                    c.expectLineEnd();
                    table = name;
                    skippingTable = false;
                    continue;
                }

                String key = c.readKey();
                path = table.isEmpty() ? key : table + "." + key;
                c.skipSpace();
                c.expect('=');
                c.skipSpace();
                Object value = c.readValue();
                c.expectLineEnd();
                if (skippingTable) continue;
                if (values.put(path, value) != null) {
                    problems.add("line " + lineNo + ": " + path + " is set twice; the last one wins");
                }
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
                if (path != null && !skippingTable) unreadable.add(path);
            }
        }
        return new Parsed(values, unreadable, problems);
    }

    /** One line, read left to right. Throws IllegalArgumentException naming the line. */
    private static final class Cursor {
        private final String s;
        private final int lineNo;
        private int pos;

        Cursor(String s, int lineNo) {
            this.s = s;
            this.lineNo = lineNo;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void skipSpace() {
            while (!atEnd() && (peek() == ' ' || peek() == '\t')) pos++;
        }

        void expect(char wanted) {
            if (atEnd() || peek() != wanted) throw fail("expected '" + wanted + "'");
            pos++;
        }

        /** Only whitespace or a comment may follow. */
        void expectLineEnd() {
            skipSpace();
            if (!atEnd() && peek() != '#') throw fail("unexpected text after the value");
        }

        /** A key, possibly dotted, each part bare or quoted. */
        String readKey() {
            StringBuilder key = new StringBuilder();
            while (true) {
                skipSpace();
                if (atEnd()) throw fail("expected a key");
                String part;
                if (peek() == '"') {
                    pos++;
                    part = readBasicString();
                } else if (peek() == '\'') {
                    pos++;
                    part = readLiteralString();
                } else {
                    int start = pos;
                    while (!atEnd() && BARE_KEY.matcher(String.valueOf(peek())).matches()) pos++;
                    if (start == pos) throw fail("expected a key");
                    part = s.substring(start, pos);
                }
                key.append(part);
                skipSpace();
                if (!atEnd() && peek() == '.') {
                    pos++;
                    key.append('.');
                    continue;
                }
                return key.toString();
            }
        }

        Object readValue() {
            if (atEnd()) throw fail("missing value");
            char first = peek();
            if (first == '"') {
                if (s.startsWith("\"\"\"", pos)) throw fail("multi-line strings aren't supported in this file");
                pos++;
                return readBasicString();
            }
            if (first == '\'') {
                if (s.startsWith("'''", pos)) throw fail("multi-line strings aren't supported in this file");
                pos++;
                return readLiteralString();
            }
            if (first == '[' || first == '{') throw fail("arrays and inline tables aren't used in this file");

            int start = pos;
            while (!atEnd() && peek() != ' ' && peek() != '\t' && peek() != '#') pos++;
            String word = s.substring(start, pos);
            switch (word) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "inf": case "+inf":
                    return Double.POSITIVE_INFINITY;
                case "-inf":
                    return Double.NEGATIVE_INFINITY;
                case "nan": case "+nan": case "-nan":
                    return Double.NaN;
                default:
                    break;
            }
            String digits = word.replace("_", "");
            try {
                if (INTEGER.matcher(word).matches()) {
                    long n = Long.parseLong(digits);
                    return n == (int) n ? (Object) (int) n : (Object) n;
                }
                if (FLOAT.matcher(word).matches()) return Double.parseDouble(digits);
            } catch (NumberFormatException e) {
                throw fail("'" + word + "' is not a number this file can hold");
            }
            throw fail("'" + word + "' is not a value; strings need quotes, like \"" + word + "\"");
        }

        /** After the opening quote. */
        String readBasicString() {
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) throw fail("string is missing its closing quote");
                char ch = next();
                if (ch == '"') return out.toString();
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (atEnd()) throw fail("string is missing its closing quote");
                char esc = next();
                switch (esc) {
                    case 'b' -> out.append('\b');
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'f' -> out.append('\f');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> out.appendCodePoint(readHex(4));
                    case 'U' -> out.appendCodePoint(readHex(8));
                    // The one a Windows path walks straight into: "C:\Users"
                    // is a \U escape with no hex after it. Worth naming.
                    default -> throw fail("'\\" + esc + "' is not an escape TOML knows; write a backslash as \\\\"
                            + " or use single quotes, like 'C:\\path'");
                }
            }
        }

        /** After the opening quote. No escapes at all, which is the point of them. */
        String readLiteralString() {
            int end = s.indexOf('\'', pos);
            if (end < 0) throw fail("string is missing its closing quote");
            String text = s.substring(pos, end);
            pos = end + 1;
            return text;
        }

        private int readHex(int count) {
            if (pos + count > s.length()) throw fail("unfinished unicode escape");
            try {
                int cp = Integer.parseInt(s.substring(pos, pos + count), 16);
                pos += count;
                return cp;
            } catch (IllegalArgumentException e) {
                throw fail("bad unicode escape");
            }
        }

        private IllegalArgumentException fail(String why) {
            return new IllegalArgumentException("line " + lineNo + ": " + why + ": " + s.strip());
        }
    }

    // ---------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------

    /** The whole file, laid out like NeoForge lays it out. */
    static String write(ConfigSpec spec, Map<ConfigSpec.ConfigValue<?>, Object> values) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        List<String> path = new ArrayList<>();
        List<String> header = new ArrayList<>();

        for (ConfigSpec.Entry entry : spec.layout()) {
            if (entry instanceof ConfigSpec.Push push) {
                path.add(push.name());
                String indent = "\t".repeat(path.size() - 1);
                if (!out.isEmpty()) out.append(nl);
                for (String line : push.comment()) out.append(indent).append('#').append(line).append(nl);
                out.append(indent).append('[').append(String.join(".", path)).append(']').append(nl);
                header = new ArrayList<>(path);
            } else if (entry instanceof ConfigSpec.Pop) {
                path.remove(path.size() - 1);
            } else if (entry instanceof ConfigSpec.ConfigValue<?> value) {
                if (!header.equals(path)) {
                    // Back in a parent after a nested section closed. TOML
                    // has no way to say that except repeating the header.
                    if (!out.isEmpty()) out.append(nl);
                    out.append("\t".repeat(Math.max(0, path.size() - 1)))
                            .append('[').append(String.join(".", path)).append(']').append(nl);
                    header = new ArrayList<>(path);
                }
                String indent = "\t".repeat(path.size());
                for (String line : value.comment()) out.append(indent).append('#').append(line).append(nl);
                if (value instanceof ConfigSpec.IntValue v) {
                    out.append(indent).append("# Default: ").append(v.getDefault()).append(nl);
                    out.append(indent).append("# Range: ").append(v.min()).append(" ~ ").append(v.max()).append(nl);
                } else if (value instanceof ConfigSpec.DoubleValue v) {
                    out.append(indent).append("# Default: ").append(v.getDefault()).append(nl);
                    out.append(indent).append("# Range: ").append(v.min()).append(" ~ ").append(v.max()).append(nl);
                }
                out.append(indent).append(writeKey(value.key())).append(" = ")
                        .append(writeValue(values.get(value))).append(nl);
            }
        }
        return out.toString();
    }

    private static String writeKey(String key) {
        return BARE_KEY.matcher(key).matches() ? key : quote(key);
    }

    static String writeValue(Object value) {
        if (value instanceof String text) return quote(text);
        return String.valueOf(value);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (ch < 0x20 || ch == 0x7F) out.append(String.format("\\u%04X", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private TomlFile() {
    }
}
