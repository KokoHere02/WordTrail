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
    @Autowired SentenceFavoriteMapper favorites;
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
            var importIds=importBatches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds))).stream().map(com.wordtrail.sentence.entity.SentenceImportBatch::getId).toList();
            if(!importIds.isEmpty()) importItems.deleteByQuery(QueryWrapper.create().where(column("batch_id").in(importIds)));
            importBatches.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            for(long id:userIds){jdbc.update("DELETE FROM sentence_translation_record WHERE user_id=?",id);jdbc.update("DELETE FROM user_sentence_learning WHERE user_id=?",id);}
            favorites.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            states.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var batchIds=learningBatches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
                .stream().map(LearningBatch::getId).toList();
            if(!batchIds.isEmpty()) learningItems.deleteByQuery(QueryWrapper.create().where(column("batch_id").in(batchIds)));
            learningBatches.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            refresh.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            authSessions.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
        }
        if(!questionIds.isEmpty()) {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            for(long id:questionIds) jdbc.update("DELETE FROM sentence_tag_relation WHERE sentence_id=?",id);
            jdbc.update("DELETE FROM sentence_tag WHERE name=?",prefix+"tag");
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



    @Test void malformedCsvHeadersEncodingLimitsAndUnknownFieldsAreRejected() throws Exception {
        for(String text:List.of("englishText,englishText\na,b","englishText,unknown\na,b",csv(prefix)+"\n\"unclosed",csv(prefix)+"x".repeat(256*1024))) {
            var response=call("POST",BASE+"/management/import/preview",Map.of("text",text),tokenA);assertTrue(response.statusCode()==400||response.statusCode()==413);
        }
        StringBuilder many=new StringBuilder("englishText,referenceTranslation,explanation,difficulty,source\n");for(int i=0;i<101;i++) many.append(prefix).append(i).append(",参考,解析,1,原创\n");
        error("POST","/management/import/preview",Map.of("text",many.toString()),tokenA,400,"IMPORT_ROW_LIMIT");
        error("GET","/management/questions?page=0",null,tokenA,400,"INVALID_PARAMETER");
    }

















    private long collectionBook(Long owner){var book=new com.wordtrail.book.entity.WordBook();book.setName(prefix+" collection "+collectionBooks.size());book.setOwnerId(owner);wordBooks.insertSelective(book);collectionBooks.add(book.getId());return book.getId();}
    private long extraWord(){var word=new Word();word.setWord(prefix+" extra "+extraWords.size());words.insertSelective(word);extraWords.add(word.getId());return word.getId();}
    private String collectPath(long session,long item,long word){return "/sessions/"+session+"/items/"+item+"/words/"+word+"/collect";}
    private JsonNode saveNote(long sentence,Object body) throws Exception{return ok("PUT",BASE+"/questions/"+sentence+"/note",body,tokenA,200);}















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


    @Test void learningListAndHistoryAreIndependentFromFavoritesAndRetries() throws Exception {
        String base="/api/sentence-practice";long sentence=questionIds.get(0);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO sentence_tag(category,name) VALUES (?,?)","句式",prefix+"tag");
        long tag=jdbc.queryForObject("SELECT id FROM sentence_tag WHERE name=?",Long.class,prefix+"tag");
        ok("PUT",base+"/sentences/"+sentence+"/tags",Map.of("tagIds",List.of(tag)),tokenA,200);
        assertEquals(1,ok("GET",base+"/sentences?scope=bank&tagId="+tag,null,tokenA,200).size());
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        assertEquals(1,ok("GET",base+"/learning/random?count=10&tagId="+tag,null,tokenA,200).size());
        ok("PUT",BASE+"/questions/"+sentence+"/favorite",Map.of("favorite",true),tokenA,200);
        var body=Map.of("translation","我自己的翻译","requestId","request-1");
        ok("POST",base+"/sentences/"+sentence+"/translations",body,tokenA,200);
        ok("POST",base+"/sentences/"+sentence+"/translations",body,tokenA,200);
        assertEquals(0,ok("GET",base+"/sentences?scope=learning",null,tokenA,200).size());
        assertEquals(1,ok("GET",base+"/sentences?scope=favorites",null,tokenA,200).size());
        assertEquals(1,ok("GET",base+"/sentences/"+sentence+"/history",null,tokenA,200).size());
        assertEquals(0,ok("GET",base+"/sentences/"+sentence+"/history",null,tokenB,200).size());
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        ok("POST",base+"/sentences/"+sentence+"/translations",Map.of("translation","第二次翻译","requestId","request-2"),tokenA,200);
        assertEquals(2,ok("GET",base+"/sentences/"+sentence+"/history",null,tokenA,200).size());
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        assertEquals(1,ok("GET",base+"/sentences?scope=learning",null,tokenA,200).size());
    }

    @Test void notesCanReferenceOnlyTheOwnersTranslationOfTheSameSentence() throws Exception {
        long sentence=questionIds.get(0), other=questionIds.get(1);
        String base="/api/sentence-practice";
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        ok("POST",base+"/sentences/"+sentence+"/translations",Map.of("translation","本人译文","requestId","note-translation"),tokenA,200);
        long record=ok("GET",base+"/sentences/"+sentence+"/history",null,tokenA,200).get(0).get("id").asLong();
        var note=saveNote(sentence,Map.of("content","笔记","attemptId",record));
        assertEquals(record,note.get("attemptId").asLong());
        error("PUT","/questions/"+other+"/note",Map.of("content","错误句子","attemptId",record),tokenA,404,"SENTENCE_ATTEMPT_NOT_FOUND");
        error("PUT","/questions/"+sentence+"/note",Map.of("content","错误用户","attemptId",record),tokenB,404,"SENTENCE_ATTEMPT_NOT_FOUND");
        assertEquals("笔记",get("/questions/"+sentence+"/note").get("content").asString());
    }

    @Test void practiceFavoritesShareExistingDataAndStayIsolated() throws Exception {
        String base="/api/sentence-practice"; long sentence=questionIds.get(0), inactive=questionIds.get(3);
        ok("PUT",base+"/sentences/"+sentence+"/favorite",Map.of("favorite",true),tokenA,200);
        ok("PUT",base+"/sentences/"+sentence+"/favorite",Map.of("favorite",true),tokenA,200);
        assertEquals(1,ok("GET",base+"/sentences?scope=favorites",null,tokenA,200).size());
        assertEquals(0,ok("GET",base+"/sentences?scope=favorites",null,tokenB,200).size());
        assertEquals(404,call("PUT",base+"/sentences/"+inactive+"/favorite",Map.of("favorite",true),tokenA).statusCode());
        ok("PUT",BASE+"/questions/"+sentence+"/favorite",Map.of("favorite",false),tokenA,200);
        assertEquals(0,ok("GET",base+"/sentences?scope=favorites",null,tokenA,200).size());
    }

    @Test void practiceRecordsAndStatisticsIncludeNewTranslationsWithoutDuplicatingLegacySessions() throws Exception {
        String base="/api/sentence-practice"; long sentence=questionIds.get(0);
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        ok("POST",base+"/sentences/"+sentence+"/translations",Map.of("translation","第一次","requestId","new_record_1"),tokenA,200);
        ok("PUT",base+"/learning/"+sentence,null,tokenA,200);
        ok("POST",base+"/sentences/"+sentence+"/translations",Map.of("translation","第二次","requestId","new_record_2"),tokenA,200);
        String day=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        var stats=ok("GET",base+"/statistics?from="+day+"&to="+day,null,tokenA,200);
        assertEquals(2,stats.get("submissions").asLong()); assertEquals(1,stats.get("sentences").asLong());
        assertEquals(1,stats.get("days").size()); assertEquals(2,stats.get("days").get(0).get("submissions").asLong());
        assertEquals(1,stats.get("days").get(0).get("sentences").asLong());
        var history=ok("GET",base+"/records?date="+day+"&page=1&size=1",null,tokenA,200);
        assertEquals(2,history.get("total").asLong()); assertEquals(1,history.get("records").size());
        long record=history.get("records").get(0).get("id").asLong();
        assertEquals("第二次",ok("GET",base+"/records/"+record,null,tokenA,200).get("translation").asString());
        assertEquals(404,call("GET",base+"/records/"+record,null,tokenB).statusCode());
        assertEquals(0,ok("GET",base+"/records?date="+day,null,tokenB,200).get("total").asLong());
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("INSERT INTO sentence_translation_record(user_id,sentence_id,translation,request_id,created_at) VALUES (?,?,?,?,?)",a,sentence,"历史译文","legacy-999999",LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));
        var recent=ok("GET",base+"/records?page=1&size=2",null,tokenA,200);
        assertEquals(3,recent.get("total").asLong()); assertEquals(2,recent.get("records").size());
        assertEquals("历史译文",recent.get("records").get(0).get("translation").asString());
        assertEquals(0,ok("GET",base+"/records",null,tokenB,200).get("total").asLong());
        assertEquals(0,ok("GET","/api/learning/records?type=WORD",null,tokenA,200).get("total").asLong());
        var rows=ok("GET","/api/learning/records?type=SENTENCE&status=COMPLETED&page=1&size=20",null,tokenA,200);
        assertEquals(3,rows.get("total").asLong()); assertEquals("LEARNING_LIST",rows.get("records").get(0).get("source").asString());
    }

}
