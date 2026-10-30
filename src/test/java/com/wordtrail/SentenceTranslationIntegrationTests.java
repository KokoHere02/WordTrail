package com.wordtrail;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.learning.entity.UserWordState;
import com.wordtrail.learning.entity.LearningBatch;
import com.wordtrail.learning.entity.LearningBatchItem;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.security.*;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.user.entity.UserAccount;
import com.wordtrail.user.mapper.*;
import com.wordtrail.word.entity.Word;
import com.wordtrail.word.mapper.WordMapper;
import io.jsonwebtoken.Jwts;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.wordtrail.sentence.service.SentenceEditorAccess;
import com.wordtrail.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import static org.mockito.Mockito.*;
import tools.jackson.databind.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LearningIntegrationTests.Instrumentation.class)
class SentenceTranslationIntegrationTests {
    private static final String BASE="/api/sentence-translation";
    private static final String SECRET=Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.jwt.secret",()->SECRET); }
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenService jwt;
    @Autowired PasswordService passwords;
    @Autowired DataSource dataSource;
    @Autowired UserMapper users;
    @Autowired RefreshTokenMapper refresh;
    @Autowired AuthSessionMapper authSessions;
    @Autowired SentenceNoteMapper notes;
    @Autowired SentenceNoteTagMapper noteTags;
    @Autowired com.wordtrail.book.mapper.WordBookMapper wordBooks;
    @Autowired com.wordtrail.book.mapper.WordBookItemMapper wordBookItems;
    @Autowired WordMapper words;
    @Autowired UserWordStateMapper states;
    @Autowired LearningBatchMapper learningBatches;
    @Autowired LearningBatchItemMapper learningItems;
    @Autowired SentenceQuestionMapper questions;
    @Autowired SentenceSessionMapper sessions;
    @Autowired SentenceItemMapper items;
    @Autowired SentenceAttemptMapper attempts;
    @Autowired SentenceFavoriteMapper favorites;
    @Autowired SentenceReviewStateMapper reviewStates;
    @Autowired SentenceItemWordMapper itemWords;
    @Autowired SentenceImportBatchMapper importBatches;
    @Autowired SentenceImportItemMapper importItems;
    @Autowired CurrentUser currentUser;
    @MockitoBean SentenceEditorAccess editorAccess;
    @Autowired LearningIntegrationTests.DatabaseProbe probe;
    @Autowired LearningIntegrationTests.StudyClock clock;
    private final HttpClient client=HttpClient.newHttpClient();
    private final List<Long> userIds=new ArrayList<>(),questionIds=new ArrayList<>();
    private final List<Long> collectionBooks=new ArrayList<>(),extraWords=new ArrayList<>();
    private long a,b,wordId;
    private String tokenA,tokenB,prefix;

    @BeforeEach void seed() throws Exception {
        IsolatedDatabaseGuard.check(dataSource); probe.failAfterMapper=null; clock.reset();
        prefix="sentence"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        for(int index=0;index<2;index++) {
            var user=new UserAccount(); user.setUsername(prefix+index); user.setPasswordHash("unused-fixture");
            users.insertSelective(user); userIds.add(user.getId());
        }
        a=userIds.get(0); b=userIds.get(1); tokenA=jwt.issue(a); tokenB=jwt.issue(b);
        when(editorAccess.allowed()).thenAnswer(inv->currentUser.id()==a);
        doAnswer(inv->{if(currentUser.id()!=a) throw new BusinessException(HttpStatus.FORBIDDEN,"SENTENCE_EDITOR_REQUIRED","当前账号没有题库维护权限");return null;}).when(editorAccess).require();
        var word=new Word(); word.setWord(prefix); word.setTranslation("fixture translation"); words.insertSelective(word); wordId=word.getId();
        for(int index=0;index<4;index++) {
            var question=new SentenceQuestion(); question.setEnglishText("Example "+prefix+index+".");
            question.setReferenceTranslation("原始参考译文"+index); question.setExplanation("原始解析"+index);
            question.setSource(prefix); question.setSourceReference("original-fixture"); question.setDifficulty(index==2?3:1);
            question.setStatus(index==3?"INACTIVE":"ACTIVE"); questions.insertSelective(question); questionIds.add(question.getId());
        }
        try(var c=dataSource.getConnection();var s=c.prepareStatement("INSERT INTO sentence_word(sentence_id,word_id) VALUES(?,?)")) {
            for(long id:questionIds.subList(0,2)) { s.setLong(1,id); s.setLong(2,wordId); s.executeUpdate(); }
        }
    }
    @AfterEach void cleanup() throws Exception {
        probe.failAfterMapper=null;
        if(!userIds.isEmpty()) {
            var where=QueryWrapper.create().where(column("user_id").in(userIds));
            var noteIds=notes.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds))).stream().map(SentenceNote::getId).toList();
            if(!noteIds.isEmpty()) noteTags.deleteByQuery(QueryWrapper.create().where(column("note_id").in(noteIds)));
            notes.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            reviewStates.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var importIds=importBatches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds))).stream().map(com.wordtrail.sentence.entity.SentenceImportBatch::getId).toList();
            if(!importIds.isEmpty()) importItems.deleteByQuery(QueryWrapper.create().where(column("batch_id").in(importIds)));
            importBatches.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            attempts.deleteByQuery(where);
            favorites.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var ids=sessions.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds))).stream().map(SentenceSession::getId).toList();
            if(!ids.isEmpty()) {
                var itemIds=items.selectListByQuery(QueryWrapper.create().where(column("session_id").in(ids))).stream().map(SentenceSessionItem::getId).toList();
                if(!itemIds.isEmpty()) itemWords.deleteByQuery(QueryWrapper.create().where(column("item_id").in(itemIds)));
                items.deleteByQuery(QueryWrapper.create().where(column("session_id").in(ids)));
            }
            sessions.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            states.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var batchIds=learningBatches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
                .stream().map(LearningBatch::getId).toList();
            if(!batchIds.isEmpty()) learningItems.deleteByQuery(QueryWrapper.create().where(column("batch_id").in(batchIds)));
            learningBatches.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            refresh.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            authSessions.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
        }
        if(!questionIds.isEmpty()) {
            try(var c=dataSource.getConnection();var s=c.prepareStatement("DELETE FROM sentence_word WHERE sentence_id=?")) {
                for(long id:questionIds) { s.setLong(1,id); s.executeUpdate(); }
            }
            questions.deleteBatchByIds(questionIds);
        }
        if(!collectionBooks.isEmpty()){wordBookItems.deleteByQuery(QueryWrapper.create().where(column("book_id").in(collectionBooks)));wordBooks.deleteBatchByIds(collectionBooks);}
        if(wordId!=0) words.deleteById(wordId);
        if(!extraWords.isEmpty()) words.deleteBatchByIds(extraWords);
        if(!userIds.isEmpty()) users.deleteBatchByIds(userIds);
    }
    private HttpResponse<String> call(String method,String path,Object body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+path));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method,String path,Object body,String token,int status) throws Exception {
        var response=call(method,path,body,token); assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private JsonNode get(String path) throws Exception { return ok("GET",BASE+path,null,tokenA,200); }
    private void error(String method,String path,Object body,String token,int status,String code) throws Exception {
        var response=call(method,BASE+path,body,token); assertEquals(status,response.statusCode(),response.body());
        assertEquals(code,json.readTree(response.body()).get("code").asString());
    }
    private long create(long... selected) throws Exception {
        return ok("POST",BASE+"/sessions",Map.of("sentenceIds",Arrays.stream(selected).boxed().toList()),tokenA,201).get("sessionId").asLong();
    }
    private JsonNode current(long session) throws Exception { return get("/sessions/"+session+"/current"); }
    private String path(long session,long item,String action) { return "/sessions/"+session+"/items/"+item+"/"+action; }
    private Map<String,Object> answer(String key) { return Map.of("action","ANSWER","translation","我的不同措辞译文","requestId",key); }
    private Map<String,Object> review(String key,String assessment) { return Map.of("assessment",assessment,"requestId",key); }
    private JsonNode submit(long session,long item,Object body) throws Exception { return ok("PUT",BASE+path(session,item,"submission"),body,tokenA,200); }
    private JsonNode assess(long session,long item,String key,String assessment) throws Exception { return ok("PUT",BASE+path(session,item,"self-review"),review(key,assessment),tokenA,200); }
    private void finish(long session,String assessment,String key) throws Exception {
        long item=current(session).get("itemId").asLong(); submit(session,item,answer(key+"_submit")); assess(session,item,key+"_review",assessment);
    }
    private long countAttempts() { return attempts.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))); }
    private long countSessions() { return sessions.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))); }

    private LearningBatch recordBatch(long user, String type, String mode, String status, LocalDate date, LocalDateTime created) {
        var batch=new LearningBatch(); batch.setUserId(user); batch.setTaskType(type);
        batch.setSelectionType(type.equals("DAILY")?"DAILY":"MANUAL");
        batch.setPracticeMode(type.equals("DAILY")?"RECOGNITION":"DICTATION");
        batch.setStudyMode(mode); batch.setStatus(status); batch.setLearningDate(date);
        if(date!=null) batch.setDailyTarget(3);
        batch.setCreatedAt(created); batch.setUpdatedAt(created);
        if(status.equals("COMPLETED")) batch.setCompletedAt(created);
        learningBatches.insertSelective(batch); return batch;
    }
    private void recordItem(LearningBatch batch,long word,int order,String type,String status,LocalDateTime now) {
        var entry=new LearningBatchItem(); entry.setBatchId(batch.getId()); entry.setWordId(word); entry.setSortOrder(order);
        entry.setQueueOrder((long)order); entry.setItemType(type); entry.setLearningStatus(status);
        if(status.equals("SKIPPED")) { entry.setStatusBeforeSkip("PENDING"); entry.setSkippedAt(now); }
        learningItems.insertSelective(entry);
    }

    @Test void combinedLearningRecordsFilterPageAndPreserveModuleSemantics() throws Exception {
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")).withNano(0);
        var today=now.toLocalDate();
        var another=new Word(); another.setWord(prefix+"records"); another.setTranslation("record"); words.insertSelective(another); extraWords.add(another.getId());
        var daily=recordBatch(a,"DAILY","RECOGNITION","COMPLETED",today,now);
        recordItem(daily,wordId,1,"NEW","DONE",now); recordItem(daily,another.getId(),2,"REVIEW","SKIPPED",now);
        var expired=recordBatch(a,"DAILY","RECOGNITION","IN_PROGRESS",today.minusDays(1),now.minusDays(1));
        recordItem(expired,wordId,1,"NEW","PENDING",now);
        var test=recordBatch(a,"PRACTICE","DICTATION","IN_PROGRESS",null,now);
        recordItem(test,wordId,1,null,"DONE",now); recordItem(test,another.getId(),2,null,"PENDING",now);
        var legacy=recordBatch(a,"PRACTICE","RECOGNITION","IN_PROGRESS",null,now.minusHours(1));
        recordItem(legacy,wordId,1,null,"PENDING",now);
        var other=recordBatch(b,"DAILY","RECOGNITION","IN_PROGRESS",today,now.plusSeconds(1));
        recordItem(other,wordId,1,"NEW","PENDING",now);
        long session=create(questionIds.get(0),questionIds.get(1));
        long first=current(session).get("itemId").asLong(); submit(session,first,answer("records_submit"));

        String endpoint="/api/learning/records";
        var all=ok("GET",endpoint,null,tokenA,200); assertEquals(5,all.get("total").asLong());
        var pageOne=ok("GET",endpoint+"?size=2&page=1",null,tokenA,200);
        var pageTwo=ok("GET",endpoint+"?size=2&page=2",null,tokenA,200);
        var pageThree=ok("GET",endpoint+"?size=2&page=3",null,tokenA,200);
        var combined=new ArrayList<String>();
        for(var page:List.of(pageOne,pageTwo,pageThree)) for(var row:page.get("records"))
            combined.add(row.get("type").asString()+":"+row.get("businessId").asLong());
        var full=new ArrayList<String>();
        for(var row:all.get("records")) full.add(row.get("type").asString()+":"+row.get("businessId").asLong());
        assertEquals(full,combined); assertEquals(5,new HashSet<>(combined).size());
        assertEquals(List.of("DAILY:"+daily.getId(),"DICTATION:"+test.getId(),"SENTENCE:"+session),full.subList(0,3));

        var dailyRow=ok("GET",endpoint+"?type=DAILY&status=COMPLETED",null,tokenA,200).get("records").get(0);
        assertEquals(1,dailyRow.get("completedCount").asLong()); assertEquals(2,dailyRow.get("totalCount").asLong());
        assertEquals(1,dailyRow.get("skippedCount").asLong()); assertEquals(1,dailyRow.get("newCount").asLong());
        assertEquals(1,dailyRow.get("reviewCount").asLong()); assertEquals(3,dailyRow.get("dailyTarget").asInt());
        assertFalse(dailyRow.get("goalReached").asBoolean()); assertFalse(dailyRow.get("canContinue").asBoolean());
        var incomplete=ok("GET",endpoint+"?status=INCOMPLETE",null,tokenA,200);
        assertEquals(2,incomplete.get("total").asLong());
        for(var row:incomplete.get("records")) { assertEquals("IN_PROGRESS",row.get("rawStatus").asString()); assertFalse(row.get("canContinue").asBoolean()); }
        var dictation=ok("GET",endpoint+"?type=DICTATION&status=IN_PROGRESS",null,tokenA,200).get("records").get(0);
        assertTrue(dictation.get("singleAttempt").asBoolean()); assertTrue(dictation.get("canContinue").asBoolean());
        assertTrue(dictation.get("source").isNull()); assertEquals(1,dictation.get("completedCount").asLong());
        var sentence=ok("GET",endpoint+"?type=SENTENCE",null,tokenA,200).get("records").get(0);
        assertEquals("BANK",sentence.get("source").asString()); assertEquals(0,sentence.get("completedCount").asLong());
        assertEquals(2,sentence.get("totalCount").asLong()); assertTrue(sentence.get("canContinue").asBoolean());
        assess(session,first,"records_review","PARTIAL");
        assertEquals(1,ok("GET",endpoint+"?type=SENTENCE",null,tokenA,200).get("records").get(0).get("completedCount").asLong());
        assertEquals(1,ok("GET",endpoint,null,tokenB,200).get("total").asLong());
        assertEquals(0,ok("GET",endpoint+"?page=2147483647",null,tokenA,200).get("records").size());
        assertEquals(400,call("GET",endpoint+"?size=101",null,tokenA).statusCode());
        assertEquals(400,call("GET",endpoint+"?type=INVALID",null,tokenA).statusCode());
        assertEquals(401,call("GET",endpoint,null,null).statusCode());

        daily.setStatus("IN_PROGRESS"); daily.setDailyTarget(1); learningBatches.update(daily);
        var active=ok("GET",endpoint+"?type=DAILY&status=IN_PROGRESS",null,tokenA,200).get("records").get(0);
        assertTrue(active.get("canContinue").asBoolean()); assertTrue(active.get("goalReached").asBoolean());
        test.setStatus("ABANDONED"); learningBatches.update(test);
        var abandoned=ok("GET",endpoint+"?type=DICTATION&status=ABANDONED",null,tokenA,200).get("records").get(0);
        assertEquals("ABANDONED",abandoned.get("rawStatus").asString()); assertFalse(abandoned.get("canContinue").asBoolean());
        clock.advanceSeconds(86400); tokenA=jwt.issue(a);
        var expiredDaily=ok("GET",endpoint+"?type=DAILY&status=INCOMPLETE",null,tokenA,200);
        assertEquals(2,expiredDaily.get("total").asLong()); assertFalse(expiredDaily.get("records").get(0).get("canContinue").asBoolean());
    }

    @Test void questionDiscoveryHidesAllReferencesAndSupportsPagingAndLinkedWordFilter() throws Exception {
        var page=get("/questions?wordId="+wordId+"&size=1"); assertEquals(2,page.get("total").asLong());
        var first=page.get("records").get(0); assertEquals(questionIds.get(0).longValue(),first.get("sentenceId").asLong());
        assertFalse(first.has("referenceTranslation")); assertFalse(first.has("explanation"));
        assertFalse(page.toString().contains("原始"));
        assertEquals(questionIds.get(1).longValue(),get("/questions?wordId="+wordId+"&page=2&size=1").get("records").get(0).get("sentenceId").asLong());
        assertEquals(0,get("/questions?wordId="+wordId+"&difficulty=3").get("total").asLong());
        assertEquals(0,get("/questions?wordId="+wordId+"&page=2147483647").get("records").size());
        error("GET","/questions",null,null,401,"UNAUTHENTICATED");
    }

    @Test void manualSelectionIsAtomicDeduplicatedOrderedAndDoesNotFabricateAttempts() throws Exception {
        long session=create(questionIds.get(1),questionIds.get(0),questionIds.get(1));
        assertEquals(2,get("/sessions/"+session).get("totalCount").asLong());
        assertEquals(questionIds.get(1).longValue(),current(session).get("sentenceId").asLong()); assertEquals(0,countAttempts());
        assertTrue(current(session).get("referenceTranslation").isNull()); assertTrue(current(session).get("explanation").isNull());
        long before=countSessions();
        for(var ids:List.of(List.of(questionIds.get(0),Long.MAX_VALUE),List.of(questionIds.get(0),questionIds.get(3))))
            error("POST","/sessions",Map.of("sentenceIds",ids),tokenA,409,"SENTENCE_UNAVAILABLE");
        error("POST","/sessions",Map.of("sentenceIds",List.of()),tokenA,409,"INVALID_SENTENCE_SELECTION");
        error("POST","/sessions",Map.of("sentenceIds",List.of(questionIds.get(0)),"count",1),tokenA,409,"INVALID_SENTENCE_SELECTION");
        error("POST","/sessions",Map.of("source","REVIEW"),tokenA,409,"NO_SENTENCE_QUESTIONS");
        assertEquals(before,countSessions());
    }

    @Test void translationSubmissionThenSelfReviewCompletesFlowWithoutAutomaticGrading() throws Exception {
        long session=create(questionIds.get(0),questionIds.get(1)); long first=current(session).get("itemId").asLong();
        error("GET","/sessions/"+session+"/result",null,tokenA,409,"SENTENCE_SESSION_NOT_COMPLETED");
        error("PUT",path(session,first,"self-review"),review("early_review","UNDERSTOOD"),tokenA,409,"SELF_REVIEW_NOT_ALLOWED");
        var feedback=submit(session,first,answer("first_submit"));
        assertEquals("原始参考译文0",feedback.get("referenceTranslation").asString());
        assertEquals("我的不同措辞译文",feedback.get("translation").asString()); assertFalse(feedback.has("correct")); assertFalse(feedback.has("score"));
        assertEquals(1,feedback.get("progress").get("answerCount").asLong()); assertEquals(0,feedback.get("progress").get("completedCount").asLong());
        assertEquals("AWAITING_REVIEW",current(session).get("status").asString()); assertEquals(first,current(session).get("itemId").asLong());
        assess(session,first,"first_review","PARTIAL");
        assertEquals(questionIds.get(1).longValue(),current(session).get("sentenceId").asLong()); finish(session,"UNDERSTOOD","second");
        var progress=get("/sessions/"+session); assertEquals("COMPLETED",progress.get("status").asString());
        for(String field:List.of("totalCount","submissionCount","answerCount","selfReviewCount","completedCount")) assertEquals(2,progress.get(field).asLong(),field);
        assertEquals(1,progress.get("partialCount").asLong()); assertEquals(1,progress.get("understoodCount").asLong());
        assertTrue(current(session).isNull()); assertEquals(2,get("/sessions/"+session+"/result").get("total").asLong());
        assertEquals(1,get("/sessions?status=COMPLETED").get("total").asLong());
    }

    @Test void revealIsARealSubmissionButNotATypedAnswerAndStillNeedsSelfReview() throws Exception {
        long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong();
        var feedback=submit(session,item,Map.of("action","REVEAL","requestId","reveal_submit"));
        assertTrue(feedback.get("translation").isNull()); assertEquals(1,feedback.get("progress").get("revealCount").asLong());
        assertEquals(0,feedback.get("progress").get("answerCount").asLong()); assertEquals("IN_PROGRESS",feedback.get("progress").get("status").asString());
        var reviewed=assess(session,item,"reveal_review","NOT_UNDERSTOOD"); assertEquals(1,reviewed.get("progress").get("completedCount").asLong());
        assertEquals(1,get("/review-queue").get("total").asLong());
    }

    @Test void submissionAndReviewRetriesCountOnceAndCannotRewriteInputOrAssessment() throws Exception {
        long session=create(questionIds.get(0),questionIds.get(1)); long item=current(session).get("itemId").asLong();
        var body=answer("retry_submit"); var first=submit(session,item,body); assertEquals(first,submit(session,item,body));
        assess(session,item,"retry_review","PARTIAL"); assertEquals(1,countAttempts());
        var replay=submit(session,item,body); assertEquals(first.get("attemptId"),replay.get("attemptId"));
        assertEquals(1,replay.get("progress").get("completedCount").asLong());
        assertEquals("PARTIAL",replay.get("selfAssessment").asString());
        error("PUT",path(session,item,"submission"),Map.of("action","ANSWER","translation","试图篡改原文","requestId","retry_submit"),tokenA,409,"REQUEST_ID_REUSED");
        error("PUT",path(session,item,"self-review"),review("retry_review","UNDERSTOOD"),tokenA,409,"REQUEST_ID_REUSED");
        error("PUT",path(session,item,"self-review"),review("new_review_key","UNDERSTOOD"),tokenA,409,"SELF_REVIEW_NOT_ALLOWED");
        long next=current(session).get("itemId").asLong(); error("PUT",path(session,next,"submission"),body,tokenA,409,"REQUEST_ID_REUSED");
        finish(session,"UNDERSTOOD","finish_retry");
        assertEquals("COMPLETED",assess(session,item,"retry_review","PARTIAL").get("progress").get("status").asString());
        assertEquals(2,countAttempts());
    }

    @Test void outOfOrderAndOldPagesCannotSubmitOrReadUnsubmittedReferences() throws Exception {
        long session=create(questionIds.get(0),questionIds.get(1)); var entries=items.selectListByQuery(QueryWrapper.create().where(column("session_id").eq(session)).orderBy(column("sort_order").asc()));
        long first=entries.get(0).getId(),second=entries.get(1).getId();
        error("PUT",path(session,second,"submission"),answer("future_submit"),tokenA,409,"STALE_SENTENCE_ITEM");
        submit(session,first,answer("current_submit"));
        error("PUT",path(session,first,"submission"),answer("oldpage_submit"),tokenA,409,"STALE_SENTENCE_ITEM");
        assess(session,first,"current_review","UNDERSTOOD"); assertTrue(current(session).get("referenceTranslation").isNull());
        assertEquals(1,countAttempts());
    }

    @Test void concurrentRetriesSerializeAndUniqueItemsPreventDuplicateSubmissions() throws Exception {
        long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->call("PUT",BASE+path(session,item,"submission"),answer("parallel_submit"),tokenA));
            var second=executor.submit(()->call("PUT",BASE+path(session,item,"submission"),answer("parallel_submit"),tokenA));
            assertEquals(200,first.get().statusCode()); assertEquals(200,second.get().statusCode());
            var reviewA=executor.submit(()->call("PUT",BASE+path(session,item,"self-review"),review("parallel_review","PARTIAL"),tokenA));
            var reviewB=executor.submit(()->call("PUT",BASE+path(session,item,"self-review"),review("parallel_review","PARTIAL"),tokenA));
            assertEquals(200,reviewA.get().statusCode()); assertEquals(200,reviewB.get().statusCode());
        }
        assertEquals(1,countAttempts()); assertEquals(1,get("/sessions/"+session).get("selfReviewCount").asLong());
        long nextSession=create(questionIds.get(0)); long nextItem=current(nextSession).get("itemId").asLong();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var left=executor.submit(()->call("PUT",BASE+path(nextSession,nextItem,"submission"),answer("different_left"),tokenA));
            var right=executor.submit(()->call("PUT",BASE+path(nextSession,nextItem,"submission"),answer("different_right"),tokenA));
            var codes=new ArrayList<>(List.of(left.get().statusCode(),right.get().statusCode())); Collections.sort(codes); assertEquals(List.of(200,409),codes);
        }
        assertEquals(2,countAttempts());
    }

    @Test void allPrivateRoutesAndSelfReviewQueueAreUserIsolated() throws Exception {
        long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong();
        finish(session,"PARTIAL","private");
        for(String endpoint:List.of("/sessions/"+session,"/sessions/"+session+"/current","/sessions/"+session+"/result"))
            error("GET",endpoint,null,tokenB,404,"SENTENCE_SESSION_NOT_FOUND");
        error("PUT",path(session,item,"submission"),answer("private_submit"),tokenB,404,"SENTENCE_SESSION_NOT_FOUND");
        error("PUT",path(session,item,"self-review"),review("private_review","PARTIAL"),tokenB,404,"SENTENCE_SESSION_NOT_FOUND");
        for(String endpoint:List.of("/sessions?status=COMPLETED","/review-queue","/questions/"+questionIds.get(0)+"/history","/favorites"))
            assertEquals(0,ok("GET",BASE+endpoint,null,tokenB,200).get("total").asLong());
        error("POST","/sessions",Map.of("source","REVIEW","sentenceIds",List.of(questionIds.get(0))),tokenB,409,"SENTENCE_UNAVAILABLE");
    }

    @Test void dictionaryEditsAndWithdrawalCannotAlterAnExistingQuestionOrHistory() throws Exception {
        long sentenceId=questionIds.get(0); long session=create(sentenceId); long item=current(session).get("itemId").asLong();
        var question=questions.selectOneById(sentenceId); String original=question.getEnglishText();
        question.setEnglishText("changed"); question.setReferenceTranslation("changed reference"); question.setExplanation("changed explanation"); question.setStatus("INACTIVE"); questions.update(question);
        assertEquals(original,current(session).get("englishText").asString());
        assertEquals("原始参考译文0",submit(session,item,answer("snapshot_submit")).get("referenceTranslation").asString());
        assess(session,item,"snapshot_review","PARTIAL");
        var history=get("/questions/"+sentenceId+"/history").get("records").get(0);
        assertEquals(original,history.get("englishText").asString()); assertEquals("原始解析0",history.get("explanation").asString());
        assertFalse(get("/review-queue").get("records").get(0).get("available").asBoolean());
        error("POST","/sessions",Map.of("source","REVIEW","sentenceIds",List.of(sentenceId)),tokenA,409,"SENTENCE_UNAVAILABLE");
    }

    @Test void refreshRealLoginAndCrossMidnightPreservePendingSelfReviewAndSeparateDates() throws Exception {
        clock.at(Instant.parse("2026-10-24T15:59:50Z")); tokenA=jwt.issue(a);
        var user=users.selectOneById(a); user.setPasswordHash(passwords.hash("test-sentence-password")); users.update(user);
        long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong(); submit(session,item,answer("midnight_submit"));
        assertEquals("AWAITING_REVIEW",current(session).get("status").asString()); clock.advanceSeconds(20);
        var login=ok("POST","/api/auth/login",Map.of("username",user.getUsername(),"password","test-sentence-password"),null,200);
        tokenA=login.get("accessToken").asString(); assertEquals(item,current(session).get("itemId").asLong());
        assertEquals("我的不同措辞译文",current(session).get("translation").asString());
        var feedback=assess(session,item,"midnight_review","PARTIAL"); assertEquals("2026-10-24",feedback.get("learningDate").asString());
        assertEquals("2026-10-25",feedback.get("reviewDate").asString()); assertEquals(1,countAttempts());
    }

    @Test void reviewQueueUsesLatestEvaluatedRealAttemptAndNewRoundsDoNotClearIt() throws Exception {
        long sentence=questionIds.get(0); long first=create(sentence); finish(first,"PARTIAL","weak_first");
        long second=create(sentence); assertEquals(1,get("/review-queue").get("total").asLong());
        long item=current(second).get("itemId").asLong(); submit(second,item,answer("weak_second_submit"));
        assertEquals(1,get("/review-queue").get("total").asLong());
        var selected=ok("POST",BASE+"/sessions",Map.of("source","REVIEW","sentenceIds",List.of(sentence)),tokenA,201);
        assertEquals(1,selected.get("totalCount").asLong()); assess(second,item,"weak_second_review","UNDERSTOOD");
        assertEquals(0,get("/review-queue").get("total").asLong());
        assertEquals(2,get("/questions/"+sentence+"/history").get("total").asLong());
        var history=get("/questions/"+sentence+"/history?size=1"); assertEquals(second,history.get("records").get(0).get("sessionId").asLong());
        assertEquals(first,get("/questions/"+sentence+"/history?size=1&page=2").get("records").get(0).get("sessionId").asLong());
    }

    @Test void favoritesAreIdempotentIndependentAndCanBeRemovedAfterWithdrawal() throws Exception {
        long sentence=questionIds.get(0); String endpoint=BASE+"/questions/"+sentence+"/favorite";
        for(int index=0;index<2;index++) ok("PUT",endpoint,Map.of("favorite",true),tokenA,200);
        assertEquals(1,get("/favorites").get("total").asLong()); assertEquals(0,ok("GET",BASE+"/favorites",null,tokenB,200).get("total").asLong());
        var created=ok("POST",BASE+"/sessions",Map.of("source","FAVORITES","sentenceIds",List.of(sentence)),tokenA,201);
        assertEquals("FAVORITES",created.get("selectionSource").asString());
        var question=questions.selectOneById(sentence); question.setStatus("INACTIVE"); questions.update(question);
        for(int index=0;index<2;index++) ok("PUT",endpoint,Map.of("favorite",false),tokenA,200);
        assertEquals(0,get("/favorites").get("total").asLong());
    }

    @Test void failuresRollBackCreationSubmissionAndSelfReviewTogether() throws Exception {
        long before=countSessions(); probe.failAfterMapper="com.wordtrail.sentence.mapper.SentenceItemMapper";
        try { error("POST","/sessions",Map.of("sentenceIds",List.of(questionIds.get(0))),tokenA,500,"INTERNAL_ERROR"); }
        finally { probe.failAfterMapper=null; }
        assertEquals(before,countSessions()); long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong();
        for(String mapper:List.of("SentenceAttemptMapper","SentenceItemMapper")) {
            probe.failAfterMapper="com.wordtrail.sentence.mapper."+mapper;
            try { error("PUT",path(session,item,"submission"),answer("rollback_submit"),tokenA,500,"INTERNAL_ERROR"); }
            finally { probe.failAfterMapper=null; }
            assertEquals(0,countAttempts()); assertEquals("PENDING",current(session).get("status").asString());
        }
        submit(session,item,answer("rollback_submit"));
        for(String mapper:List.of("SentenceAttemptMapper","SentenceItemMapper","SentenceSessionMapper")) {
            probe.failAfterMapper="com.wordtrail.sentence.mapper."+mapper;
            try { error("PUT",path(session,item,"self-review"),review("rollback_review","PARTIAL"),tokenA,500,"INTERNAL_ERROR"); }
            finally { probe.failAfterMapper=null; }
            assertEquals("AWAITING_REVIEW",current(session).get("status").asString()); assertEquals(0,get("/sessions/"+session).get("selfReviewCount").asLong());
        }
        assertEquals("COMPLETED",assess(session,item,"rollback_review","PARTIAL").get("progress").get("status").asString());
    }

    @Test void sessionListUsesBoundedQueriesAndQueriesDoNotCreateLearningRecords() throws Exception {
        for(int index=0;index<3;index++) create(questionIds.get(0),questionIds.get(1));
        probe.reads.set(0); probe.writes.set(0); var page=get("/sessions?size=3");
        assertEquals(3,page.get("total").asLong()); assertTrue(probe.reads.get()<=4,"Must not query each session separately");
        assertEquals(0,probe.writes.get()); assertEquals(0,countAttempts());
        assertEquals(0,get("/questions/"+Long.MAX_VALUE+"/history").get("total").asLong());
    }

    @Test void sentenceWorkNeverChangesWordPlanDailyGoalOrRecognitionStatistics() throws Exception {
        var state=new UserWordState(); state.setUserId(a); state.setWordId(wordId); state.setPlanStatus("ACTIVE"); state.setReviewLevel(3);
        state.setJoinedAt(LocalDateTime.of(2026,9,1,0,0)); state.setFirstLearnedAt(LocalDateTime.of(2026,9,2,0,0));
        state.setNextReviewAt(LocalDateTime.of(2026,11,1,0,0)); states.insertSelective(state);
        var before=json.valueToTree(states.selectOneById(state.getId()));
        var today=ok("GET","/api/learning/today",null,tokenA,200);
        var daily=ok("GET","/api/learning/statistics/daily",null,tokenA,200);
        var practice=ok("GET","/api/learning/statistics/summary?source=PRACTICE",null,tokenA,200);
        long session=create(questionIds.get(0)); finish(session,"PARTIAL","isolated");
        assertEquals(before,json.valueToTree(states.selectOneById(state.getId())));
        assertEquals(today,ok("GET","/api/learning/today",null,tokenA,200));
        assertEquals(daily,ok("GET","/api/learning/statistics/daily",null,tokenA,200));
        assertEquals(practice,ok("GET","/api/learning/statistics/summary?source=PRACTICE",null,tokenA,200));
    }

    private Map<String,Object> managedBody(long sentenceId) {
        var q=questions.selectOneById(sentenceId);var body=new HashMap<String,Object>();
        body.put("englishText",q.getEnglishText());body.put("referenceTranslation",q.getReferenceTranslation());body.put("explanation",q.getExplanation());
        body.put("difficulty",q.getDifficulty());body.put("source",q.getSource());body.put("sourceReference",q.getSourceReference());body.put("status",q.getStatus());body.put("version",q.getVersion());
        body.put("wordIds",questions.linkedWords(sentenceId).stream().map(com.wordtrail.sentence.vo.SentenceTranslationVO.LinkedWord::wordId).toList());return body;
    }
    private String csv(String english) {return "englishText,referenceTranslation,explanation,difficulty,source,wordIds\n\""+english.replace("\"","\"\"")+"\",参考译文,解析,2,原创测试,"+wordId;}
    private JsonNode importCsv(String text,String key) throws Exception {
        var result=ok("POST",BASE+"/management/import",Map.of("text",text,"requestId",key),tokenA,201);
        for(var id:result.get("sentenceIds")) if(!questionIds.contains(id.asLong())) questionIds.add(id.asLong());return result;
    }

    @Test void managementRequiresServerGrantedEditorAndCannotBeEnabledByRequestFields() throws Exception {
        assertTrue(get("/management/access").get("canManage").asBoolean());
        assertFalse(ok("GET",BASE+"/management/access",null,tokenB,200).get("canManage").asBoolean());
        for(String route:List.of("/management/questions","/management/questions/"+questionIds.get(0))) error("GET",route,null,tokenB,403,"SENTENCE_EDITOR_REQUIRED");
        error("POST","/management/questions",managedBody(questionIds.get(0)),tokenB,403,"SENTENCE_EDITOR_REQUIRED");
        error("PUT","/management/questions/"+questionIds.get(0),managedBody(questionIds.get(0)),tokenB,403,"SENTENCE_EDITOR_REQUIRED");
        for(String route:List.of("/management/import/preview","/management/import")) error("POST",route,Map.of("text",csv(prefix+"new"),"requestId","denied_import"),tokenB,403,"SENTENCE_EDITOR_REQUIRED");
        var body=managedBody(questionIds.get(0));body.put("canManage",true);error("POST","/management/questions",body,tokenA,400,"INVALID_PARAMETER");
        error("GET","/management/access",null,null,401,"UNAUTHENTICATED");
    }

    @Test void managementCreatesValidatedQuestionsAndRejectsDuplicateEnglishAcrossSources() throws Exception {
        long before=questions.selectCountByQuery(QueryWrapper.create());var body=managedBody(questionIds.get(0));body.put("englishText",prefix+" managed sentence.");body.put("version",0);
        var created=ok("POST",BASE+"/management/questions",body,tokenA,201);long id=created.get("sentenceId").asLong();questionIds.add(id);
        assertEquals(0,created.get("version").asLong());assertEquals(wordId,created.get("linkedWords").get(0).get("wordId").asLong());
        body.put("source","different source");body.put("englishText"," "+prefix+" managed sentence. ");
        error("POST","/management/questions",body,tokenA,409,"SENTENCE_ALREADY_EXISTS");
        body.put("englishText",prefix+" unavailable word.");body.put("wordIds",List.of(Long.MAX_VALUE));error("POST","/management/questions",body,tokenA,409,"SENTENCE_WORD_UNAVAILABLE");
        body.put("wordIds",List.of());body.put("difficulty",4);error("POST","/management/questions",body,tokenA,400,"INVALID_PARAMETER");
        assertEquals(before+1,questions.selectCountByQuery(QueryWrapper.create()));
        assertEquals(id,get("/management/questions/"+id).get("sentenceId").asLong());
        assertEquals(1,get("/management/questions?keyword="+prefix+"&difficulty=1&wordId="+wordId).get("total").asLong());
    }

    @Test void editsAndWithdrawalKeepTextReferencesAndWordLinksOfExistingRounds() throws Exception {
        long sentence=questionIds.get(0),session=create(sentence);long item=current(session).get("itemId").asLong();String original=current(session).get("englishText").asString();
        var body=managedBody(sentence);body.put("englishText",prefix+" updated");body.put("referenceTranslation","更新后的参考");body.put("sourceReference",null);body.put("wordIds",List.of());body.put("status","INACTIVE");
        var updated=ok("PUT",BASE+"/management/questions/"+sentence,body,tokenA,200);assertEquals(1,updated.get("version").asLong());assertTrue(updated.get("sourceReference").isNull());
        assertEquals(original,current(session).get("englishText").asString());assertEquals(wordId,current(session).get("linkedWords").get(0).get("wordId").asLong());
        assertEquals("原始参考译文0",submit(session,item,answer("edit_snapshot")).get("referenceTranslation").asString());assess(session,item,"edit_snapshot_review","PARTIAL");
        error("PUT","/management/questions/"+sentence,body,tokenA,409,"SENTENCE_VERSION_CONFLICT");
        error("POST","/sessions",Map.of("sentenceIds",List.of(sentence)),tokenA,409,"SENTENCE_UNAVAILABLE");
        assertEquals(1,get("/management/questions?status=INACTIVE&keyword="+prefix).get("total").asLong());
    }

    @Test void csvPreviewIsReadOnlyAndSupportsBomQuotedCommaQuotesAndMultilineExplanation() throws Exception {
        String text="\uFEFFenglishText,referenceTranslation,explanation,difficulty,source,sourceReference,wordIds\r\n\""+prefix+", he said \"\"hello\"\".\",参考,\"第一行\n第二行\",3,原创,来源位置,"+wordId;
        long before=questions.selectCountByQuery(QueryWrapper.create());probe.writes.set(0);
        var preview=ok("POST",BASE+"/management/import/preview",Map.of("text",text),tokenA,200);
        assertEquals(1,preview.get("readyCount").asLong());assertEquals("第一行\n第二行",preview.get("rows").get(0).get("explanation").asString());assertEquals(0,probe.writes.get());
        assertEquals(before,questions.selectCountByQuery(QueryWrapper.create()));
        var imported=importCsv(text,"quoted_import");assertEquals(1,imported.get("importedCount").asLong());
    }

    @Test void previewReportsExistingFileDuplicatesAndInvalidWordLinksAndConfirmationIsAtomic() throws Exception {
        String header="englishText,referenceTranslation,explanation,difficulty,source,wordIds\n";
        String text=header+questions.selectOneById(questionIds.get(0)).getEnglishText()+",参考,解析,1,原创,\n"+prefix+" new,参考,解析,1,原创,\n"+prefix+" new,参考,解析,1,原创,\n"+prefix+" missing,参考,解析,1,原创,"+Long.MAX_VALUE+"\n"+prefix+" invalid,参考,解析,5,原创,";
        var preview=ok("POST",BASE+"/management/import/preview",Map.of("text",text),tokenA,200);
        assertEquals(1,preview.get("readyCount").asLong());assertEquals(2,preview.get("duplicateCount").asLong());assertEquals(2,preview.get("invalidCount").asLong());
        long before=questions.selectCountByQuery(QueryWrapper.create());error("POST","/management/import",Map.of("text",text,"requestId","invalid_batch"),tokenA,409,"SENTENCE_IMPORT_NOT_READY");assertEquals(before,questions.selectCountByQuery(QueryWrapper.create()));
    }

    @Test void csvConfirmationRetriesPersistOneBatchAndRejectChangedContent() throws Exception {
        String text=csv(prefix+" imported.");var first=importCsv(text,"idempotent_import");assertEquals(first,importCsv(text,"idempotent_import"));
        assertEquals(1,importBatches.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
        error("POST","/management/import",Map.of("text",csv(prefix+" changed."),"requestId","idempotent_import"),tokenA,409,"REQUEST_ID_REUSED");
        assertEquals(1,ok("POST",BASE+"/management/import/preview",Map.of("text",text),tokenA,200).get("duplicateCount").asLong());
        error("POST","/management/import",Map.of("text",text),tokenA,400,"INVALID_PARAMETER");
    }

    @Test void csvConfirmationAndManagementEditsRollBackAllAssociatedWrites() throws Exception {
        long before=questions.selectCountByQuery(QueryWrapper.create());String text=csv(prefix+" rollback.");probe.failAfterMapper="com.wordtrail.sentence.mapper.SentenceWordMapper";
        try {error("POST","/management/import",Map.of("text",text,"requestId","rollback_import"),tokenA,500,"INTERNAL_ERROR");}finally{probe.failAfterMapper=null;}
        assertEquals(before,questions.selectCountByQuery(QueryWrapper.create()));assertEquals(0,importBatches.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
        importCsv(text,"rollback_import");long sentence=questionIds.get(0);var body=managedBody(sentence);body.put("referenceTranslation","应回滚");probe.failAfterMapper="com.wordtrail.sentence.mapper.SentenceWordMapper";
        try {error("PUT","/management/questions/"+sentence,body,tokenA,500,"INTERNAL_ERROR");}finally{probe.failAfterMapper=null;}
        assertEquals("原始参考译文0",questions.selectOneById(sentence).getReferenceTranslation());assertEquals(0,questions.selectOneById(sentence).getVersion());
    }

    @Test void concurrentImportsReturnOneReceiptAndConcurrentEditsRejectStaleVersions() throws Exception {
        String text=csv(prefix+" concurrent.");Object body=Map.of("text",text,"requestId","concurrent_import");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var left=executor.submit(()->call("POST",BASE+"/management/import",body,tokenA));var right=executor.submit(()->call("POST",BASE+"/management/import",body,tokenA));
            var first=left.get();var second=right.get();
            for(var response:List.of(first,second)) if(response.statusCode()==201) for(var id:json.readTree(response.body()).get("data").get("sentenceIds")) if(!questionIds.contains(id.asLong())) questionIds.add(id.asLong());
            assertEquals(201,first.statusCode(),first.body());assertEquals(201,second.statusCode(),second.body());assertEquals(json.readTree(first.body()),json.readTree(second.body()));
        }
        long id=questionIds.get(0);var edit=managedBody(id);edit.put("referenceTranslation","并发修改");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var left=executor.submit(()->call("PUT",BASE+"/management/questions/"+id,edit,tokenA));var right=executor.submit(()->call("PUT",BASE+"/management/questions/"+id,edit,tokenA));
            var codes=new ArrayList<>(List.of(left.get().statusCode(),right.get().statusCode()));Collections.sort(codes);assertEquals(List.of(200,409),codes);
        }
        assertEquals(1,questions.selectOneById(id).getVersion());
    }

    @Test void understoodReviewIntervalsCapAtThirtyDaysAndManagementListsUseBoundedQueries() throws Exception {
        clock.at(Instant.parse("2026-10-24T08:00:00Z"));tokenA=jwt.issue(a);long sentence=questionIds.get(0);
        int[] intervals={1,3,7,14,30,30};for(int index=0;index<intervals.length;index++) {
            finish(create(sentence),"UNDERSTOOD","interval_"+index);var schedule=get("/questions/"+sentence+"/schedule");
            assertEquals(Math.min(index+1,5),schedule.get("reviewLevel").asLong());
            assertEquals(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(intervals[index]).toString(),schedule.get("nextReviewDate").asString());
        }
        probe.reads.set(0);probe.writes.set(0);get("/management/questions?size=100");assertTrue(probe.reads.get()<=3);assertEquals(0,probe.writes.get());
    }

    @Test void malformedCsvHeadersEncodingLimitsAndUnknownFieldsAreRejected() throws Exception {
        for(String text:List.of("englishText,englishText\na,b","englishText,unknown\na,b",csv(prefix)+"\n\"unclosed",csv(prefix)+"x".repeat(256*1024))) {
            var response=call("POST",BASE+"/management/import/preview",Map.of("text",text),tokenA);assertTrue(response.statusCode()==400||response.statusCode()==413);
        }
        StringBuilder many=new StringBuilder("englishText,referenceTranslation,explanation,difficulty,source\n");for(int i=0;i<101;i++) many.append(prefix).append(i).append(",参考,解析,1,原创\n");
        error("POST","/management/import/preview",Map.of("text",many.toString()),tokenA,400,"IMPORT_ROW_LIMIT");
        error("GET","/management/questions?page=0",null,tokenA,400,"INVALID_PARAMETER");
    }

    @Test void dueReviewUsesNextShanghaiDateAndIncreasesUnderstoodIntervalsWithoutMixingWordState() throws Exception {
        clock.at(Instant.parse("2026-10-24T08:00:00Z"));tokenA=jwt.issue(a);long sentence=questionIds.get(0);long first=create(sentence);finish(first,"UNDERSTOOD","scheduled_first");
        assertEquals("2026-10-25",get("/questions/"+sentence+"/schedule").get("nextReviewDate").asString());assertEquals(0,get("/reviews/due").get("total").asLong());
        clock.advanceSeconds(86400);tokenA=jwt.issue(a);assertEquals(1,get("/reviews/due").get("total").asLong());
        var created=ok("POST",BASE+"/sessions",Map.of("source","DUE"),tokenA,201);long session=created.get("sessionId").asLong();assertEquals("DUE",created.get("selectionSource").asString());
        assertEquals(0,get("/reviews/due").get("total").asLong());error("POST","/sessions",Map.of("source","DUE"),tokenA,409,"NO_SENTENCE_QUESTIONS");
        finish(session,"UNDERSTOOD","scheduled_second");var schedule=get("/questions/"+sentence+"/schedule");assertEquals(2,schedule.get("reviewLevel").asLong());assertEquals("2026-10-28",schedule.get("nextReviewDate").asString());
        assertEquals(0,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
    }

    @Test void weakerSelfReviewResetsScheduleButNewOrUnreviewedAttemptsDoNot() throws Exception {
        clock.at(Instant.parse("2026-10-24T08:00:00Z"));tokenA=jwt.issue(a);long sentence=questionIds.get(0);finish(create(sentence),"UNDERSTOOD","reset_first");var before=get("/questions/"+sentence+"/schedule");
        long session=create(sentence);assertEquals(before,get("/questions/"+sentence+"/schedule"));long item=current(session).get("itemId").asLong();submit(session,item,answer("reset_submit"));assertEquals(before,get("/questions/"+sentence+"/schedule"));
        var reviewed=assess(session,item,"reset_review","NOT_UNDERSTOOD");assertEquals(0,reviewed.get("reviewSchedule").get("reviewLevel").asLong());assertEquals("2026-10-25",reviewed.get("reviewSchedule").get("nextReviewDate").asString());
        tokenB=jwt.issue(b);assertTrue(ok("GET",BASE+"/questions/"+sentence+"/schedule",null,tokenB,200).isNull());
    }

    @Test void delayedOldSelfReviewCannotOverwriteNewerAttemptSchedule() throws Exception {
        long sentence=questionIds.get(0),old=create(sentence);long item=current(old).get("itemId").asLong();submit(old,item,answer("delayed_old_submit"));
        long newer=create(sentence);finish(newer,"UNDERSTOOD","newer_schedule");var expected=get("/questions/"+sentence+"/schedule");
        assess(old,item,"delayed_old_review","PARTIAL");assertEquals(expected,get("/questions/"+sentence+"/schedule"));assertEquals(0,get("/review-queue").get("total").asLong());
        assertEquals(2,get("/statistics").get("selfReviewCount").asLong());
    }

    @Test void schedulingFailuresRollBackAssessmentItemAndStateTogether() throws Exception {
        long session=create(questionIds.get(0)),item=current(session).get("itemId").asLong();submit(session,item,answer("schedule_rollback_submit"));probe.failAfterMapper="com.wordtrail.sentence.mapper.SentenceReviewStateMapper";
        try{error("PUT",path(session,item,"self-review"),review("schedule_rollback_review","PARTIAL"),tokenA,500,"INTERNAL_ERROR");}finally{probe.failAfterMapper=null;}
        assertTrue(get("/questions/"+questionIds.get(0)+"/schedule").isNull());assertEquals("AWAITING_REVIEW",current(session).get("status").asString());
        assertEquals(0,get("/statistics").get("selfReviewCount").asLong());assess(session,item,"schedule_rollback_review","PARTIAL");assertEquals(1,get("/statistics").get("selfReviewCount").asLong());
    }

    @Test void sentenceStatisticsSeparateSubmissionAndSelfReviewDatesAcrossShanghaiMidnight() throws Exception {
        clock.at(Instant.parse("2026-10-24T15:59:50Z"));tokenA=jwt.issue(a);long session=create(questionIds.get(0)),item=current(session).get("itemId").asLong();submit(session,item,answer("stats_midnight_submit"));
        clock.advanceSeconds(20);assess(session,item,"stats_midnight_review","PARTIAL");var stats=get("/statistics?days=2");
        assertEquals(1,stats.get("submissionCount").asLong());assertEquals(1,stats.get("selfReviewCount").asLong());assertEquals(2,stats.get("learningDayCount").asLong());assertEquals(1,stats.get("weakSentenceCount").asLong());
        assertEquals(1,stats.get("days").get(0).get("submissionCount").asLong());assertEquals(0,stats.get("days").get(0).get("selfReviewCount").asLong());
        assertEquals(0,stats.get("days").get(1).get("submissionCount").asLong());assertEquals(1,stats.get("days").get(1).get("selfReviewCount").asLong());
        assertEquals("2026-10-26",get("/questions/"+questionIds.get(0)+"/schedule").get("nextReviewDate").asString());
    }

    @Test void sentenceCalendarStreakAndDateDetailsFollowRealActivityAcrossMidnight() throws Exception {
        clock.at(Instant.parse("2024-02-28T15:59:50Z")); tokenA=jwt.issue(a); tokenB=jwt.issue(b);
        ok("POST",BASE+"/sessions",Map.of("sentenceIds",List.of(questionIds.get(2))),tokenB,201);
        long session=create(questionIds.get(0),questionIds.get(1)),first=current(session).get("itemId").asLong();
        submit(session,first,answer("calendar_first_submit"));
        clock.advanceSeconds(20); assess(session,first,"calendar_first_review","PARTIAL");
        long second=current(session).get("itemId").asLong();
        submit(session,second,Map.of("action","REVEAL","requestId","calendar_second_submit"));
        assess(session,second,"calendar_second_review","UNDERSTOOD");

        var overview=get("/statistics?days=2");
        assertEquals(2,overview.get("learningDayCount").asLong()); assertEquals(2,overview.get("currentStreak").asLong());
        assertEquals(2,overview.get("longestStreak").asLong());
        var calendar=get("/statistics/calendar?month=2024-02"); assertEquals("2024-02",calendar.get("month").asString());
        assertEquals(29,calendar.get("days").size());
        var empty=calendar.get("days").get(0); assertEquals("2024-02-01",empty.get("date").asString());
        assertFalse(empty.get("hasActivity").asBoolean());
        var firstDay=calendar.get("days").get(27); var secondDay=calendar.get("days").get(28);
        assertTrue(firstDay.get("hasActivity").asBoolean()); assertEquals(1,firstDay.get("submissionCount").asLong());
        assertEquals(1,firstDay.get("answerCount").asLong()); assertEquals(0,firstDay.get("selfReviewCount").asLong());
        assertTrue(secondDay.get("hasActivity").asBoolean()); assertEquals(1,secondDay.get("submissionCount").asLong());
        assertEquals(1,secondDay.get("revealCount").asLong()); assertEquals(2,secondDay.get("selfReviewCount").asLong());
        assertEquals(firstDay,get("/statistics/daily?date=2024-02-28"));
        assertEquals(secondDay,get("/statistics/daily?date=2024-02-29"));
        assertFalse(get("/statistics/daily?date=2024-02-27").get("hasActivity").asBoolean());

        var firstDetails=get("/statistics/attempts?date=2024-02-28"); assertEquals(1,firstDetails.get("total").asLong());
        var before=firstDetails.get("records").get(0); assertTrue(before.get("submittedOnDate").asBoolean());
        assertFalse(before.get("reviewedOnDate").asBoolean()); assertEquals("2024-02-29",before.get("reviewDate").asString());
        var details=get("/statistics/attempts?date=2024-02-29&size=1");
        var next=get("/statistics/attempts?date=2024-02-29&size=1&page=2");
        assertEquals(2,details.get("total").asLong()); assertEquals(2,next.get("total").asLong());
        var sameDay=details.get("records").get(0); var reviewOnly=next.get("records").get(0);
        assertEquals(second,sameDay.get("itemId").asLong()); assertTrue(sameDay.get("submittedOnDate").asBoolean());
        assertTrue(sameDay.get("reviewedOnDate").asBoolean()); assertEquals("REVEAL",sameDay.get("action").asString());
        assertEquals(first,reviewOnly.get("itemId").asLong()); assertFalse(reviewOnly.get("submittedOnDate").asBoolean());
        assertTrue(reviewOnly.get("reviewedOnDate").asBoolean()); assertEquals(before.get("attemptId"),reviewOnly.get("attemptId"));
        assertEquals(0,get("/statistics/attempts?date=2024-02-29&page=2147483647").get("records").size());
        assertEquals(0,ok("GET",BASE+"/statistics/attempts?date=2024-02-29",null,tokenB,200).get("total").asLong());
        assertEquals(0,ok("GET",BASE+"/statistics",null,tokenB,200).get("currentStreak").asLong());
        assertFalse(ok("GET",BASE+"/statistics/daily?date=2024-02-29",null,tokenB,200).get("hasActivity").asBoolean());
        error("GET","/statistics/calendar?month=2024-13",null,tokenA,400,"INVALID_PARAMETER");
        error("GET","/statistics/daily?date=2024-02-30",null,tokenA,400,"INVALID_PARAMETER");
        error("GET","/statistics/attempts?date=2024-02-29&size=101",null,tokenA,400,"INVALID_PARAMETER");
        error("GET","/statistics/attempts",null,null,401,"UNAUTHENTICATED");

        clock.at(Instant.parse("2024-03-01T08:00:00Z")); tokenA=jwt.issue(a);
        assertEquals(2,get("/statistics").get("currentStreak").asLong());
        clock.advanceSeconds(86400); tokenA=jwt.issue(a); var stale=get("/statistics");
        assertEquals(0,stale.get("currentStreak").asLong()); assertEquals(2,stale.get("longestStreak").asLong());
        assertEquals("2024-03",get("/statistics/calendar").get("month").asString());
        assertEquals("2024-03-02",get("/statistics/daily").get("date").asString());
        finish(create(questionIds.get(0)),"PARTIAL","calendar_restart");
        var restarted=get("/statistics"); assertEquals(1,restarted.get("currentStreak").asLong());
        assertEquals(2,restarted.get("longestStreak").asLong());
    }

    @Test void statisticsCountTypedAnswersAndRevealsOnceAndQueriesStayReadOnlyAndBounded() throws Exception {
        long session=create(questionIds.get(0)),item=current(session).get("itemId").asLong();submit(session,item,answer("stats_typed_submit"));submit(session,item,answer("stats_typed_submit"));assess(session,item,"stats_typed_review","PARTIAL");assess(session,item,"stats_typed_review","PARTIAL");
        long reveal=create(questionIds.get(1)),revealItem=current(reveal).get("itemId").asLong();submit(reveal,revealItem,Map.of("action","REVEAL","requestId","stats_reveal_submit"));
        probe.reads.set(0);probe.writes.set(0);var stats=get("/statistics?days=30");assertTrue(probe.reads.get()<=5);assertEquals(0,probe.writes.get());
        assertEquals(2,stats.get("submissionCount").asLong());assertEquals(1,stats.get("answerCount").asLong());assertEquals(1,stats.get("revealCount").asLong());assertEquals(1,stats.get("selfReviewCount").asLong());assertEquals(30,stats.get("days").size());
        var other=ok("GET",BASE+"/statistics",null,tokenB,200);assertEquals(0,other.get("submissionCount").asLong());assertEquals(0,other.get("learningDayCount").asLong());
        for(String days:List.of("0","367","1.2")) error("GET","/statistics?days="+days,null,tokenA,400,"INVALID_PARAMETER");
    }

    @Test void inactiveQuestionsKeepSchedulesButCannotEnterDueSelections() throws Exception {
        clock.at(Instant.parse("2026-10-24T08:00:00Z"));tokenA=jwt.issue(a);long sentence=questionIds.get(0);finish(create(sentence),"PARTIAL","inactive_schedule");
        var body=managedBody(sentence);body.put("status","INACTIVE");ok("PUT",BASE+"/management/questions/"+sentence,body,tokenA,200);clock.advanceSeconds(86400);tokenA=jwt.issue(a);
        assertEquals(0,get("/reviews/due").get("total").asLong());assertFalse(get("/questions/"+sentence+"/schedule").isNull());
        error("POST","/sessions",Map.of("source","DUE","sentenceIds",List.of(sentence)),tokenA,409,"SENTENCE_UNAVAILABLE");
        assertEquals(1,get("/statistics").get("weakSentenceCount").asLong());
    }

    private long collectionBook(Long owner){var book=new com.wordtrail.book.entity.WordBook();book.setName(prefix+" collection "+collectionBooks.size());book.setOwnerId(owner);wordBooks.insertSelective(book);collectionBooks.add(book.getId());return book.getId();}
    private long extraWord(){var word=new Word();word.setWord(prefix+" extra "+extraWords.size());words.insertSelective(word);extraWords.add(word.getId());return word.getId();}
    private String collectPath(long session,long item,long word){return "/sessions/"+session+"/items/"+item+"/words/"+word+"/collect";}
    private JsonNode saveNote(long sentence,Object body) throws Exception{return ok("PUT",BASE+"/questions/"+sentence+"/note",body,tokenA,200);}

    @Test void relatedSentenceEntryAndBatchedCountsExposeOnlyAvailableEnglishAndAvoidNPlusOne() throws Exception {
        var page=ok("GET","/api/words/"+wordId+"/sentences?size=1",null,tokenA,200);assertEquals(2,page.get("total").asLong());assertFalse(page.get("records").get(0).has("referenceTranslation"));
        long unrelated=extraWord();probe.reads.set(0);var summary=ok("POST",BASE+"/word-links/summary",Map.of("wordIds",List.of(wordId,unrelated,wordId)),tokenA,200);
        assertEquals(2,summary.size());assertEquals(2,summary.get(0).get("sentenceCount").asLong());assertEquals(0,summary.get(1).get("sentenceCount").asLong());assertTrue(probe.reads.get()<=2);
        assertEquals(404,call("GET","/api/words/"+Long.MAX_VALUE+"/sentences",null,tokenA).statusCode());
        error("POST","/word-links/summary",Map.of("wordIds",Collections.nCopies(101,wordId)),tokenA,400,"INVALID_PARAMETER");
    }

    @Test void selectedWordsCreateDistinctSentencePracticeWithoutChangingWordSchedule() throws Exception {
        long secondWord=extraWord();try(var c=dataSource.getConnection();var s=c.prepareStatement("INSERT INTO sentence_word(sentence_id,word_id) VALUES(?,?)")){s.setLong(1,questionIds.get(0));s.setLong(2,secondWord);s.executeUpdate();}
        var created=ok("POST",BASE+"/word-practice",Map.of("wordIds",List.of(wordId,secondWord,wordId),"count",50),tokenA,201);
        assertEquals(2,created.get("totalCount").asLong());assertEquals(0,countAttempts());assertEquals(0,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
        long before=countSessions();error("POST","/word-practice",Map.of("wordIds",List.of(wordId,Long.MAX_VALUE)),tokenA,404,"WORD_NOT_FOUND");assertEquals(before,countSessions());
        error("POST","/word-practice",Map.of("wordIds",List.of(secondWord),"difficulty",3),tokenA,409,"NO_SENTENCE_QUESTIONS");
    }

    @Test void collectingSnapshotWordsIsExplicitPersonalAndCanAtomicallyJoinExistingPlan() throws Exception {
        long session=create(questionIds.get(0)),item=current(session).get("itemId").asLong(),book=collectionBook(a);
        var first=ok("POST",BASE+collectPath(session,item,wordId),Map.of("bookId",book),tokenA,200);assertTrue(first.get("plan").isNull());assertEquals(0,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
        var joined=ok("POST",BASE+collectPath(session,item,wordId),Map.of("bookId",book,"joinPlan",true),tokenA,200);assertEquals(1,joined.get("plan").get("addedCount").asLong());
        var state=states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(a)).and(column("word_id").eq(wordId)));assertEquals(0,state.getReviewLevel());assertNull(state.getFirstLearnedAt());
        ok("POST",BASE+collectPath(session,item,wordId),Map.of("bookId",book,"joinPlan",true),tokenA,200);assertEquals(1,wordBookItems.selectCountByQuery(QueryWrapper.create().where(column("book_id").eq(book))));assertEquals(0,countAttempts());
    }

    @Test void collectionChecksOwnershipBookPermissionsAndSnapshotMembershipAndRollsBackPlanFailures() throws Exception {
        long session=create(questionIds.get(0)),item=current(session).get("itemId").asLong(),own=collectionBook(a),foreign=collectionBook(b),system=collectionBook(null);
        error("POST",collectPath(session,item,wordId),Map.of("bookId",foreign),tokenA,404,"BOOK_NOT_FOUND");
        error("POST",collectPath(session,item,wordId),Map.of("bookId",system),tokenA,403,"BOOK_READ_ONLY");
        error("POST",collectPath(session,item,wordId),Map.of("bookId",own),tokenB,404,"SENTENCE_SESSION_NOT_FOUND");
        error("POST",collectPath(session,item,extraWord()),Map.of("bookId",own),tokenA,409,"SENTENCE_WORD_NOT_LINKED");
        probe.failAfterMapper="com.wordtrail.learning.mapper.UserWordStateMapper";
        try{error("POST",collectPath(session,item,wordId),Map.of("bookId",own,"joinPlan",true),tokenA,500,"INTERNAL_ERROR");}finally{probe.failAfterMapper=null;}
        assertEquals(0,wordBookItems.selectCountByQuery(QueryWrapper.create().where(column("book_id").eq(own))));assertEquals(0,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
    }

    @Test void collectionRetainsHistoricalWordLinksAfterBankAssociationChanges() throws Exception {
        long sentence=questionIds.get(0),session=create(sentence),item=current(session).get("itemId").asLong(),book=collectionBook(a);
        var edit=managedBody(sentence);edit.put("wordIds",List.of());ok("PUT",BASE+"/management/questions/"+sentence,edit,tokenA,200);
        ok("POST",BASE+collectPath(session,item,wordId),Map.of("bookId",book),tokenA,200);assertEquals(1,wordBookItems.selectCountByQuery(QueryWrapper.create().where(column("book_id").eq(book))));
    }

    @Test void privateNotesAndErrorTagsAreVisibleDuringPracticeAndNeverChangeOriginalAnswers() throws Exception {
        long sentence=questionIds.get(0),session=create(sentence),item=current(session).get("itemId").asLong();var answer=submit(session,item,answer("note_context_submit"));
        String content="注意否定句\n保留自己的理解。";var saved=saveNote(sentence,Map.of("content",content,"errorTags",List.of("NEGATION","TENSE","NEGATION"),"attemptId",answer.get("attemptId").asLong(),"version",0));
        assertEquals(1,saved.get("version").asLong());assertEquals(2,saved.get("errorTags").size());assertEquals(content,current(session).get("personalNote").get("content").asString());
        assertEquals("我的不同措辞译文",current(session).get("translation").asString());assertEquals("AWAITING_REVIEW",current(session).get("status").asString());assertEquals(0,get("/statistics").get("selfReviewCount").asLong());
        assertEquals(0,ok("GET",BASE+"/questions/"+sentence+"/note",null,tokenB,200).get("version").asLong());assertEquals(0,ok("GET",BASE+"/notes",null,tokenB,200).get("total").asLong());
    }

    @Test void notesRejectStaleVersionsOtherUserOrOtherSentenceAttemptsAndForgedFields() throws Exception {
        long first=questionIds.get(0),second=questionIds.get(1),session=create(first);long item=current(session).get("itemId").asLong();long attempt=submit(session,item,answer("note_owner_submit")).get("attemptId").asLong();
        saveNote(first,Map.of("content","笔记","version",0));error("PUT","/questions/"+first+"/note",Map.of("content","旧页面","version",0),tokenA,409,"SENTENCE_NOTE_VERSION_CONFLICT");
        error("PUT","/questions/"+second+"/note",Map.of("content","错题关联","attemptId",attempt),tokenA,404,"SENTENCE_ATTEMPT_NOT_FOUND");
        error("PUT","/questions/"+first+"/note",Map.of("content","越权","attemptId",attempt),tokenB,404,"SENTENCE_ATTEMPT_NOT_FOUND");
        error("PUT","/questions/"+first+"/note",Map.of("content","x".repeat(4001)),tokenA,400,"INVALID_PARAMETER");
        error("PUT","/questions/"+first+"/note",Map.of("content","伪造","userId",b),tokenA,400,"INVALID_PARAMETER");
        error("PUT","/questions/"+first+"/note",Map.of("errorTags",List.of("AI_SCORE")),tokenA,400,"INVALID_PARAMETER");
        assertEquals("笔记",get("/questions/"+first+"/note").get("content").asString());
    }

    @Test void noteFilteringIsBoundedAndClearingKeepsVersionTombstoneAndPersonalIsolation() throws Exception {
        long first=questionIds.get(0),second=questionIds.get(1);saveNote(first,Map.of("content","词义笔记","errorTags",List.of("VOCABULARY")));saveNote(second,Map.of("errorTags",List.of("TENSE")));
        probe.reads.set(0);probe.writes.set(0);var page=get("/notes?wordId="+wordId);assertEquals(2,page.get("total").asLong());assertTrue(probe.reads.get()<=3);assertEquals(0,probe.writes.get());
        assertEquals(1,get("/notes?errorTag=VOCABULARY").get("total").asLong());
        saveNote(first,Map.of("content","","errorTags",List.of(),"version",1));assertEquals(1,get("/notes").get("total").asLong());assertEquals(2,get("/questions/"+first+"/note").get("version").asLong());
        error("PUT","/questions/"+first+"/note",Map.of("content","旧内容复活","version",1),tokenA,409,"SENTENCE_NOTE_VERSION_CONFLICT");
    }

    @Test void noteWriteFailuresRollBackContentAndTagsAndConcurrentEditsHaveOneWinner() throws Exception {
        long sentence=questionIds.get(0);saveNote(sentence,Map.of("content","原笔记","errorTags",List.of("VOCABULARY")));probe.failAfterMapper="com.wordtrail.sentence.mapper.SentenceNoteTagMapper";
        try{error("PUT","/questions/"+sentence+"/note",Map.of("content","应回滚","errorTags",List.of("TENSE"),"version",1),tokenA,500,"INTERNAL_ERROR");}finally{probe.failAfterMapper=null;}
        assertEquals("原笔记",get("/questions/"+sentence+"/note").get("content").asString());assertEquals("VOCABULARY",get("/questions/"+sentence+"/note").get("errorTags").get(0).asString());
        try(var executor=Executors.newFixedThreadPool(2)){
            var left=executor.submit(()->call("PUT",BASE+"/questions/"+sentence+"/note",Map.of("content","并发一","version",1),tokenA));
            var right=executor.submit(()->call("PUT",BASE+"/questions/"+sentence+"/note",Map.of("content","并发二","version",1),tokenA));
            var codes=new ArrayList<>(List.of(left.get().statusCode(),right.get().statusCode()));Collections.sort(codes);assertEquals(List.of(200,409),codes);
        }
    }

    @Test void publicHealthChecksWorkWithoutTokensAndReportNoSensitiveDetails() throws Exception {
        for(String path:List.of("/health/live","/health/ready")){
            var response=call("GET",path,null,"malformed-token");assertEquals(200,response.statusCode());assertEquals(1,json.readTree(response.body()).size());assertEquals("UP",json.readTree(response.body()).get("status").asString());
        }
        error("GET","/questions/"+Long.MAX_VALUE+"/note",null,tokenA,404,"SENTENCE_NOT_FOUND");
    }

    @Test void invalidParametersUnknownFieldsAndForgedAutomaticGradesAreRejected() throws Exception {
        for(Object body:List.of(Map.of("count",0),Map.of("count",51),Map.of("count",1.2),Map.of("source","AI"),
                Map.of("difficulty",4),Map.of("wordId",0),Map.of("user_id",b),Map.of("sentenceIds",List.of(-1))))
            error("POST","/sessions",body,tokenA,400,"INVALID_PARAMETER");
        for(String query:List.of("page=0","size=101","difficulty=0","wordId=0","difficulty=1.2"))
            error("GET","/questions?"+query,null,tokenA,400,"INVALID_PARAMETER");
        long session=create(questionIds.get(0)); long item=current(session).get("itemId").asLong();
        error("PUT",path(session,item,"submission"),Map.of("action","ANSWER","translation"," ","requestId","invalid_answer"),tokenA,409,"INVALID_SENTENCE_SUBMISSION");
        error("PUT",path(session,item,"submission"),Map.of("action","REVEAL","translation","不应携带","requestId","invalid_reveal"),tokenA,409,"INVALID_SENTENCE_SUBMISSION");
        for(Object body:List.of(Map.of("action","ANSWER","translation","x".repeat(2001),"requestId","too_long_input"),
                Map.of("action","ANSWER","translation","译文","requestId","bad"),Map.of("action","ANSWER","translation","译文","requestId","forged_grade","correct",true)))
            error("PUT",path(session,item,"submission"),body,tokenA,400,"INVALID_PARAMETER");
        error("PUT",path(session,item,"self-review"),Map.of("assessment","CORRECT","requestId","fake_review_key"),tokenA,400,"INVALID_PARAMETER");
        assertEquals(0,countAttempts());
    }
}
