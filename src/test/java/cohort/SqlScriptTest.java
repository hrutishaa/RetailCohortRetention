package cohort;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptTest {
    @Test
    void splitsNamedBlocksAndDropsComments() {
        String text = """
            -- header comment
            -- step: first
            -- what it does
            DELETE FROM t WHERE x = 1;

            -- name: second
            SELECT 1;
            """;
        List<SqlScript.Block> blocks = SqlScript.parse(text);
        assertEquals(2, blocks.size());
        assertEquals("first", blocks.get(0).name());
        assertEquals("DELETE FROM t WHERE x = 1", blocks.get(0).sql());
        assertEquals("SELECT 1", blocks.get(1).sql());
    }

    @Test
    void realScriptsParse() throws Exception {
        assertEquals(7, SqlScript.read(Path.of("sql/01_clean.sql")).size());
        assertTrue(SqlScript.read(Path.of("sql/03_metrics.sql")).size() >= 9);
    }
}
