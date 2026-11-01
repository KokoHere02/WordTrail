package com.wordtrail.sentence.controller;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.service.SentenceEditorAccess;
import com.wordtrail.sentence.service.SentenceFavoriteService;
import com.wordtrail.sentence.dto.SentenceFavoriteRequest;
import com.wordtrail.sentence.vo.SentenceTranslationVO.Favorite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sentence-practice")
@RequiredArgsConstructor
public class SentencePracticeController {
    private final JdbcTemplate db;
    private final SqlSessionTemplate sqlSession;
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceEditorAccess editor;
    private final SentenceFavoriteService favorites;
    public record Submit(@NotBlank @Size(max=2000) String translation, @NotBlank @Size(max=64) @Pattern(regexp="[A-Za-z0-9_-]+") String requestId) {}
    public record Tags(@NotNull @Size(max=30) List<@NotNull @Positive Long> tagIds) {}
    public record Sentence(long id,String englishText,String referenceTranslation,String explanation,String source,String sourceReference,boolean learning,boolean favorite,boolean available) {}
    public record Tag(long id,String category,String name) {}
    public record Record(long id,String translation,java.time.LocalDateTime createdAt) {}
    public record Result(long sentenceId,String referenceTranslation,String explanation) {}
    public record PracticeRecord(long id,long sentenceId,String englishText,String translation,String referenceTranslation,String explanation,java.time.LocalDateTime createdAt) {}
    public record PracticeDay(LocalDate date,long submissions,long sentences) {}
    public record PracticeStatistics(long submissions,long sentences,long learningDays,long currentStreak,long longestStreak,List<PracticeDay> days) {}
    // MyBatis Flex owns the transaction connection. Use it for JDBC writes as well,
    // otherwise the second connection can wait on our own user lock and commit separately.
    private JdbcTemplate transactionDb() {
        return new JdbcTemplate(new SingleConnectionDataSource(sqlSession.getConnection(), true));
    }
    private BusinessException error(HttpStatus status,String code,String message){return new BusinessException(status,code,message);}
    private void existing(long id){if(db.queryForObject("SELECT COUNT(*) FROM sentence_question WHERE id=?",Long.class,id)==0) throw error(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","句子不存在");}

    @GetMapping("/tags") public ApiResponse<List<Tag>> tags(){return ApiResponse.ok(db.query("SELECT id,category,name FROM sentence_tag ORDER BY id",(r,n)->new Tag(r.getLong(1),r.getString(2),r.getString(3))));}
    @PutMapping("/sentences/{id}/favorite") public ApiResponse<Favorite> favorite(@PathVariable @Positive long id,@Valid @RequestBody SentenceFavoriteRequest request){return ApiResponse.ok(favorites.set(id,request.favorite()));}
    @GetMapping("/sentences") public ApiResponse<List<Sentence>> list(@RequestParam(defaultValue="bank") String scope,@RequestParam(required=false) Long tagId,@RequestParam(required=false) Long wordId,@RequestParam(defaultValue="0") @Min(0) int offset,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){
        if(offset<0 || limit<1 || limit>100 || (tagId!=null && tagId<=0) || (wordId!=null && wordId<=0)) throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","筛选或分页参数无效");
        if(!Set.of("bank","learning","favorites").contains(scope)) throw error(HttpStatus.BAD_REQUEST,"INVALID_SCOPE","无效的范围");
        String filter=switch(scope){case "learning" -> " AND EXISTS (SELECT 1 FROM user_sentence_learning l WHERE l.user_id=? AND l.sentence_id=q.id)";case "favorites" -> " AND EXISTS (SELECT 1 FROM user_sentence_favorite f WHERE f.user_id=? AND f.sentence_id=q.id)";default -> "";};
        var args=new ArrayList<Object>(); args.add(user.id());args.add(user.id()); if(!scope.equals("bank"))args.add(user.id());
        if(tagId!=null){filter+=" AND EXISTS (SELECT 1 FROM sentence_tag_relation t WHERE t.sentence_id=q.id AND t.tag_id=?)";args.add(tagId);}
        if(wordId!=null){filter+=" AND EXISTS (SELECT 1 FROM sentence_word w WHERE w.sentence_id=q.id AND w.word_id=?)";args.add(wordId);}
        args.add(limit);args.add(offset);
        String sql="SELECT q.id,q.english_text,q.reference_translation,q.explanation,q.source,q.source_reference, EXISTS(SELECT 1 FROM user_sentence_learning l WHERE l.user_id=? AND l.sentence_id=q.id), EXISTS(SELECT 1 FROM user_sentence_favorite f WHERE f.user_id=? AND f.sentence_id=q.id), q.status FROM sentence_question q WHERE 1=1"+(scope.equals("bank")?" AND q.status='ACTIVE'":"")+filter+" ORDER BY q.id DESC LIMIT ? OFFSET ?";
        return ApiResponse.ok(db.query(sql,(r,n)->new Sentence(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7),r.getBoolean(8),"ACTIVE".equals(r.getString(9))),args.toArray()));
    }
    @GetMapping("/learning/random") public ApiResponse<List<Sentence>> random(@RequestParam(defaultValue="10") int count,@RequestParam(required=false) Long tagId,@RequestParam(required=false) Long wordId){
        if(count<1 || count>50)throw error(HttpStatus.BAD_REQUEST,"INVALID_COUNT","数量应为 1 至 50");
        String sql="SELECT q.id,q.english_text,q.reference_translation,q.explanation,q.source,q.source_reference, EXISTS(SELECT 1 FROM user_sentence_favorite f WHERE f.user_id=? AND f.sentence_id=q.id) FROM sentence_question q JOIN user_sentence_learning l ON l.sentence_id=q.id WHERE l.user_id=? AND q.status='ACTIVE'";
        var args=new ArrayList<Object>();args.add(user.id());args.add(user.id());
        if(tagId!=null){sql+=" AND EXISTS (SELECT 1 FROM sentence_tag_relation t WHERE t.sentence_id=q.id AND t.tag_id=?)";args.add(tagId);}
        if(wordId!=null){sql+=" AND EXISTS (SELECT 1 FROM sentence_word w WHERE w.sentence_id=q.id AND w.word_id=?)";args.add(wordId);}
        args.add(count);sql+=" ORDER BY RAND() LIMIT ?";
        return ApiResponse.ok(db.query(sql,(r,n)->new Sentence(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),true,r.getBoolean(7),true),args.toArray()));
    }
    @GetMapping("/sentences/{id}/tags") public ApiResponse<List<Tag>> sentenceTags(@PathVariable long id){existing(id);return ApiResponse.ok(db.query("SELECT t.id,t.category,t.name FROM sentence_tag t JOIN sentence_tag_relation r ON r.tag_id=t.id WHERE r.sentence_id=? ORDER BY t.id",(r,n)->new Tag(r.getLong(1),r.getString(2),r.getString(3)),id));}
    @PutMapping("/sentences/{id}/tags") @Transactional public ApiResponse<Void> setTags(@PathVariable long id,@Valid @RequestBody Tags body){editor.require();context.lockUser(user.id());var db=transactionDb();existing(id);var ids=new LinkedHashSet<>(body.tagIds());for(long tag:ids) if(db.queryForObject("SELECT COUNT(*) FROM sentence_tag WHERE id=?",Long.class,tag)==0)throw error(HttpStatus.BAD_REQUEST,"INVALID_TAG","标签不存在");db.update("DELETE FROM sentence_tag_relation WHERE sentence_id=?",id);for(long tag:ids)db.update("INSERT INTO sentence_tag_relation(sentence_id,tag_id) VALUES (?,?)",id,tag);return ApiResponse.ok(null);}
    @PutMapping("/learning/{id}") @Transactional public ApiResponse<Void> add(@PathVariable long id){context.lockUser(user.id());var db=transactionDb();if(db.queryForObject("SELECT COUNT(*) FROM sentence_question WHERE id=? AND status='ACTIVE'",Long.class,id)==0)throw error(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","句子不存在或已停用");if(db.queryForObject("SELECT COUNT(*) FROM user_sentence_learning WHERE user_id=? AND sentence_id=?",Long.class,user.id(),id)==0)db.update("INSERT INTO user_sentence_learning(user_id,sentence_id,added_at) VALUES (?,?,?)",user.id(),id,context.now());return ApiResponse.ok(null);}
    @DeleteMapping("/learning/{id}") @Transactional public ApiResponse<Void> remove(@PathVariable long id){context.lockUser(user.id());var db=transactionDb();db.update("DELETE FROM user_sentence_learning WHERE user_id=? AND sentence_id=?",user.id(),id);return ApiResponse.ok(null);}
    @PostMapping("/sentences/{id}/translations") @Transactional public ApiResponse<Result> submit(@PathVariable long id,@Valid @RequestBody Submit body){
        context.lockUser(user.id());
        var db=transactionDb();
        var previous=db.query("SELECT sentence_id,translation FROM sentence_translation_record WHERE user_id=? AND request_id=?",(r,n)->Map.entry(r.getLong(1),r.getString(2)),user.id(),body.requestId());
        if(!previous.isEmpty()){var p=previous.get(0);if(p.getKey()!=id || !p.getValue().equals(body.translation()))throw error(HttpStatus.CONFLICT,"REQUEST_ID_REUSED","请求标识已用于其他译文");return ApiResponse.ok(result(id));}
        if(db.queryForObject("SELECT COUNT(*) FROM user_sentence_learning l JOIN sentence_question q ON q.id=l.sentence_id WHERE l.user_id=? AND l.sentence_id=? AND q.status='ACTIVE'",Long.class,user.id(),id)==0)throw error(HttpStatus.CONFLICT,"SENTENCE_NOT_IN_LEARNING","句子不在学习列表或已停用");
        db.update("INSERT INTO sentence_translation_record(user_id,sentence_id,translation,request_id,created_at) VALUES (?,?,?,?,?)",user.id(),id,body.translation(),body.requestId(),context.now());
        db.update("DELETE FROM user_sentence_learning WHERE user_id=? AND sentence_id=?",user.id(),id);return ApiResponse.ok(result(id));
    }
    private Result result(long id){return db.query("SELECT id,reference_translation,explanation FROM sentence_question WHERE id=?",(r,n)->new Result(r.getLong(1),r.getString(2),r.getString(3)),id).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","句子不存在"));}
    @GetMapping("/sentences/{id}/history") public ApiResponse<List<Record>> history(@PathVariable long id,@RequestParam(defaultValue="0") @Min(0) int offset,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){if(offset<0 || limit<1 || limit>100)throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","分页参数无效");existing(id);return ApiResponse.ok(db.query("SELECT id,translation,created_at FROM sentence_translation_record WHERE user_id=? AND sentence_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->new Record(r.getLong(1),r.getString(2),r.getTimestamp(3).toLocalDateTime()),user.id(),id,limit,offset));}
    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","日期格式应为 YYYY-MM-DD"); }
    }
    private static PracticeRecord practiceRecord(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new PracticeRecord(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toLocalDateTime());
    }
    @GetMapping("/records/{recordId}") public ApiResponse<PracticeRecord> record(@PathVariable long recordId) {
        var rows=db.query("SELECT r.id,q.id,q.english_text,r.translation,q.reference_translation,q.explanation,r.created_at FROM sentence_translation_record r JOIN sentence_question q ON q.id=r.sentence_id WHERE r.user_id=? AND r.id=?",SentencePracticeController::practiceRecord,user.id(),recordId);
        return ApiResponse.ok(rows.stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"RECORD_NOT_FOUND","记录不存在")));
    }
    @GetMapping("/records") public ApiResponse<PageVO<PracticeRecord>> records(@RequestParam(required=false) String date,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        if(page<1 || size<1 || size>100) throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","分页参数无效");
        var args=new ArrayList<Object>(); args.add(user.id());
        String filter=" WHERE r.user_id=?";
        if(date!=null) { var day=parseDate(date); filter+=" AND r.created_at>=? AND r.created_at<?"; args.add(day.atStartOfDay()); args.add(day.plusDays(1).atStartOfDay()); }
        long total=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM sentence_translation_record r"+filter,Long.class,args.toArray()));
        args.add(size); args.add(((long)page-1)*size);
        var rows=db.query("SELECT r.id,q.id,q.english_text,r.translation,q.reference_translation,q.explanation,r.created_at FROM sentence_translation_record r JOIN sentence_question q ON q.id=r.sentence_id"+filter+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",SentencePracticeController::practiceRecord,args.toArray());
        return ApiResponse.ok(new PageVO<>(page,size,total,rows));
    }
    @GetMapping("/statistics") public ApiResponse<PracticeStatistics> statistics(@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        var today=context.today(); var start=from==null?today.minusDays(29):parseDate(from); var end=to==null?today:parseDate(to);
        if(end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS.between(start,end)>365) throw error(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","统计范围应为 1 至 366 天");
        long uid=user.id();
        var totals=db.queryForObject("SELECT COUNT(*),COUNT(DISTINCT sentence_id) FROM sentence_translation_record WHERE user_id=?",(r,n)->new long[]{r.getLong(1),r.getLong(2)},uid);
        var dates=db.query("SELECT DATE(created_at) FROM sentence_translation_record WHERE user_id=? AND created_at<? GROUP BY DATE(created_at) ORDER BY DATE(created_at)",(r,n)->r.getDate(1).toLocalDate(),uid,today.plusDays(1).atStartOfDay());
        long run=0,longest=0; LocalDate previous=null; for(var day:dates){run=previous!=null && day.equals(previous.plusDays(1))?run+1:1; longest=Math.max(longest,run); previous=day;}
        long current=previous!=null && !previous.isBefore(today.minusDays(1))?run:0;
        var days=db.query("SELECT DATE(created_at),COUNT(*),COUNT(DISTINCT sentence_id) FROM sentence_translation_record WHERE user_id=? AND created_at>=? AND created_at<? GROUP BY DATE(created_at) ORDER BY DATE(created_at)",(r,n)->new PracticeDay(r.getDate(1).toLocalDate(),r.getLong(2),r.getLong(3)),uid,start.atStartOfDay(),end.plusDays(1).atStartOfDay());
        return ApiResponse.ok(new PracticeStatistics(totals[0],totals[1],dates.size(),current,longest,days));
    }

}
