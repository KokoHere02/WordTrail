package com.wordtrail;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORDTRAIL_VERIFY_MYSQL", matches="true")
class SentenceRetirementMySqlTests {
    @Test void removesLegacyTablesAndKeepsNotesAndNewTranslations() throws Exception {
        String schema="wordtrail_retire_test_"+UUID.randomUUID().toString().replace("-", "");
        String url=System.getenv().getOrDefault("WORDTRAIL_MYSQL_ADMIN_URL", "jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        try(var c=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
            try(var admin=c.createStatement()){admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");}
            try {
                c.setCatalog(schema);
                try(var s=c.createStatement()) {
                    s.execute("CREATE TABLE sentence_translation_session(id BIGINT UNSIGNED PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,UNIQUE KEY(id,user_id))");
                    s.execute("CREATE TABLE sentence_translation_item(id BIGINT UNSIGNED PRIMARY KEY,session_id BIGINT UNSIGNED NOT NULL,sentence_id BIGINT UNSIGNED NOT NULL,UNIQUE KEY(id,session_id,sentence_id),FOREIGN KEY(session_id) REFERENCES sentence_translation_session(id))");
                    s.execute("CREATE TABLE sentence_translation_attempt(id BIGINT UNSIGNED PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,session_id BIGINT UNSIGNED NOT NULL,item_id BIGINT UNSIGNED NOT NULL,sentence_id BIGINT UNSIGNED NOT NULL,UNIQUE KEY(id,user_id,sentence_id),FOREIGN KEY(session_id,user_id) REFERENCES sentence_translation_session(id,user_id),FOREIGN KEY(item_id,session_id,sentence_id) REFERENCES sentence_translation_item(id,session_id,sentence_id))");
                    s.execute("CREATE TABLE sentence_item_word(item_id BIGINT UNSIGNED NOT NULL,FOREIGN KEY(item_id) REFERENCES sentence_translation_item(id))");
                    s.execute("CREATE TABLE user_sentence_review_state(last_attempt_id BIGINT UNSIGNED NOT NULL,FOREIGN KEY(last_attempt_id) REFERENCES sentence_translation_attempt(id))");
                    s.execute("CREATE TABLE sentence_translation_record(id BIGINT UNSIGNED PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,sentence_id BIGINT UNSIGNED NOT NULL,translation VARCHAR(100) NOT NULL)");
                    s.execute("CREATE TABLE user_sentence_note(id BIGINT PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,sentence_id BIGINT UNSIGNED NOT NULL,attempt_id BIGINT UNSIGNED,content VARCHAR(100),CONSTRAINT fk_sentence_note_attempt FOREIGN KEY(attempt_id,user_id,sentence_id) REFERENCES sentence_translation_attempt(id,user_id,sentence_id))");
                    s.execute("INSERT INTO sentence_translation_session VALUES(1,1)");
                    s.execute("INSERT INTO sentence_translation_item VALUES(1,1,10)");
                    s.execute("INSERT INTO sentence_translation_attempt VALUES(1,1,1,1,10)");
                    s.execute("INSERT INTO user_sentence_review_state VALUES(1)");
                    s.execute("INSERT INTO sentence_item_word VALUES(1)");
                    s.execute("INSERT INTO user_sentence_note VALUES(1,1,10,1,'keep note')");
                    s.execute("INSERT INTO sentence_translation_record VALUES(9,1,10,'keep translation')");
                    String sql=new ClassPathResource("db/016_retire_legacy_sentence_practice.sql").getContentAsString(StandardCharsets.UTF_8);
                    ScriptUtils.executeSqlScript(c,new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
                    try(var rs=s.executeQuery("SELECT content,attempt_id FROM user_sentence_note")){assertTrue(rs.next());assertEquals("keep note",rs.getString(1));assertNull(rs.getObject(2));}
                    try(var rs=s.executeQuery("SELECT translation FROM sentence_translation_record")){assertTrue(rs.next());assertEquals("keep translation",rs.getString(1));}
                    s.execute("UPDATE user_sentence_note SET attempt_id=9 WHERE id=1");
                    assertThrows(java.sql.SQLException.class,()->s.execute("UPDATE user_sentence_note SET attempt_id=999 WHERE id=1"));
                    try(var rs=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sentence_translation_session','sentence_translation_item','sentence_translation_attempt','sentence_item_word','user_sentence_review_state')")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
                }
            } finally {c.setCatalog("information_schema");try(var admin=c.createStatement()){admin.execute("DROP DATABASE `"+schema+"`");}}
        }
    }
}
