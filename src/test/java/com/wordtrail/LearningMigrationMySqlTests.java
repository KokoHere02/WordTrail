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
            var beforeSettings = history(c);
            run(c, new ClassPathResource("db/004_create_user_learning_setting.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeSettings, history(c));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM user_learning_setting"));
            run(c, "INSERT INTO user_learning_setting(user_id) VALUES(1)");
            assertEquals("20", scalar(c, "SELECT daily_target FROM user_learning_setting WHERE user_id=1"));
            for (String invalid : List.of("0", "201", "NULL")) {
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    () -> run(c, "UPDATE user_learning_setting SET daily_target=" + invalid + " WHERE user_id=1"));
            }
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                () -> run(c, "INSERT INTO user_learning_setting(user_id) VALUES(1)"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                () -> run(c, "INSERT INTO user_learning_setting(user_id) VALUES(999999)"));
            run(c, "UPDATE user_learning_setting SET daily_target=200 WHERE user_id=1");
            assertEquals("200", scalar(c, "SELECT daily_target FROM user_learning_setting WHERE user_id=1"));
            assertEquals(beforeSettings, history(c));
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
            run(c, """
                UPDATE user_word_state SET plan_status='PAUSED' WHERE user_id=1;
                INSERT INTO learning_batch(id,user_id,selection_type,task_type,learning_date,daily_target)
                  VALUES(2,1,'DAILY','DAILY',DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),20);
                INSERT INTO learning_batch_item(id,batch_id,word_id,sort_order,item_type,learning_status,result,answered_at,completed_at)
                  VALUES(3,2,1,1,'REVIEW','DONE',2,NOW(),NOW()),(4,2,2,2,'NEW','PENDING',NULL,NULL,NULL);
                """);
            var beforeSkip = history(c);
            run(c, new ClassPathResource("db/005_plan_management_skip.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeSkip.get("state"), history(c).get("state"));
            assertEquals(beforeSkip.get("item"), history(c).get("item"));
            assertEquals(beforeSkip.get("batch").getFirst(), history(c).get("batch").getFirst());
            assertEquals("SKIPPED", scalar(c, "SELECT learning_status FROM learning_batch_item WHERE id=3"));
            assertEquals("DONE", scalar(c, "SELECT status_before_skip FROM learning_batch_item WHERE id=3"));
            assertEquals("PENDING", scalar(c, "SELECT status_before_skip FROM learning_batch_item WHERE id=4"));
            assertEquals("1", scalar(c, "SELECT completed_at IS NOT NULL FROM learning_batch_item WHERE id=3"));
            assertEquals("1", scalar(c, "SELECT completed_at IS NULL FROM learning_batch_item WHERE id=4"));
            assertEquals("COMPLETED", scalar(c, "SELECT status FROM learning_batch WHERE id=2"));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM word_review_log"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                () -> run(c, "UPDATE learning_batch_item SET status_before_skip=NULL WHERE id=3"));
            var beforeReinforcement = history(c);
            run(c, new ClassPathResource("db/006_daily_reinforcement.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeReinforcement, history(c));
            assertEquals("0", scalar(c, "SELECT COUNT(*) FROM learning_batch_item WHERE reinforcement_remaining IS NOT NULL OR completion_reason IS NOT NULL"));
            run(c, "UPDATE learning_batch_item SET reinforcement_remaining=2,completion_reason='LIMIT_REACHED' WHERE id=3");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                () -> run(c, "UPDATE learning_batch_item SET reinforcement_remaining=4 WHERE id=3"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                () -> run(c, "UPDATE learning_batch_item SET completion_reason='MASTERED' WHERE id=3"));
            var beforeDictation=history(c);
            run(c,new ClassPathResource("db/007_dictation.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeDictation,history(c));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM learning_batch WHERE practice_mode<>'RECOGNITION'"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM learning_batch_item WHERE dictation_target IS NOT NULL OR dictation_correct_streak IS NOT NULL"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM dictation_review_log"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"UPDATE learning_batch SET practice_mode='DICTATION' WHERE id=2"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"UPDATE learning_batch_item SET dictation_target='one' WHERE id=1"));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    ()->run(second,new ClassPathResource("db/007_dictation.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            var beforeSentences=history(c);
            run(c,new ClassPathResource("db/008_sentence_translation_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c,new ClassPathResource("db/008_sentence_translation.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeSentences,history(c));
            for(String table:List.of("sentence_question","sentence_word","sentence_translation_session","sentence_translation_item","sentence_translation_attempt","user_sentence_favorite"))
                assertEquals("0",scalar(c,"SELECT COUNT(*) FROM "+table));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"INSERT INTO sentence_question(english_text,reference_translation,explanation,difficulty,source) VALUES('example','参考','解析',4,'original')"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"INSERT INTO sentence_question(english_text,reference_translation,explanation,source) VALUES('example','','解析','original')"));
            run(c,new ClassPathResource("db/008_sentence_translation_samples.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals("6",scalar(c,"SELECT COUNT(*) FROM sentence_question"));
            run(c,new ClassPathResource("db/008_sentence_translation_samples.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals("6",scalar(c,"SELECT COUNT(*) FROM sentence_question"));
            run(c,new ClassPathResource("db/008_sentence_translation_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    ()->run(second,new ClassPathResource("db/008_sentence_translation.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            run(c,"""
                INSERT INTO sentence_word(sentence_id,word_id) SELECT MIN(id),1 FROM sentence_question;
                INSERT INTO sentence_translation_session(id,user_id,selection_source,status,created_at,completed_at)
                  VALUES(700,1,'BANK','COMPLETED','2025-02-01 10:00:00','2025-02-02 10:00:00');
                INSERT INTO sentence_translation_item(id,session_id,sentence_id,sort_order,status,english_text,reference_translation,explanation,difficulty,source)
                  SELECT 700,700,MIN(id),1,'DONE','Historical sentence.','旧译文','旧解析',1,'legacy' FROM sentence_question;
                INSERT INTO sentence_translation_attempt(id,user_id,session_id,item_id,sentence_id,submit_request_id,action,translation,learning_date,submitted_at,self_assessment,review_request_id,review_date,reviewed_at)
                  SELECT 700,1,700,700,MIN(id),'legacy_sentence_submit','ANSWER','原始用户输入','2025-02-01','2025-02-01 10:00:00','PARTIAL','legacy_sentence_review','2025-02-02','2025-02-02 10:00:00' FROM sentence_question;
                """);
            var oldAttempts=rows(c,"SELECT * FROM sentence_translation_attempt ORDER BY id");
            var oldItems=rows(c,"SELECT * FROM sentence_translation_item ORDER BY id");
            var oldSessions=rows(c,"SELECT * FROM sentence_translation_session ORDER BY id");
            var oldQuestions=rows(c,"SELECT id,english_text,reference_translation,explanation,difficulty,source,source_reference,status FROM sentence_question ORDER BY id");
            run(c,new ClassPathResource("db/009_sentence_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c,new ClassPathResource("db/009_sentence_bank_review_statistics.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(oldAttempts,rows(c,"SELECT * FROM sentence_translation_attempt ORDER BY id"));
            assertEquals(oldItems,rows(c,"SELECT * FROM sentence_translation_item ORDER BY id"));
            assertEquals(oldSessions,rows(c,"SELECT * FROM sentence_translation_session ORDER BY id"));
            assertEquals(oldQuestions,rows(c,"SELECT id,english_text,reference_translation,explanation,difficulty,source,source_reference,status FROM sentence_question ORDER BY id"));
            assertEquals("1",scalar(c,"SELECT COUNT(*) FROM user_sentence_review_state WHERE user_id=1 AND last_attempt_id=700 AND review_level=0 AND next_review_date='2025-02-03'"));
            assertEquals("1",scalar(c,"SELECT COUNT(*) FROM sentence_item_word WHERE item_id=700 AND word_id=1"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sentence_import_batch"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sentence_question WHERE version<>0"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"INSERT INTO sentence_question(english_text,reference_translation,explanation,source) SELECT english_text,'参考','解析','different source' FROM sentence_question LIMIT 1"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"UPDATE user_sentence_review_state SET review_level=6"));
            run(c,new ClassPathResource("db/009_sentence_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    ()->run(second,new ClassPathResource("db/009_sentence_bank_review_statistics.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            run(c,"INSERT INTO refresh_token(user_id,token_hash,expires_at,created_at) VALUES(1,REPEAT('a',64),'2025-03-02 00:00:00','2025-03-01 00:00:00')");
            var originalUsers=rows(c,"SELECT id,username,password_hash,nickname,created_at,updated_at FROM user_account ORDER BY id");
            var originalRefresh=rows(c,"SELECT id,user_id,token_hash,expires_at,revoked_at,created_at FROM refresh_token ORDER BY id");
            var originalAttemptRows=rows(c,"SELECT * FROM sentence_translation_attempt ORDER BY id");
            run(c,new ClassPathResource("db/010_notes_auth_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c,new ClassPathResource("db/010_notes_auth_sessions.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(originalUsers,rows(c,"SELECT id,username,password_hash,nickname,created_at,updated_at FROM user_account ORDER BY id"));
            assertEquals(originalRefresh,rows(c,"SELECT id,user_id,token_hash,expires_at,revoked_at,created_at FROM refresh_token ORDER BY id"));
            assertEquals(originalAttemptRows,rows(c,"SELECT * FROM sentence_translation_attempt ORDER BY id"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM user_account WHERE auth_version<>0"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM refresh_token WHERE session_id IS NOT NULL"));
            for(String table:List.of("user_auth_session","user_sentence_note","user_sentence_note_tag"))assertEquals("0",scalar(c,"SELECT COUNT(*) FROM "+table));
            run(c,"INSERT INTO user_sentence_note(user_id,sentence_id,content,attempt_id,version,updated_at) SELECT 1,MIN(id),'真实合成笔记',700,1,'2025-03-01 00:00:00' FROM sentence_question");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"INSERT INTO user_sentence_note_tag(note_id,error_tag) SELECT id,'AUTOMATIC_GRADE' FROM user_sentence_note LIMIT 1"));
            run(c,"INSERT INTO user_account(id,username,password_hash) VALUES(2,'other_legacy_fixture','unused')");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"INSERT INTO user_sentence_note(user_id,sentence_id,content,attempt_id,version,updated_at) SELECT 2,MIN(id),'非法跨用户笔记',700,1,'2025-03-01 00:00:00' FROM sentence_question"));
            run(c,new ClassPathResource("db/010_notes_auth_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    ()->run(second,new ClassPathResource("db/010_notes_auth_sessions.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            run(c,"""
                INSERT INTO word_review_log(user_id,word_id,batch_id,batch_item_id,request_id,event_type,learning_date,result,attempt_no,affects_schedule,schedule_before,schedule_after,answered_at,created_at)
                VALUES(1,1,1,1,'pre_unified_recognition','ANSWER','2025-01-02',2,1,0,'{"reviewLevel":0}','{"reviewLevel":0}','2025-01-02 03:04:05','2025-01-02 03:04:05');
                """);
            var beforeUnified=history(c);
            var beforeWordEvents=rows(c,"SELECT * FROM word_review_log ORDER BY id");
            var beforeDictationEvents=rows(c,"SELECT * FROM dictation_review_log ORDER BY id");
            var beforeSchedules=rows(c,"SELECT id,review_level,next_review_at,plan_status FROM user_word_state ORDER BY id");
            run(c,new ClassPathResource("db/011_training_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c,new ClassPathResource("db/011_unified_daily_training.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeUnified,history(c));
            assertEquals(beforeSchedules,rows(c,"SELECT id,review_level,next_review_at,plan_status FROM user_word_state ORDER BY id"));
            assertEquals(beforeDictationEvents,rows(c,"SELECT * FROM dictation_review_log ORDER BY id"));
            var newWordEvents=rows(c,"SELECT * FROM word_review_log ORDER BY id");
            assertEquals(beforeWordEvents,newWordEvents.stream().map(r->r.subList(0,r.size()-1)).toList());
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM learning_batch WHERE study_mode<>'RECOGNITION' OR mode_version<>0"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM learning_batch_item WHERE study_word IS NOT NULL OR study_hint_used<>0 OR study_attempt_count<>0"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM word_training_attempt"));
            assertEquals("RECOGNITION",scalar(c,"SELECT feedback_mode FROM word_review_log WHERE request_id='pre_unified_recognition'"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"UPDATE learning_batch SET study_mode='AUTOMATIC' WHERE id=2"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                ()->run(c,"UPDATE learning_batch_item SET study_presentation_id='bad' WHERE id=3"));
            run(c,"UPDATE learning_batch_item SET completion_reason='FOLLOW_TYPED' WHERE id=3");
            run(c,new ClassPathResource("db/011_training_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                    ()->run(second,new ClassPathResource("db/011_unified_daily_training.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
            var beforeTwelve=history(c);
            var oldSettings=rows(c,"SELECT user_id,daily_target,preferred_study_mode,created_at,updated_at FROM user_learning_setting ORDER BY user_id");
            var oldFeedback=rows(c,"SELECT * FROM word_review_log ORDER BY id");
            var oldTraining=rows(c,"SELECT * FROM word_training_attempt ORDER BY id");
            var oldMemory=rows(c,"SELECT * FROM user_word_state ORDER BY id");
            run(c,new ClassPathResource("db/012_learning_precheck.sql").getContentAsString(StandardCharsets.UTF_8));
            run(c,new ClassPathResource("db/012_consolidation_allocation_audio.sql").getContentAsString(StandardCharsets.UTF_8));
            assertEquals(beforeTwelve,history(c)); assertEquals(oldFeedback,rows(c,"SELECT * FROM word_review_log ORDER BY id"));
            assertEquals(oldTraining,rows(c,"SELECT * FROM word_training_attempt ORDER BY id"));
            assertEquals(oldMemory,rows(c,"SELECT * FROM user_word_state ORDER BY id"));
            assertEquals(oldSettings,rows(c,"SELECT user_id,daily_target,preferred_study_mode,created_at,updated_at FROM user_learning_setting ORDER BY user_id"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM user_learning_setting WHERE new_word_target IS NOT NULL OR review_limit IS NOT NULL OR audio_accent<>'US' OR audio_playback_rate<>1 OR audio_repeat_count<>1 OR audio_auto_play<>0"));
            assertEquals("0",scalar(c,"SELECT COUNT(*) FROM learning_batch WHERE new_word_target IS NOT NULL OR review_limit IS NOT NULL OR creation_request_id IS NOT NULL"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"UPDATE user_learning_setting SET new_word_target=1 WHERE user_id=1"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"UPDATE user_learning_setting SET audio_accent='GB' WHERE user_id=1"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"UPDATE user_learning_setting SET audio_playback_rate=2 WHERE user_id=1"));
            run(c,"UPDATE user_learning_setting SET daily_target=3,new_word_target=1,review_limit=2,audio_accent='UK',audio_playback_rate=0.75,audio_repeat_count=2 WHERE user_id=1");
            run(c,"UPDATE learning_batch SET study_mode='LISTEN_DICTATION' WHERE id=2");
            run(c,"INSERT INTO learning_batch(id,user_id,selection_type,task_type,practice_mode,study_mode,creation_request_id,creation_fingerprint) VALUES(5000,1,'MANUAL','PRACTICE','CONSOLIDATION','LISTEN_DICTATION','migration_focus_request',REPEAT('f',64))");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"INSERT INTO learning_batch(user_id,selection_type,practice_mode) VALUES(1,'MANUAL','CONSOLIDATION')"));
            assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(c,"INSERT INTO learning_batch(user_id,selection_type,practice_mode,creation_request_id,creation_fingerprint) VALUES(1,'MANUAL','CONSOLIDATION','migration_focus_request',REPEAT('f',64))"));
            run(c,new ClassPathResource("db/012_learning_postcheck.sql").getContentAsString(StandardCharsets.UTF_8));
            try(var second=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv().getOrDefault("DB_PASSWORD","root"))) {
                second.setCatalog(schema);
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,()->run(second,new ClassPathResource("db/012_consolidation_allocation_audio.sql").getContentAsString(StandardCharsets.UTF_8)));
            }
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
