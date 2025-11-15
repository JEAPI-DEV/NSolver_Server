package de.simplehardware.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal console logger providing timestamped and colored output.
 * Keeps dependencies low and works nicely for development/testing.
 */
public class ConsoleLogger {
    private final String name;
    private static final DateTimeFormatter TF = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    // ANSI color codes (won't harm terminals that don't support them)
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";

    public ConsoleLogger(String name) {
        this.name = name;
    }

    private String ts() {
        return TF.format(Instant.now());
    }

    public void info(String msg, String... kv) {
        System.out.println(format("INFO", CYAN, msg, kv));
    }

    public void warn(String msg, String... kv) {
        System.out.println(format("WARN", YELLOW, msg, kv));
    }

    public void error(String msg, String... kv) {
        System.err.println(format("ERROR", RED, msg, kv));
    }

    public void debug(String msg, String... kv) {
        System.out.println(format("DEBUG", GREEN, msg, kv));
    }

    private String format(String level, String color, String msg, String... kv) {
        StringBuilder b = new StringBuilder();
        b.append(color).append("[").append(ts()).append("]")
                .append(" ").append(level)
                .append(" ").append("(").append(name).append(")")
                .append(RESET)
                .append(" - ").append(msg);

        if (kv != null && kv.length > 0) {
            b.append(" |");
            for (int i = 0; i < kv.length; i++) {
                if (i > 0) b.append(' ');
                b.append(kv[i]);
            }
        }

        return b.toString();
    }
}
