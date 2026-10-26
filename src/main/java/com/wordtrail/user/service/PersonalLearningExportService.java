package com.wordtrail.user.service;

import java.io.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** An authenticated user's portable learning archive. Explicit projections exclude authentication material. */
@Service @RequiredArgsConstructor
public class PersonalLearningExportService {
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final Clock clock;

    private static final String SENTENCES="SELECT sentence_id FROM user_sentence_favorite WHERE user_id=? UNION SELECT sentence_id FROM user_sentence_note WHERE user_id=? UNION SELECT sentence_id FROM user_sentence_review_state WHERE user_id=? UNION SELECT i.sentence_id FROM sentence_translation_item i JOIN sentence_translation_session s ON s.id=i.session_id WHERE s.user_id=?";
    private static final String WORDS="SELECT word_id FROM user_word_state WHERE user_id=? UNION SELECT i.word_id FROM learning_batch_item i JOIN learning_batch b ON b.id=i.batch_id WHERE b.user_id=? UNION SELECT i.word_id FROM word_book_item i JOIN word_book b ON b.id=i.book_id WHERE b.owner_id=? UNION SELECT w.word_id FROM sentence_item_word w JOIN sentence_translation_item i ON i.id=w.item_id JOIN sentence_translation_session s ON s.id=i.session_id WHERE s.user_id=? UNION SELECT word_id FROM sentence_word WHERE sentence_id IN ("+SENTENCES+")";
    private static final Set<String> BOOLEANS=Set.of("affects_schedule","hint_used","extra_training","audio_auto_play","dictation_had_failure");

    private static Map<String,String> queries() {
        var queries=new LinkedHashMap<String,String>();
        queries.put("profile","SELECT id,username,nickname,created_at,updated_at FROM user_account WHERE id=?");
        queries.put("settings","SELECT user_id,daily_target,preferred_study_mode,new_word_target,review_limit,audio_auto_play,audio_accent,audio_playback_rate,audio_repeat_count,created_at,updated_at FROM user_learning_setting WHERE user_id=?");
        queries.put("word_plan","SELECT id,user_id,word_id,last_result,last_batch_item_id,first_learned_at,last_learned_at,plan_status,joined_at,next_review_at,review_level FROM user_word_state WHERE user_id=? ORDER BY id");
        queries.put("word_books","SELECT id,name,description,owner_id,created_at,updated_at FROM word_book b WHERE owner_id=? OR (owner_id IS NULL AND EXISTS(SELECT 1 FROM learning_batch l WHERE l.user_id=? AND l.book_id=b.id)) ORDER BY id");
        queries.put("collected_words","SELECT i.id,i.book_id,i.word_id,i.sort_order,i.created_at FROM word_book_item i JOIN word_book b ON b.id=i.book_id WHERE b.owner_id=? ORDER BY i.id");
        queries.put("words","SELECT id,word,phonetic,translation,definition,pos,tag,bnc,frq,exchange,created_at,updated_at FROM word WHERE id IN ("+WORDS+") ORDER BY id");
        queries.put("word_tasks","SELECT id,user_id,book_id,selection_type,status,created_at,updated_at,completed_at,task_type,practice_mode,learning_date,daily_target,study_mode,mode_version,new_word_target,review_limit,consolidation_reason FROM learning_batch WHERE user_id=? ORDER BY id");
        queries.put("word_task_items","SELECT i.id,i.batch_id,i.word_id,i.sort_order,i.result,i.answered_at,i.item_type,i.learning_status,i.queue_order,i.answer_count,i.relearn_count,i.completed_at,i.status_before_skip,i.skipped_at,i.reinforcement_remaining,i.completion_reason,i.dictation_target,i.dictation_translation,i.dictation_had_failure,i.dictation_correct_streak,i.study_word,i.study_translation,i.study_phonetic,i.study_hint_used,i.study_attempt_count FROM learning_batch_item i JOIN learning_batch b ON b.id=i.batch_id WHERE b.user_id=? ORDER BY i.id");
        queries.put("word_feedback","SELECT id,user_id,word_id,batch_id,batch_item_id,event_type,corrects_log_id,learning_date,result,attempt_no,affects_schedule,schedule_before,schedule_after,answered_at,created_at,feedback_mode FROM word_review_log WHERE user_id=? ORDER BY id");
        queries.put("dictation_attempts","SELECT id,user_id,word_id,batch_id,batch_item_id,event_type,input_text,verdict,attempt_no,learning_date,answered_at,correct_streak_before,correct_streak_after,had_failure_after,item_status_after,completion_reason_after,affects_schedule FROM dictation_review_log WHERE user_id=? ORDER BY id");
        queries.put("training_attempts","SELECT id,user_id,word_id,batch_id,batch_item_id,mode,action,input_text,self_assessment,verdict,hint_used,extra_training,attempt_no,review_log_id,learning_date,answered_at FROM word_training_attempt WHERE user_id=? ORDER BY id");
        queries.put("sentence_tasks","SELECT id,user_id,selection_source,status,created_at,completed_at FROM sentence_translation_session WHERE user_id=? ORDER BY id");
        queries.put("sentence_task_items","SELECT i.id,i.session_id,i.sentence_id,i.sort_order,i.status,i.english_text,i.reference_translation,i.explanation,i.difficulty,i.source,i.source_reference FROM sentence_translation_item i JOIN sentence_translation_session s ON s.id=i.session_id WHERE s.user_id=? ORDER BY i.id");
        queries.put("sentence_item_words","SELECT w.item_id,w.word_id,w.word FROM sentence_item_word w JOIN sentence_translation_item i ON i.id=w.item_id JOIN sentence_translation_session s ON s.id=i.session_id WHERE s.user_id=? ORDER BY w.item_id,w.word_id");
        queries.put("sentence_attempts","SELECT id,user_id,session_id,item_id,sentence_id,action,translation,learning_date,submitted_at,self_assessment,review_date,reviewed_at FROM sentence_translation_attempt WHERE user_id=? ORDER BY id");
        queries.put("sentence_review","SELECT id,user_id,sentence_id,last_attempt_id,review_level,last_assessment,reviewed_at,next_review_date FROM user_sentence_review_state WHERE user_id=? ORDER BY id");
        queries.put("sentence_favorites","SELECT id,user_id,sentence_id,created_at FROM user_sentence_favorite WHERE user_id=? ORDER BY id");
        queries.put("sentence_notes","SELECT id,user_id,sentence_id,content,attempt_id,version,updated_at FROM user_sentence_note WHERE user_id=? ORDER BY id");
        queries.put("sentence_note_tags","SELECT t.note_id,t.error_tag FROM user_sentence_note_tag t JOIN user_sentence_note n ON n.id=t.note_id WHERE n.user_id=? ORDER BY t.note_id,t.error_tag");
        queries.put("sentences","SELECT id,english_text,reference_translation,explanation,difficulty,source,source_reference,status,version FROM sentence_question WHERE id IN ("+SENTENCES+") ORDER BY id");
        queries.put("sentence_words","SELECT sentence_id,word_id FROM sentence_word WHERE sentence_id IN ("+SENTENCES+") ORDER BY sentence_id,word_id");
        return queries;
    }

    // userId is captured from CurrentUser by the controller before Spring hands off the streaming thread.
    public void write(long userId,OutputStream output)throws IOException {
        try(var connection=dataSource.getConnection();var zip=new ZipOutputStream(output,java.nio.charset.StandardCharsets.UTF_8)) {
            connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
            var entries=new LinkedHashMap<String,Object>();
            for(var query:queries().entrySet()) {
                String name=query.getKey()+".json"; var digest=MessageDigest.getInstance("SHA-256");
                zip.putNextEntry(new ZipEntry(name)); var entryOutput=new DigestOutputStream(zip,digest); long count=0;
                entryOutput.write('[');
                try(var statement=connection.prepareStatement(query.getValue())) {
                    int parameters=(int)query.getValue().chars().filter(c->c=='?').count();
                    for(int index=1;index<=parameters;index++) statement.setLong(index,userId);
                    statement.setFetchSize("MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())?Integer.MIN_VALUE:500);
                    try(var rows=statement.executeQuery()) {
                        var metadata=rows.getMetaData();
                        while(rows.next()) {
                            if(count++>0) entryOutput.write(','); var row=new LinkedHashMap<String,Object>();
                            for(int column=1;column<=metadata.getColumnCount();column++) {
                                String label=metadata.getColumnLabel(column).toLowerCase(Locale.ROOT); Object value=rows.getObject(column);
                                if(value!=null && BOOLEANS.contains(label)) value=rows.getBoolean(column);
                                else if(value instanceof java.sql.Timestamp timestamp) value=timestamp.toLocalDateTime().toString();
                                else if(value instanceof java.sql.Date date) value=date.toLocalDate().toString();
                                else if(value instanceof Clob || "JSON".equalsIgnoreCase(metadata.getColumnTypeName(column))) value=rows.getString(column);
                                row.put(label,value);
                            }
                            entryOutput.write(json.writeValueAsBytes(row));
                        }
                    }
                }
                entryOutput.write(']'); zip.closeEntry();
                entries.put(name,Map.of("rows",count,"sha256",HexFormat.of().formatHex(digest.digest())));
            }
            var manifest=new LinkedHashMap<String,Object>(); manifest.put("format","wordtrail-personal-learning"); manifest.put("version",1);
            manifest.put("exportedAtUtc",clock.instant().toString()); manifest.put("learningTimezone","Asia/Shanghai");
            manifest.put("entries",entries); manifest.put("complete",true);
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(json.writeValueAsBytes(manifest)); zip.closeEntry();
            connection.rollback(); zip.finish();
        } catch(SQLException ex) { throw new IOException("Personal learning export did not complete; SQLState="+ex.getSQLState()+" code="+ex.getErrorCode()); }
        catch(NoSuchAlgorithmException ex) { throw new IOException("Export checksum algorithm unavailable"); }
    }
}
