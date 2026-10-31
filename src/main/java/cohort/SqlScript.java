package cohort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Splits a .sql file into named blocks marked with "-- step: x" or "-- name: x". */
public final class SqlScript {
    public record Block(String name, String sql) {}

    private SqlScript() {}

    public static List<Block> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        String name = null;
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("-- step:") || t.startsWith("-- name:")) {
                flush(blocks, name, sql);
                name = t.substring(t.indexOf(':') + 1).trim();
                sql.setLength(0);
            } else if (!t.startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        flush(blocks, name, sql);
        return blocks;
    }

    private static void flush(List<Block> blocks, String name, StringBuilder sql) {
        if (name == null) return;
        String body = sql.toString().trim();
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1).trim();
        if (!body.isEmpty()) blocks.add(new Block(name, body));
    }
}
