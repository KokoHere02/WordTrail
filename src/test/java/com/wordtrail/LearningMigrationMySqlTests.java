package com.wordtrail;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: creates a separate database; never migrates the application's database. */
@EnabledIfEnvironmentVariable(named = "WORDTRAIL_VERIFY_MYSQL", matches = "true")
class LearningMigrationMySqlTests {
    @Test void migrationPreservesLegacyRowsAndRejectsReruns() throws Exception {
        String schema = "wordtrail_daily_verify_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv().getOrDefault("WORDTRAIL_MYSQL_ADMIN_URL",
            "jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        try (var c = DriverManager.getConnection(url,
                System.getenv().getOrDefault("DB_USERNAME", "root"),
                System.getenv().getOrDefault("DB_PASSWORD", "root"))) {
            try (var s = c.createStatement()) { s.execute("CREATE DATABASE `" + schema + "`"); }
            c.setCatalog(schema);
            System.out.println("ISOLATED_MYSQL_VERIFICATION_SCHEMA=" + schema);
            // Baseline contains USE word_trail_db for manual installation. Remove it before execution.
            String baseline = new ClassPathResource("db/WordTrail.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?im)^USE\\s+word_trail_db;", "");
            assertFalse(baseline.matches("(?is).*\\bUSE\\s+word_trail_db.*"));
            run(c, baseline);
            run(c, """
                INSERT INTO user_account(id,username,password_hash) VALUES(1,'legacy_fixture','unused');
                INSERT INTO word(id,word) VALUES(1,'legacy_one'),(2,'legacy_two');
                INSERT INTO word_book(id,name) VALUES(1,'legacy_book');
                INSERT INTO word_book_item(book_id,word_id,sort_order) VALUES(1,1,1),(1,2,2);
                INSERT INTO learning_batch(id,user_id,book_id,selection_type,status)
                  VALUES(1,1,1,'MANUAL','IN_PROGRESS');
                INSERT INTO learning_batch_item(id,batch_id,word_id,sort_order,result,answered_at)
                  VALUES(1,1,1,1,2,'2025-01-02 03:04:05'),(2,1,2,2,NULL,NULL);
                INSERT INTO user_word_state(id,user_id,word_id,last_result,last_batch_item_id,first_learned_at,last_learned_at)
                  VALUES(1,1,1,2,1,'2025-01-01 01:02:03','2025-01-02 03:04:05');
                """);
            Map<String, List<List<String>>> before = history(c);
            run(c, new ClassPathResource("db/003_.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(before, history(c));
            assertEquals("PRACTICE", scalar(c, "SELECT task_type FROM learning_batch WHERE id=1"));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM word_review_log"));
            assertEquals("0", scalar(c, "SELECT review_level FROM user_word_state WHERE id=1"));
            assertEquals("2025-01-01 01:02:03", scalar(c, "SELECT DATE_FORMAT(joined_at,'%Y-%m-%d %H:%i:%s') FROM user_word_state WHERE id=1"));
            assertEquals("1", scalar(c, "SELECT next_review_at=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')) FROM user_word_state WHERE id=1"));
            assertEquals("DONE", scalar(c, "SELECT learning_status FROM learning_batch_item WHERE id=1"));
            assertEquals("0", scalar(c, "SELECT answer_count FROM learning_batch_item WHERE id=1"));
            assertEquals("PENDING", scalar(c, "SELECT learning_status FROM learning_batch_item WHERE id=2"));
            run(c, "INSERT INTO user_word_state(user_id,word_id,joined_at) VALUES(1,2,NOW())");
            assertEquals("1", scalar(c, "SELECT first_learned_at IS NULL AND last_result IS NULL AND last_batch_item_id IS NULL AND last_learned_at IS NULL FROM user_word_state WHERE word_id=2"));
            run(c, new ClassPathResource("db/003_learning_plan_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c, new ClassPathResource("db/003_learning_plan_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            // A fresh connection has no temporary guard table: refusal is due to upgraded structure.
            try (var second = DriverManager.getConnection(url,
                    System.getenv().getOrDefault("DB_USERNAME", "root"), System.getenv().getOrDefault("DB_PASSWORD", "root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    () -> run(second, new ClassPathResource("db/003_.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            // Exclude the newly inserted plan row when comparing the preserved legacy history.
            assertEquals(before.get("batch"), history(c).get("batch"));
            assertEquals(before.get("item"), history(c).get("item"));
        }
        // Keep the isolated schema for subsequent real-MySQL HTTP tests and manual inspection.
    }

    private static void run(Connection c, String sql) {
        ScriptUtils.executeSqlScript(c, new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
    private static String scalar(Connection c, String sql) throws SQLException {
        try (var s=c.createStatement(); var r=s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); }
    }
    private static List<List<String>> rows(Connection c, String sql) throws SQLException {
        var result=new ArrayList<List<String>>();
        try (var s=c.createStatement(); var r=s.executeQuery(sql)) {
            while(r.next()) {
                var row=new ArrayList<String>();
                for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.add(r.getString(i));
                result.add(row);
            }
        }
        return result;
    }
    private static Map<String,List<List<String>>> history(Connection c) throws SQLException {
        return Map.of("state",rows(c,"SELECT id,user_id,word_id,last_result,last_batch_item_id,first_learned_at,last_learned_at FROM user_word_state ORDER BY id"),
            "batch",rows(c,"SELECT id,user_id,book_id,selection_type,status,created_at,updated_at,completed_at FROM learning_batch ORDER BY id"),
            "item",rows(c,"SELECT id,batch_id,word_id,sort_order,result,answered_at FROM learning_batch_item ORDER BY id"));
    }
}
