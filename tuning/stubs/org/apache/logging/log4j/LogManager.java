package org.apache.logging.log4j;

/**
 * Printing stub. This previously returned null, which meant any LOGGER call
 * reached from the tuning studio threw a NullPointerException rather than
 * logging. Substitutes {} placeholders the way log4j does so harness output
 * matches what the game will actually write.
 */
public class LogManager {
    public static Logger getLogger(String name) {
        return new Logger() {
            private void out(String level, String s, Object... a) {
                StringBuilder sb = new StringBuilder();
                int arg = 0, i = 0;
                while (i < s.length()) {
                    if (i + 1 < s.length() && s.charAt(i) == '{' && s.charAt(i + 1) == '}') {
                        sb.append(arg < a.length ? String.valueOf(a[arg++]) : "{}");
                        i += 2;
                    } else {
                        sb.append(s.charAt(i++));
                    }
                }
                System.out.println("[" + name + "/" + level + "] " + sb);
            }
            public void info(String s, Object... a) { out("INFO", s, a); }
            public void warn(String s, Object... a) { out("WARN", s, a); }
            public void warn(String s, Throwable t) { out("WARN", s); }
            public void debug(String s, Object... a) { out("DEBUG", s, a); }
            public void error(String s, Object... a) { out("ERROR", s, a); }
        };
    }
}
