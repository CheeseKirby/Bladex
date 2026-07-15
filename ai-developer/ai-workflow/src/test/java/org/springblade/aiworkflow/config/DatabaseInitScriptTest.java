package org.springblade.aiworkflow.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitScriptTest {

    private static final Pattern ALTER_TABLE = Pattern.compile("ALTER\\s+TABLE\\s+([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);

    @Test
    void everyAlterTableMustAppearAfterItsCreateTable() throws Exception {
        Path initSql = Path.of("..", "sql", "init.sql").toAbsolutePath().normalize();
        String sql = Files.readString(initSql);
        String normalized = sql.toLowerCase(Locale.ROOT);

        Matcher matcher = ALTER_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            int createPosition = normalized.indexOf("create table if not exists " + table);
            int alterPosition = matcher.start();
            assertTrue(createPosition >= 0,
                    () -> "ALTER TABLE 引用了未声明的表: " + table + " (" + initSql + ")");
            assertTrue(createPosition < alterPosition,
                    () -> "必须先 CREATE TABLE 再 ALTER TABLE: " + table
                            + ", createPosition=" + createPosition + ", alterPosition=" + alterPosition);
        }
    }
}
