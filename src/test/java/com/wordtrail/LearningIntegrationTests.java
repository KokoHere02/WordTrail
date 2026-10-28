package com.wordtrail;

import tools.jackson.databind.*;
import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.entity.*;
import com.wordtrail.book.mapper.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.security.JwtTokenService;
import com.wordtrail.security.PasswordService;
import com.wordtrail.user.entity.UserAccount;
import com.wordtrail.user.mapper.UserMapper;
import com.wordtrail.user.mapper.RefreshTokenMapper;
import com.wordtrail.word.entity.Word;
import com.wordtrail.word.mapper.WordMapper;
import io.jsonwebtoken.Jwts;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LearningIntegrationTests.Instrumentation.class)
class LearningIntegrationTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> SECRET);
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    static class DatabaseProbe implements Interceptor {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile String failAfterMapper;
        volatile Runnable afterNextStateRead;
        volatile Runnable afterNextInsightsRead;

        @Override public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (invocation.getMethod().getName().equals("query")
                    && (statement.startsWith("com.wordtrail.book.mapper.")
                        || statement.startsWith("com.wordtrail.word.mapper.")
                        || statement.startsWith("com.wordtrail.learning.mapper.")
                        || statement.startsWith("com.wordtrail.sentence.mapper."))) {
                reads.incrementAndGet();
            }
            if (invocation.getMethod().getName().equals("update")) { writes.incrementAndGet(); }
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("query")
                    && statement.startsWith("com.wordtrail.learning.mapper.UserWordStateMapper.")
                    && afterNextStateRead != null) {
                var action = afterNextStateRead;
                afterNextStateRead = null;
                action.run();
            }
            if (invocation.getMethod().getName().equals("query")
                    && statement.startsWith("com.wordtrail.learning.mapper.LearningInsightsMapper.")
                    && afterNextInsightsRead != null) {
                var action = afterNextInsightsRead; afterNextInsightsRead = null; action.run();
            }
            if (invocation.getMethod().getName().equals("update")
                    && failAfterMapper != null && statement.startsWith(failAfterMapper + ".")) {
                throw new IllegalStateException("Injected persistence failure after write");
            }
            return result;
        }
    }
    @TestConfiguration static class Instrumentation {
        @Bean DatabaseProbe databaseProbe() { return new DatabaseProbe(); }
        @Bean @Primary StudyClock studyClock() { return new StudyClock(); }
        @Bean @Primary FixtureAudio fixtureAudio() { return new FixtureAudio(); }
    }
    static class FixtureAudio implements com.wordtrail.learning.service.PronunciationProvider {
        volatile String lastWord;
        volatile com.wordtrail.learning.dto.PronunciationRequest.Accent lastAccent;
        public byte[] audio(String word,com.wordtrail.learning.dto.PronunciationRequest.Accent accent) {
            lastWord=word; lastAccent=accent; return "ID3-test-pronunciation".getBytes(StandardCharsets.UTF_8);
        }
    }
    @Autowired private FixtureAudio audioFixture;

    static class StudyClock extends Clock {
        private volatile Instant now = Instant.now();
        void reset() { now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        void at(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private final Environment environment;
    private final ObjectMapper json;
    private final JwtTokenService jwt;
    private final UserMapper users;
    private final WordMapper words;
    private final WordBookMapper books;
    private final WordBookItemMapper bookItems;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper details;
    private final UserWordStateMapper states;
    private final DatabaseProbe probe;
    private final StudyClock clock;
    private final PasswordService passwords;
    private final RefreshTokenMapper refreshTokens;
    private final WordReviewLogMapper reviewLogs;
    @Autowired private DictationMapper dictationLogs;
    @Autowired private FollowTypingMapper followSelection;
    @Autowired private WordTrainingAttemptMapper trainingAttempts;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> userIds = new ArrayList<>(), bookIds = new ArrayList<>(), wordIds = new ArrayList<>();
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private UserLearningSettingMapper learningSettings;
    @Autowired private com.wordtrail.user.mapper.AuthSessionMapper authSessions;
    private long a, b, systemBook, ownBook, otherBook, emptyBook;
    private String tokenA, tokenB, prefix;

    @Autowired LearningIntegrationTests(Environment environment, ObjectMapper json, JwtTokenService jwt,
            UserMapper users, WordMapper words, WordBookMapper books, WordBookItemMapper bookItems,
            LearningBatchMapper batches, LearningBatchItemMapper details, UserWordStateMapper states,
            DatabaseProbe probe, StudyClock clock, PasswordService passwords, RefreshTokenMapper refreshTokens,
            WordReviewLogMapper reviewLogs) {
        this.environment = environment; this.json = json; this.jwt = jwt;
        this.users = users; this.words = words; this.books = books; this.bookItems = bookItems;
        this.batches = batches; this.details = details; this.states = states; this.probe = probe;
        this.clock = clock;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.reviewLogs = reviewLogs;
    }

    @BeforeEach void seed() throws Exception {
        IsolatedDatabaseGuard.check(dataSource);
        probe.failAfterMapper = null;
        probe.afterNextStateRead = null;
        probe.afterNextInsightsRead = null;
        clock.reset();
        prefix = "fixture" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        a = user(prefix + "_a"); b = user(prefix + "_b");
        tokenA = jwt.issue(a); tokenB = jwt.issue(b);
        systemBook = book(prefix + "_system", null);
        ownBook = book(prefix + "_own", a);
        otherBook = book(prefix + "_other", b);
        emptyBook = book(prefix + "_empty", a);
        for (String spelling : List.of("apple", "apricot", "banana", "%literal", "_literal", "!literal", "\\literal", "outside")) {
            word(prefix + spelling);
        }
        int[] order = {20, 10, 10, 30, 40, 50, 60};
        for (int i = 0; i < order.length; i++) { associate(systemBook, wordIds.get(i), order[i]); }
        associate(ownBook, wordIds.get(2), 0);
        associate(otherBook, wordIds.get(7), 0);
        learn(a, wordIds.get(0), 3);
        learn(b, wordIds.get(1), 1);
    }

    @AfterEach void cleanup() {
        probe.failAfterMapper = null;
        probe.afterNextStateRead = null;
        probe.afterNextInsightsRead = null;
        if (!userIds.isEmpty()) {
            trainingAttempts.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            dictationLogs.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            learningSettings.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            reviewLogs.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds))
                .and(column("event_type").eq("CORRECTION")));
            reviewLogs.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            states.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            var ids = batches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
                .stream().map(LearningBatch::getId).toList();
            if (!ids.isEmpty()) { details.deleteByQuery(QueryWrapper.create().where(column("batch_id").in(ids))); }
            batches.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
        }
        if (!bookIds.isEmpty()) {
            bookItems.deleteByQuery(QueryWrapper.create().where(column("book_id").in(bookIds)));
            books.deleteBatchByIds(bookIds);
        }
        if (!wordIds.isEmpty()) { words.deleteBatchByIds(wordIds); }
        if (!userIds.isEmpty()) {
            refreshTokens.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            authSessions.deleteByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
            users.deleteBatchByIds(userIds);
        }
    }

    private long user(String username) {
        var user = new UserAccount();
        user.setUsername(username); user.setPasswordHash("fixture-not-used-for-login");
        users.insertSelective(user); userIds.add(user.getId()); return user.getId();
    }
    private long book(String name, Long owner) {
        var book = new WordBook();
        book.setName(name); book.setDescription("fixture description"); book.setOwnerId(owner);
        books.insertSelective(book); bookIds.add(book.getId()); return book.getId();
    }
    private long word(String spelling) {
        var word = new Word();
        word.setWord(spelling); word.setPhonetic("/test/"); word.setTranslation("测试");
        words.insertSelective(word); wordIds.add(word.getId()); return word.getId();
    }
    private void associate(long bookId, long wordId, int order) {
        var item = new WordBookItem();
        item.setBookId(bookId); item.setWordId(wordId); item.setSortOrder(order);
        bookItems.insertSelective(item);
    }
    private void learn(long userId, long wordId, int result) {
        var batch = new LearningBatch();
        batch.setUserId(userId); batch.setBookId(systemBook); batch.setSelectionType("MANUAL");
        batch.setStatus("COMPLETED"); batch.setCompletedAt(LocalDateTime.now());
        batches.insertSelective(batch);
        var item = new LearningBatchItem();
        item.setBatchId(batch.getId()); item.setWordId(wordId); item.setSortOrder(1);
        item.setResult(result); item.setAnsweredAt(LocalDateTime.now());
        details.insertSelective(item);
        var state = new UserWordState();
        state.setUserId(userId); state.setWordId(wordId); state.setLastResult(result);
        state.setJoinedAt(LocalDateTime.now()); state.setPlanStatus("ACTIVE"); state.setReviewLevel(0);
        state.setLastBatchItemId(item.getId()); state.setFirstLearnedAt(LocalDateTime.now());
        state.setLastLearnedAt(LocalDateTime.now()); states.insertSelective(state);
    }
    private String encoded(String input) { return URLEncoder.encode(input, StandardCharsets.UTF_8); }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"
            + environment.getRequiredProperty("local.server.port") + path));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(response.body().contains("password"));
        return response;
    }
    private JsonNode get(String path, String token) throws Exception {
        var response = call("GET", path, null, token);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }
    private String wordsPath(long bookId) { return "/api/word-books/" + bookId + "/words"; }
    private List<Long> ids(JsonNode records) {
        var result = new ArrayList<Long>();
        records.forEach(record -> result.add(record.get("id").asLong()));
        return result;
    }
    private List<LearningBatchItem> batchDetails(long batchId) {
        return details.selectListByQuery(QueryWrapper.create().where(column("batch_id").eq(batchId))
            .orderBy(column("sort_order").asc()));
    }
    private long countBatches() {
        return batches.selectCountByQuery(QueryWrapper.create().where(column("user_id").in(userIds)));
    }
    private String stateSnapshot() throws Exception {
        return json.writeValueAsString(states.selectListByQuery(QueryWrapper.create()
            .where(column("user_id").in(userIds)).orderBy(column("id").asc())));
    }

    @Test void allEndpointsRequireLogin() throws Exception {
        for (String path : List.of("/api/word-books", wordsPath(systemBook))) {
            var response = call("GET", path, null, null);
            assertEquals(401, response.statusCode());
            assertEquals("UNAUTHENTICATED", json.readTree(response.body()).get("code").asText());
        }
        assertEquals(401, call("POST", "/api/learning-batches",
            Map.of("bookId", systemBook, "selectionType", "BOOK"), null).statusCode());
    }

    @Test void listVisibilityCountsPaginationAndStableOrdering() throws Exception {
        var data = get("/api/word-books?keyword=" + prefix, tokenA);
        assertEquals(1, data.get("page").asInt()); assertEquals(20, data.get("size").asInt());
        assertEquals(3, data.get("total").asLong());
        assertEquals(List.of(systemBook, ownBook, emptyBook), ids(data.get("records")));
        assertEquals(List.of(7L, 1L, 0L), java.util.stream.StreamSupport
            .stream(data.get("records").spliterator(), false).map(row -> row.get("wordCount").asLong()).toList());
        var page = get("/api/word-books?keyword=" + prefix + "&page=2&size=1", tokenA);
        assertEquals(3, page.get("total").asLong());
        assertEquals(List.of(ownBook), ids(page.get("records")));
        assertEquals(List.of(systemBook, otherBook), ids(get("/api/word-books?keyword=" + prefix, tokenB).get("records")));
        assertTrue(get("/api/word-books?keyword=" + prefix + "&page=2147483647", tokenA).get("records").isEmpty());
    }

    @Test void bookSearchEscapesWildcardsAndSqlSyntaxLiterally() throws Exception {
        long literal = book(prefix + "%_!\\", a);
        book(prefix + "somethingelse", a);
        for (String keyword : List.of(prefix + "%", prefix + "%_", prefix + "%_!", prefix + "%_!\\")) {
            assertEquals(List.of(literal), ids(get("/api/word-books?keyword=" + encoded(keyword), tokenA).get("records")));
        }
        assertTrue(get("/api/word-books?keyword=" + encoded("' OR 1=1 --"), tokenA).get("records").isEmpty());
    }

    @Test void bookCountsDoNotCauseNPlusOneQueries() throws Exception {
        for (int i = 0; i < 5; i++) { book(prefix + "_extra" + i, a); }
        probe.reads.set(0);
        get("/api/word-books?keyword=" + prefix + "&size=1", tokenA);
        int one = probe.reads.get();
        probe.reads.set(0);
        get("/api/word-books?keyword=" + prefix + "&size=20", tokenA);
        int many = probe.reads.get();
        assertTrue(one > 0);
        assertTrue(many <= one + 1, "Query count must stay constant as the page grows");
    }

    @Test void missingAndForbiddenBooksAreIndistinguishable() throws Exception {
        var missing = call("GET", wordsPath(Long.MAX_VALUE), null, tokenA);
        var forbidden = call("GET", wordsPath(otherBook), null, tokenA);
        assertEquals(404, missing.statusCode()); assertEquals(missing.body(), forbidden.body());

    }

    @Test void wordOrderPaginationAndUserLearningStateAreCorrect() throws Exception {
        var page = get(wordsPath(systemBook), tokenA);
        assertEquals(7, page.get("total").asLong());
        assertEquals(List.of(wordIds.get(1), wordIds.get(2), wordIds.get(0),
            wordIds.get(3), wordIds.get(4), wordIds.get(5), wordIds.get(6)), ids(page.get("records")));
        var records = page.get("records");
        assertFalse(records.get(0).get("learned").asBoolean());
        assertTrue(records.get(0).get("lastResult").isNull());
        assertTrue(records.get(2).get("learned").asBoolean());
        assertEquals(3, records.get(2).get("lastResult").asInt());
        assertEquals(7, records.get(0).size());
        var secondPage = get(wordsPath(systemBook) + "?page=2&size=2", tokenA);
        assertEquals(7, secondPage.get("total").asLong());
        assertEquals(List.of(wordIds.get(0), wordIds.get(3)), ids(secondPage.get("records")));
        assertEquals(List.of(wordIds.get(0)), ids(get(wordsPath(systemBook) + "?learningStatus=LEARNED", tokenA).get("records")));
        var unlearned = get(wordsPath(systemBook) + "?learningStatus=UNLEARNED", tokenA);
        assertEquals(6, unlearned.get("total").asLong());
        assertTrue(ids(unlearned.get("records")).contains(wordIds.get(1)));
        assertEquals(List.of(wordIds.get(1)), ids(get(wordsPath(systemBook) + "?learningStatus=LEARNED", tokenB).get("records")));
        assertEquals(0, get(wordsPath(emptyBook), tokenA).get("total").asLong());
        assertTrue(get(wordsPath(emptyBook), tokenA).get("records").isEmpty());
    }

    @Test void wordPrefixAndStatusFiltersUseLiteralInput() throws Exception {
        assertEquals(List.of(wordIds.get(1), wordIds.get(0)),
            ids(get(wordsPath(systemBook) + "?keyword=" + prefix + "ap", tokenA).get("records")));
        assertEquals(List.of(wordIds.get(0)), ids(get(wordsPath(systemBook)
            + "?keyword=" + prefix + "ap&learningStatus=LEARNED", tokenA).get("records")));
        for (int i = 3; i <= 6; i++) {
            assertEquals(List.of(wordIds.get(i)), ids(get(wordsPath(systemBook) + "?keyword="
                + encoded(words.selectOneById(wordIds.get(i)).getWord().substring(0, prefix.length() + 1)), tokenA).get("records")));
        }
        assertTrue(get(wordsPath(systemBook) + "?keyword=" + encoded("' OR 1=1 --"), tokenA).get("records").isEmpty());
    }

    @Test void invalidBookQueryParametersReturn400() throws Exception {
        for (String query : List.of("page=0", "page=-1", "page=abc", "size=0", "size=101", "size=", "page=")) {
            assertEquals(400, call("GET", "/api/word-books?" + query, null, tokenA).statusCode());
            assertEquals(400, call("GET", wordsPath(systemBook) + "?" + query, null, tokenA).statusCode());
        }
        for (String status : List.of("INVALID", "learned", "")) {
            assertEquals(400, call("GET", wordsPath(systemBook) + "?learningStatus=" + status, null, tokenA).statusCode());
        }
        assertEquals(400, call("GET", wordsPath(-1), null, tokenA).statusCode());

    }

    private String batchPath(long batchId) { return "/api/learning-batches/" + batchId; }
    private String itemPath(long batchId, long itemId) { return batchPath(batchId) + "/items/" + itemId; }
    private UserWordState state(long userId, long wordId) {
        return states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").eq(wordId)));
    }
    private long itemId(long batchId) { return batchDetails(batchId).getFirst().getId(); }
    private String batchSnapshot(long batchId) throws Exception {
        return json.writeValueAsString(List.of(batches.selectOneById(batchId), batchDetails(batchId)));
    }

    // Seed pre-removal records directly: historical queries must work without retired APIs.
    private long historicalPractice(Long... selected) {
        var batch = new LearningBatch(); batch.setUserId(a); batch.setBookId(systemBook);
        batch.setTaskType("PRACTICE"); batch.setPracticeMode("RECOGNITION");
        batch.setSelectionType("MANUAL"); batch.setStatus("IN_PROGRESS");
        batches.insertSelective(batch);
        for (int index=0; index<selected.length; index++) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(selected[index]);
            item.setSortOrder(index+1); item.setQueueOrder((long)index+1); item.setLearningStatus("PENDING");
            item.setAnswerCount(0); item.setRelearnCount(0); details.insertSelective(item);
        }
        return batch.getId();
    }
    private void historicalFeedback(long batchId, long itemId, int result) {
        var item=details.selectOneById(itemId);
        var now=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0);
        boolean correction=item.getResult()!=null;
        var event=new WordReviewLog(); event.setUserId(a); event.setWordId(item.getWordId());
        event.setBatchId(batchId); event.setBatchItemId(itemId); event.setRequestId(requestId());
        event.setEventType(correction?"CORRECTION":"ANSWER"); event.setResult(result);
        event.setLearningDate(now.toLocalDate()); event.setAnsweredAt(now); event.setCreatedAt(now);
        event.setAffectsSchedule(false); event.setFeedbackMode("RECOGNITION");
        if(correction) {
            var source=reviewLogs.selectOneByQuery(QueryWrapper.create().where(column("batch_item_id").eq(itemId))
                .and(column("event_type").eq("ANSWER")));
            event.setCorrectsLogId(source.getId()); event.setAttemptNo(source.getAttemptNo());
        } else {
            event.setAttemptNo(Math.toIntExact(reviewLogs.selectCountByQuery(QueryWrapper.create()
                .where(column("user_id").eq(a)).and(column("word_id").eq(item.getWordId()))
                .and(column("learning_date").eq(now.toLocalDate())).and(column("event_type").eq("ANSWER"))))+1);
        }
        reviewLogs.insertSelective(event);
        item.setResult(result); item.setAnsweredAt(now); item.setCompletedAt(now);
        item.setLearningStatus("DONE"); item.setAnswerCount(1); details.update(item);
        if(batchDetails(batchId).stream().allMatch(i->i.getResult()!=null)) {
            var batch=batches.selectOneById(batchId); batch.setStatus("COMPLETED");
            batch.setCompletedAt(now); batches.update(batch);
        }
    }

    @Test void removedRecognitionPracticeRoutesReturn404WithoutChangingAnyLearningData() throws Exception {
        long historical=historicalPractice(wordIds.get(1)); historicalFeedback(historical,itemId(historical),3);
        long pending=historicalPractice(wordIds.get(2));
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong();
        long dictation=createDictation(Map.of()).get("batchId").asLong();
        var snapshots=new LinkedHashMap<Long,String>();
        for(long id:List.of(historical,pending,daily,dictation)) snapshots.put(id,batchSnapshot(id));
        String memory=stateSnapshot(); long batchCount=countBatches(), events=logCount(historical);
        int writes=probe.writes.get();
        assertEquals(404,call("GET","/api/learning-batches",null,tokenA).statusCode());
        assertEquals(404,call("POST","/api/learning-batches",Map.of("bookId",systemBook,"selectionType","BOOK"),tokenA).statusCode());
        assertEquals(404,call("POST","/api/learning/weak-words/practice",Map.of("source","DAILY","wordIds",List.of(wordIds.get(1))),tokenA).statusCode());
        for(long id:snapshots.keySet()) {
            for(String token:List.of(tokenA,tokenB)) {
                for(String suffix:List.of("","/items/"+itemId(id),"/result","/result/words"))
                    assertEquals(404,call("GET",batchPath(id)+suffix,null,token).statusCode());
                assertEquals(404,call("POST",batchPath(id)+"/relearn",Map.of(),token).statusCode());
                assertEquals(404,call("PUT",itemPath(id,itemId(id))+"/answer",Map.of("result",1),token).statusCode());
            }
            assertEquals(snapshots.get(id),batchSnapshot(id));
        }
        assertEquals(writes,probe.writes.get()); assertEquals(memory,stateSnapshot());
        assertEquals(batchCount,countBatches()); assertEquals(events,logCount(historical));
        assertEquals(1,weakWords("PRACTICE").get("total").asInt());
        assertEquals("DAILY",get(dailyPath(daily),tokenA).get("taskType").asText());
        assertEquals("IN_PROGRESS",get(dictationPath(dictation),tokenA).get("status").asText());
        assertEquals("COMPLETED",dailyAnswer(daily,itemId(daily),1,1).at("/progress/status").asText());
    }

    private JsonNode joinPlan(Long... ids) throws Exception {
        var response=call("POST","/api/learning-plan/words",Map.of("bookId",systemBook,"wordIds",List.of(ids)),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private JsonNode startDay() throws Exception {
        var response=call("POST","/api/learning/today/start",null,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private String dailyPath(long id) { return batchPath(id)+"/daily"; }
    private String dailyItem(long batch,long item) { return dailyPath(batch)+"/items/"+item; }
    private String requestId() { return UUID.randomUUID().toString(); }
    private JsonNode dailyAnswer(long batch,long item,int result,int attempt,String requestId) throws Exception {
        var response=call("PUT",dailyItem(batch,item)+"/answer",
            Map.of("result",result,"attemptNo",attempt,"requestId",requestId),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private JsonNode dailyAnswer(long batch,long item,int result,int attempt) throws Exception {
        return dailyAnswer(batch,item,result,attempt,requestId());
    }
    private JsonNode correction(long batch,long item,long log,int result,String requestId) throws Exception {
        var response=call("PUT",dailyItem(batch,item)+"/correction",
            Map.of("result",result,"logId",log,"requestId",requestId),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private void advanceDays(int days) {
        clock.advanceSeconds(days*86400L); tokenA=jwt.issue(a); tokenB=jwt.issue(b);
    }
    private long logCount(long batch) {
        return reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch)));
    }

    @Test void planJoinDeduplicatesAcrossBooksAndDoesNotMarkWordsLearned() throws Exception {
        var joined=joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(1));
        assertEquals(2,joined.get("addedCount").asInt());
        associate(ownBook,wordIds.get(1),1);
        var duplicate=call("POST","/api/learning-plan/words",Map.of("bookId",ownBook,
            "wordIds",List.of(wordIds.get(1),wordIds.get(2))),tokenA);
        assertEquals(200,duplicate.statusCode()); assertEquals(2,json.readTree(duplicate.body()).at("/data/unchangedCount").asInt());
        var state=state(a,wordIds.get(1)); assertNotNull(state); assertNotNull(state.getJoinedAt());
        assertNull(state.getFirstLearnedAt()); assertNull(state.getLastResult()); assertNull(state.getNextReviewAt());
        assertNull(state.getLastBatchItemId()); assertEquals(0,state.getReviewLevel());
        var fresh=get("/api/learning-plan/words?learningStatus=NEW",tokenA);
        assertEquals(2,fresh.get("total").asInt());
        var bookWords=get(wordsPath(systemBook)+"?learningStatus=UNLEARNED",tokenA);
        var selected=bookWords.get("records").findValues("id").stream().map(JsonNode::asLong).toList();
        assertTrue(selected.contains(wordIds.get(1))); assertTrue(selected.contains(wordIds.get(2)));
        assertEquals(6,bookWords.get("total").asInt());
    }

    @Test void planPauseRemovalAndRejoinPreserveMemoryAndOriginalJoinTime() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong();
        dailyAnswer(batch,itemId(batch),1,1);
        var original=json.valueToTree(state(a,wordIds.get(1)));
        for(String status:List.of("PAUSED","ACTIVE","PAUSED","REMOVED")) {
            assertEquals(200,call("PATCH","/api/learning-plan/words/"+wordIds.get(1),Map.of("status",status),tokenA).statusCode());
            var after=json.valueToTree(state(a,wordIds.get(1)));
            assertEquals(original.get("joinedAt"),after.get("joinedAt")); assertEquals(original.get("nextReviewAt"),after.get("nextReviewAt"));
            assertEquals(original.get("firstLearnedAt"),after.get("firstLearnedAt")); assertEquals(original.get("reviewLevel"),after.get("reviewLevel"));
        }
        assertEquals(200,call("PATCH","/api/learning-plan/words/"+wordIds.get(1),Map.of("status","REMOVED"),tokenA).statusCode());
        advanceDays(2);
        assertEquals(0,get("/api/learning/today",tokenA).get("dueCount").asInt());
        assertEquals(1,joinPlan(wordIds.get(1)).get("reactivatedCount").asInt());
        assertEquals(1,get("/api/learning/today",tokenA).get("dueCount").asInt());
        assertEquals(original.get("nextReviewAt"),json.valueToTree(state(a,wordIds.get(1))).get("nextReviewAt"));
    }

    @Test void planValidationVisibilityAndAllOrNothingSelection() throws Exception {
        String snapshot=stateSnapshot();
        for(Object body:List.of(Map.of("bookId",systemBook,"wordIds",List.of(wordIds.get(1),wordIds.get(7))),
                Map.of("bookId",otherBook,"wordIds",List.of(wordIds.get(7))),
                Map.of("bookId",systemBook,"wordIds",List.of()),Map.of("bookId",systemBook,"wordIds",List.of(wordIds.get(1)),"user_id",b))) {
            assertTrue(Set.of(400,404).contains(call("POST","/api/learning-plan/words",body,tokenA).statusCode()));
        }
        assertEquals(snapshot,stateSnapshot());
        joinPlan(wordIds.get(1),wordIds.get(2));
        assertEquals(0,get("/api/learning-plan/words?learningStatus=NEW",tokenB).get("total").asInt());
        assertEquals(404,call("PATCH","/api/learning-plan/words/"+wordIds.get(2),Map.of("status","REMOVED"),tokenB).statusCode());
        assertEquals(2,get("/api/learning-plan/words?learningStatus=NEW&size=1",tokenA).get("total").asInt());
        for(String query:List.of("page=0","size=101","learningStatus=INVALID","status=ALL")) {
            assertEquals(400,call("GET","/api/learning-plan/words?"+query,null,tokenA).statusCode());
        }
        assertEquals(401,call("POST","/api/learning/today/start",null,null).statusCode());
        assertEquals(401,call("GET","/api/learning-plan/words",null,null).statusCode());
    }

    @Test void dailyKnownWordsRemainInPlanAndAdvanceIntervalsAcrossDays() throws Exception {
        joinPlan(wordIds.get(1));
        int[] intervals={1,3,7,15,30,60,90,90};
        for(int day=0;day<intervals.length;day++) {
            var task=startDay(); long batch=task.get("batchId").asLong();
            assertEquals(day==0?"NEW":"REVIEW",batchDetails(batch).getFirst().getItemType());
            var result=dailyAnswer(batch,itemId(batch),1,1);
            assertEquals("COMPLETED",result.at("/progress/status").asText());
            var memory=state(a,wordIds.get(1));
            assertEquals(Math.min(day,6),memory.getReviewLevel());
            var date=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            assertEquals(date.plusDays(intervals[day]).atStartOfDay(),memory.getNextReviewAt());
            assertEquals("ACTIVE",memory.getPlanStatus());
            assertEquals(batch,startDay().get("batchId").asLong());
            advanceDays(intervals[day]);
        }
    }

    @Test void weakWordsReappearAfterThreeOthersAndReinforcementDoesNotAdvanceSchedule() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch);
        long weak=entries.getFirst().getId(); dailyAnswer(batch,weak,2,1);
        var due=state(a,entries.getFirst().getWordId()).getNextReviewAt();
        for(int i=1;i<=3;i++) {
            assertEquals(entries.get(i).getId(),get(dailyPath(batch),tokenA).get("nextItemId").asLong());
            dailyAnswer(batch,entries.get(i).getId(),1,1);
        }
        assertEquals(weak,get(dailyPath(batch),tokenA).get("nextItemId").asLong());
        assertFalse(get(dailyItem(batch,weak),tokenA).has("translation"));
        assertEquals(2,get(dailyItem(batch,weak),tokenA).get("nextAttemptNo").asInt());
        dailyAnswer(batch,weak,1,2);
        assertEquals(due,state(a,entries.getFirst().getWordId()).getNextReviewAt());
        assertEquals(0,state(a,entries.getFirst().getWordId()).getReviewLevel());
        dailyAnswer(batch,entries.get(4).getId(),1,1);
        var done=dailyAnswer(batch,weak,1,3).get("progress");
        assertEquals(5,done.get("totalCount").asInt()); assertEquals(5,done.get("completedCount").asInt());
        assertEquals(7,done.get("answerCount").asInt()); assertEquals(0,done.get("unfamiliarCount").asInt());
        assertEquals(5,reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))
            .and(column("affects_schedule").eq(true))));
        assertEquals(5,batchDetails(batch).size());
    }

    @Test void unknownWordStopsAtSixAttemptsAndRemainsDueTomorrow() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        LocalDateTime firstDue=null;
        for(int attempt=1;attempt<=6;attempt++) {
            var response=dailyAnswer(batch,item,3,attempt);
            var due=state(a,wordIds.get(1)).getNextReviewAt(); if(firstDue==null) firstDue=due; else assertEquals(firstDue,due);
            assertEquals(attempt,logCount(batch));
            assertEquals(attempt==6?1:0,response.at("/progress/completedCount").asInt());
        }
        var done=get(dailyPath(batch),tokenA); assertEquals(1,done.get("unfamiliarCount").asInt());
        assertEquals(6,done.get("answerCount").asInt()); assertTrue(done.get("nextItemId").isNull());
        assertEquals(5,details.selectOneById(item).getRelearnCount());
        advanceDays(1); var tomorrow=startDay(); assertNotEquals(batch,tomorrow.get("batchId").asLong());
        assertEquals(1,tomorrow.get("reviewCount").asInt());
    }

    @Test void dailyRetryAndStaleAttemptCannotDoubleCountOrReschedule() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        String request=requestId(); var first=dailyAnswer(batch,item,3,1,request);
        String snapshot=stateSnapshot(), queue=batchSnapshot(batch); clock.advanceSeconds(60);
        assertEquals(first,dailyAnswer(batch,item,3,1,request));
        assertEquals(snapshot,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(1,logCount(batch));
        for(Object body:List.of(Map.of("result",3,"attemptNo",1,"requestId",requestId()),
                Map.of("result",1,"attemptNo",1,"requestId",request),Map.of("result",3,"attemptNo",2,"requestId",request))) {
            assertEquals(409,call("PUT",dailyItem(batch,item)+"/answer",body,tokenA).statusCode());
        }
        dailyAnswer(batch,item,3,2); assertEquals(2,logCount(batch));
    }

    @Test void correctionRecomputesFromOriginalScheduleAndCanReopenCompletedTask() throws Exception {
        joinPlan(wordIds.get(1)); var state=state(a,wordIds.get(1));
        state.setFirstLearnedAt(LocalDateTime.of(2026,1,1,0,0)); state.setReviewLevel(2);
        state.setNextReviewAt(LocalDateTime.of(2026,1,1,0,0)); states.update(state);
        long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        var first=dailyAnswer(batch,item,1,1); long log=first.get("logId").asLong();
        assertEquals(3,state(a,wordIds.get(1)).getReviewLevel());
        String request=requestId(); var changed=correction(batch,item,log,3,request);
        assertEquals("IN_PROGRESS",changed.at("/progress/status").asText());
        assertEquals(0,state(a,wordIds.get(1)).getReviewLevel());
        assertNull(batches.selectOneById(batch).getCompletedAt()); assertNull(details.selectOneById(item).getCompletedAt());
        String snapshot=stateSnapshot(); assertEquals(changed,correction(batch,item,log,3,request));
        assertEquals(snapshot,stateSnapshot()); assertEquals(2,logCount(batch));
        var corrected=correction(batch,item,log,1,requestId());
        assertEquals(3,state(a,wordIds.get(1)).getReviewLevel());
        assertEquals("COMPLETED",corrected.at("/progress/status").asText());
        assertEquals(1,corrected.at("/progress/answerCount").asInt()); assertEquals(1,details.selectOneById(item).getAnswerCount());
        assertEquals(3,logCount(batch));
    }

    @Test void correctionsAreLimitedToLatestAnswerAndDoNotAdvanceReinforcementSchedule() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch);
        long first=entries.get(0).getId(), second=entries.get(1).getId();
        var original=dailyAnswer(batch,first,3,1); var due=state(a,entries.getFirst().getWordId()).getNextReviewAt();
        dailyAnswer(batch,second,1,1);
        assertEquals(409,call("PUT",dailyItem(batch,first)+"/correction",Map.of("result",1,"logId",original.get("logId").asLong(),"requestId",requestId()),tokenA).statusCode());
        var reinforcement=dailyAnswer(batch,first,1,2);
        correction(batch,first,reinforcement.get("logId").asLong(),3,requestId());
        assertEquals(due,state(a,entries.getFirst().getWordId()).getNextReviewAt());
        assertEquals(2,details.selectOneById(first).getAnswerCount());
    }

    @Test void midnightAndInterruptedDaysRestoreFromPersistentMemoryAndQueue() throws Exception {
        clock.at(Instant.parse("2026-10-19T15:59:00Z")); tokenA=jwt.issue(a);
        joinPlan(wordIds.get(1),wordIds.get(2)); long first=startDay().get("batchId").asLong();
        var entries=batchDetails(first); dailyAnswer(first,entries.getFirst().getId(),3,1);
        var saved=get(dailyPath(first),tokenA); assertEquals(saved,get(dailyPath(first),tokenA));
        assertEquals(entries.get(1).getId(),saved.get("nextItemId").asLong());
        clock.advanceSeconds(120); tokenA=jwt.issue(a);
        assertEquals("2026-10-20",get("/api/learning/today",tokenA).get("learningDate").asText());
        long next=startDay().get("batchId").asLong(); assertNotEquals(first,next);
        assertEquals("REVIEW",batchDetails(next).getFirst().getItemType());
        assertEquals(409,call("PUT",dailyItem(first,entries.get(1).getId())+"/answer",Map.of("result",1,"attemptNo",1,"requestId",requestId()),tokenA).statusCode());
        var historical=get(dailyPath(first),tokenA);
        assertTrue(historical.get("nextItemId").isNull());
        ((tools.jackson.databind.node.ObjectNode)saved).putNull("nextItemId");
        assertEquals(saved,historical);
        advanceDays(10); long afterBreak=startDay().get("batchId").asLong();
        assertEquals(2,batchDetails(afterBreak).size());
        assertEquals(entries.getFirst().getWordId(),batchDetails(afterBreak).getFirst().getWordId());
    }

    @Test void concurrentStartUsesOneDailyBatchAndDatabaseUniqueConstraint() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            var two=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            gate.countDown(); var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body()); assertEquals(200,second.statusCode(),second.body());
            assertEquals(json.readTree(first.body()),json.readTree(second.body()));
            long id=json.readTree(first.body()).at("/data/batchId").asLong();
            var duplicate=batches.selectOneById(id); duplicate.setId(null);
            assertThrows(org.springframework.dao.DuplicateKeyException.class,()->batches.insertSelective(duplicate));
            assertEquals(1,batches.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a)).and(column("task_type").eq("DAILY"))));
        }
    }

    @Test void concurrentDailyAnswersDoNotDuplicateEventsOrAdvancement() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        var body=Map.of("result",3,"attemptNo",1,"requestId",requestId());
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",body,tokenA);});
            var two=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",body,tokenA);});
            gate.countDown(); assertEquals(200,one.get(15,TimeUnit.SECONDS).statusCode()); assertEquals(200,two.get(15,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(1,logCount(batch)); assertEquals(1,details.selectOneById(item).getAnswerCount());
        assertEquals(0,state(a,wordIds.get(1)).getReviewLevel());
    }

    @Test void dailyWriteFailuresRollBackQueueMemoryEventsAndCompletionTogether() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        String snapshot=stateSnapshot(), queue=batchSnapshot(batch), request=requestId();
        for(String mapper:List.of("UserWordStateMapper","LearningBatchItemMapper","WordReviewLogMapper","LearningBatchMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try { assertEquals(500,call("PUT",dailyItem(batch,item)+"/answer",Map.of("result",1,"attemptNo",1,"requestId",request),tokenA).statusCode()); }
            finally { probe.failAfterMapper=null; }
            assertEquals(snapshot,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(0,logCount(batch));
        }
        assertEquals("COMPLETED",dailyAnswer(batch,item,1,1,request).at("/progress/status").asText());
    }


    @Test void dailyOwnershipAndMalformedRequestsAreRejected() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        assertEquals(404,call("GET",dailyPath(batch),null,tokenB).statusCode());
        assertEquals(404,call("GET",dailyItem(batch,item),null,tokenB).statusCode());
        assertEquals(401,call("GET",dailyPath(batch),null,null).statusCode());
        assertEquals(404,call("PUT",dailyItem(batch,Long.MAX_VALUE)+"/answer",Map.of("result",1,"requestId",requestId(),"attemptNo",1),tokenA).statusCode());
        for(Object body:List.of(Map.of("result",1),Map.of("result",4,"requestId",requestId(),"attemptNo",1),
                Map.of("result",1,"requestId","short","attemptNo",1),Map.of("result",1,"requestId",requestId(),"attemptNo",7),
                Map.of("result",1,"requestId",requestId(),"attemptNo",1,"user_id",b))) {
            assertEquals(400,call("PUT",dailyItem(batch,item)+"/answer",body,tokenA).statusCode());
        }
        assertEquals(0,logCount(batch));
    }

    @Test void overdueReviewsFillTargetBeforeNewWordsWithoutChangingUnselectedDueDates() throws Exception {
        var extras=new ArrayList<Long>();
        for(int i=0;i<22;i++) { long id=word(prefix+"_daily_extra_"+i); associate(systemBook,id,100+i); extras.add(id); }
        var selected=new ArrayList<>(extras); selected.add(wordIds.get(1)); joinPlan(selected.toArray(Long[]::new));
        var now=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0);
        for(int i=0;i<extras.size();i++) {
            var state=state(a,extras.get(i)); state.setFirstLearnedAt(now.minusDays(100));
            state.setNextReviewAt(now.minusDays(i+1)); states.update(state);
        }
        String before=stateSnapshot(); var task=startDay();
        assertEquals(20,task.get("totalCount").asInt()); assertEquals(20,task.get("reviewCount").asInt());
        assertEquals(0,task.get("newCount").asInt()); assertEquals(before,stateSnapshot());
        var entries=batchDetails(task.get("batchId").asLong());
        assertEquals(extras.getLast(),entries.getFirst().getWordId());
        assertEquals(22,get("/api/learning/today",tokenA).get("dueCount").asInt());
        assertNull(state(a,wordIds.get(1)).getFirstLearnedAt());
        assertFalse(entries.stream().anyMatch(i->i.getWordId().equals(extras.getFirst())));
    }

    @Test void dailyHistoryIsUserScopedAndReadingDoesNotChangeQueueOrTimestamps() throws Exception {
        joinPlan(wordIds.get(1)); long first=startDay().get("batchId").asLong();
        dailyAnswer(first,itemId(first),1,1); advanceDays(1); long second=startDay().get("batchId").asLong();
        String firstBefore=batchSnapshot(first),secondBefore=batchSnapshot(second),memory=stateSnapshot();
        var completed=get("/api/learning/daily-tasks?status=COMPLETED&size=1",tokenA);
        assertEquals(1,completed.get("total").asInt()); assertEquals(first,completed.get("records").get(0).get("batchId").asLong());
        assertEquals(1,get("/api/learning/daily-tasks",tokenA).get("total").asInt());
        assertEquals(0,get("/api/learning/daily-tasks",tokenB).get("total").asInt());
        get(dailyPath(first),tokenA); get(dailyPath(second),tokenA); get(dailyItem(second,itemId(second)),tokenA);
        assertEquals(firstBefore,batchSnapshot(first)); assertEquals(secondBefore,batchSnapshot(second)); assertEquals(memory,stateSnapshot());
        assertEquals(404,call("GET","/api/learning-batches",null,tokenA).statusCode());
    }

    @Test void planAndDailyCreationWriteFailuresLeaveNoPartialRecords() throws Exception {
        String original=stateSnapshot();
        probe.failAfterMapper="com.wordtrail.learning.mapper.UserWordStateMapper";
        try {
            assertEquals(500,call("POST","/api/learning-plan/words",Map.of("bookId",systemBook,
                "wordIds",List.of(wordIds.get(1),wordIds.get(2))),tokenA).statusCode());
        } finally { probe.failAfterMapper=null; }
        assertEquals(original,stateSnapshot()); joinPlan(wordIds.get(1),wordIds.get(2));
        long count=countBatches();
        for(String mapper:List.of("LearningBatchMapper","LearningBatchItemMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try { assertEquals(500,call("POST","/api/learning/today/start",null,tokenA).statusCode()); }
            finally { probe.failAfterMapper=null; }
            assertEquals(count,countBatches()); assertTrue(get("/api/learning/today",tokenA).get("task").isNull());
        }
        assertEquals(2,startDay().get("totalCount").asInt());
    }

    @Test void correctionFailureRollsBackScheduleAuditAndReopenedQueue() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        long log=dailyAnswer(batch,item,1,1).get("logId").asLong();
        String memory=stateSnapshot(), queue=batchSnapshot(batch); clock.advanceSeconds(10);
        for(String mapper:List.of("UserWordStateMapper","LearningBatchItemMapper","WordReviewLogMapper","LearningBatchMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try { assertEquals(500,call("PUT",dailyItem(batch,item)+"/correction",
                Map.of("result",3,"requestId",requestId(),"logId",log),tokenA).statusCode()); }
            finally { probe.failAfterMapper=null; }
            assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(1,logCount(batch));
        }
        assertEquals("IN_PROGRESS",correction(batch,item,log,3,requestId()).at("/progress/status").asText());
    }

    @Test void simultaneousDifferentRequestIdsForSameDailyAttemptOnlyAcceptOne() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(), item=itemId(batch);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",Map.of("result",3,"attemptNo",1,"requestId",requestId()),tokenA);});
            var two=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",Map.of("result",3,"attemptNo",1,"requestId",requestId()),tokenA);});
            gate.countDown(); var codes=List.of(one.get(15,TimeUnit.SECONDS).statusCode(),two.get(15,TimeUnit.SECONDS).statusCode());
            assertTrue(codes.contains(200)); assertTrue(codes.contains(409));
        }
        assertEquals(1,logCount(batch)); assertEquals(1,details.selectOneById(item).getAnswerCount());
    }

    @Test void emptyDailyStartDoesNotFreezeDayBeforeWordsAreJoined() throws Exception {
        long before=countBatches(); var empty=call("POST","/api/learning/today/start",null,tokenA);
        assertEquals(409,empty.statusCode()); assertEquals("NO_DAILY_WORDS",json.readTree(empty.body()).get("code").asText());
        assertEquals(before,countBatches()); assertTrue(get("/api/learning/today",tokenA).get("task").isNull());
        joinPlan(wordIds.get(1)); assertEquals(1,startDay().get("totalCount").asInt());
    }

    @Test void dailyQueueSurvivesLogoutRefreshAndRelogin() throws Exception {
        var account=users.selectOneById(a); account.setPasswordHash(passwords.hash("password123")); users.update(account);
        var credentials=Map.of("username",account.getUsername(),"password","password123");
        var login=call("POST","/api/auth/login",credentials,null); assertEquals(200,login.statusCode());
        var pair=json.readTree(login.body()).get("data"); tokenA=pair.get("accessToken").asText();
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        dailyAnswer(batch,itemId(batch),3,1); var before=get(dailyPath(batch),tokenA);
        var refresh=call("POST","/api/auth/refresh",Map.of("refreshToken",pair.get("refreshToken").asText()),null);
        assertEquals(200,refresh.statusCode()); var rotated=json.readTree(refresh.body()).get("data");
        assertEquals(200,call("POST","/api/auth/logout",Map.of("refreshToken",rotated.get("refreshToken").asText()),null).statusCode());
        var relogin=call("POST","/api/auth/login",credentials,null); assertEquals(200,relogin.statusCode());
        tokenA=json.readTree(relogin.body()).at("/data/accessToken").asText();
        assertEquals(before,get(dailyPath(batch),tokenA)); assertEquals(batch,startDay().get("batchId").asLong());
    }

    @Test void requestCrossingMidnightUsesOneDateForSelectionAndAllAnswerWrites() throws Exception {
        clock.at(Instant.parse("2026-10-19T15:59:59Z")); tokenA=jwt.issue(a);
        joinPlan(wordIds.get(1));
        probe.afterNextStateRead=()->clock.advanceSeconds(2);
        var task=startDay(); long batch=task.get("batchId").asLong(), item=itemId(batch);
        assertEquals("2026-10-19",task.get("learningDate").asText());
        assertEquals("2026-10-20",get("/api/learning/today",tokenA).get("learningDate").asText());
        assertEquals(LocalDate.of(2026,10,19),batches.selectOneById(batch).getCreatedAt().toLocalDate());

        clock.at(Instant.parse("2026-10-19T15:59:59Z"));
        probe.afterNextStateRead=()->clock.advanceSeconds(2);
        var answer=dailyAnswer(batch,item,1,1);
        var event=reviewLogs.selectOneById(answer.get("logId").asLong());
        assertEquals(LocalDate.of(2026,10,19),event.getLearningDate());
        assertEquals(event.getLearningDate(),event.getAnsweredAt().toLocalDate());
        assertEquals(event.getAnsweredAt(),state(a,wordIds.get(1)).getFirstLearnedAt());
        assertEquals(event.getAnsweredAt(),details.selectOneById(item).getCompletedAt());
        assertEquals(event.getAnsweredAt(),batches.selectOneById(batch).getCompletedAt());
        assertEquals(LocalDate.of(2026,10,20).atStartOfDay(),state(a,wordIds.get(1)).getNextReviewAt());
    }


    @Test void allWeakWordsHaveBoundedPersistentQueueAndOnlyOneSchedulePerWord() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        long batch=startDay().get("batchId").asLong();
        for(int answers=0;answers<30;answers++) {
            var progress=get(dailyPath(batch),tokenA); long item=progress.get("nextItemId").asLong();
            var detail=details.selectOneById(item);
            dailyAnswer(batch,item,3,detail.getAnswerCount()+1);
            assertEquals(5,batchDetails(batch).size());
            assertEquals(startDay(),get(dailyPath(batch),tokenA));
        }
        var done=get(dailyPath(batch),tokenA);
        assertEquals("COMPLETED",done.get("status").asText());
        assertEquals(5,done.get("completedCount").asInt());
        assertEquals(5,done.get("unfamiliarCount").asInt());
        assertEquals(30,done.get("answerCount").asInt());
        assertEquals(5,reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))
            .and(column("affects_schedule").eq(true))));
    }

    private LocalDate statisticsDate() {
        return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private JsonNode statisticsDaily(LocalDate date) throws Exception {
        return get("/api/learning/statistics/daily?date="+date,tokenA);
    }

    private void assertStatistics(JsonNode stats,long completed,long answers,long fresh,long reviews,long unfamiliar) {
        assertEquals(completed,stats.get("completedCount").asLong(),stats.toString());
        assertEquals(answers,stats.get("answerCount").asLong(),stats.toString());
        assertEquals(fresh,stats.get("newCompletedCount").asLong(),stats.toString());
        assertEquals(reviews,stats.get("reviewCompletedCount").asLong(),stats.toString());
        assertEquals(unfamiliar,stats.get("unfamiliarCount").asLong(),stats.toString());
        assertEquals(completed,fresh+reviews);
    }

    @Test void statisticsEmptyDatesAndCalendarsReturnZerosWithoutCreatingRecords() throws Exception {
        long before=countBatches(); int writes=probe.writes.get();
        var day=statisticsDaily(statisticsDate());
        assertStatistics(day,0,0,0,0,0);
        assertFalse(day.get("hasLearningActivity").asBoolean());
        assertTrue(day.get("statisticsComplete").asBoolean());
        assertEquals(0,day.get("incompleteWordCount").asInt());
        var calendar=get("/api/learning/statistics/calendar?month=2024-02",tokenA);
        assertEquals(29,calendar.get("days").size());
        for(var entry:calendar.get("days")) {
            assertEquals(0,entry.get("completedCount").asInt());
            assertEquals(0,entry.get("answerCount").asInt());
            assertFalse(entry.get("hasLearningActivity").asBoolean());
            assertTrue(entry.get("statisticsComplete").asBoolean());
        }
        assertEquals("2024-02-29",calendar.get("days").get(28).get("learningDate").asText());
        assertEquals(31,get("/api/learning/statistics/calendar?month=9999-12",tokenA).get("days").size());
        assertEquals(0,get("/api/learning/statistics/words",tokenA).get("total").asInt());
        assertEquals(writes,probe.writes.get()); assertEquals(before,countBatches());
    }

    @Test void statisticsCountReinforcementAndUnfinishedWeakWordsWithoutDoubleCountingWords() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3));
        var review=state(a,wordIds.get(2)); review.setFirstLearnedAt(LocalDateTime.of(2020,1,1,0,0));
        review.setNextReviewAt(statisticsDate().minusDays(2).atStartOfDay()); states.update(review);
        long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch);
        assertEquals("REVIEW",entries.getFirst().getItemType());
        dailyAnswer(batch,entries.get(0).getId(),1,1);
        dailyAnswer(batch,entries.get(1).getId(),2,1);
        assertStatistics(statisticsDaily(statisticsDate()),1,2,0,1,1);
        var partial=get("/api/learning/statistics/words",tokenA);
        assertEquals(2,partial.get("total").asInt());
        assertFalse(partial.get("records").get(1).get("completed").asBoolean());
        dailyAnswer(batch,entries.get(2).getId(),3,1);
        dailyAnswer(batch,entries.get(1).getId(),1,2);
        dailyAnswer(batch,entries.get(2).getId(),3,2);
        dailyAnswer(batch,entries.get(1).getId(),1,3);
        for(int attempt=3;attempt<=6;attempt++) { dailyAnswer(batch,entries.get(2).getId(),3,attempt); }
        var result=statisticsDaily(statisticsDate());
        assertStatistics(result,3,10,2,1,1);
        assertTrue(result.get("hasLearningActivity").asBoolean());
        assertTrue(result.get("statisticsComplete").asBoolean());
        var learned=get("/api/learning/statistics/words",tokenA);
        assertEquals(3,learned.get("total").asInt());
        assertEquals(List.of(1,3,6),java.util.stream.StreamSupport.stream(learned.get("records").spliterator(),false)
            .map(r->r.get("answerCount").asInt()).toList());
        for(var word:learned.get("records")) {
            assertTrue(word.get("completed").asBoolean());
            assertTrue(word.get("statisticsComplete").asBoolean());
            assertEquals("测试",word.get("translation").asText());
        }
    }

    @Test void statisticsUseLatestCorrectedAnswerAndRetriesDoNotIncreaseCounts() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        String request=requestId(); var first=dailyAnswer(batch,item,1,1,request);
        long log=first.get("logId").asLong();
        dailyAnswer(batch,item,1,1,request);
        assertStatistics(statisticsDaily(statisticsDate()),1,1,1,0,0);
        String correctionRequest=requestId(); correction(batch,item,log,3,correctionRequest);
        correction(batch,item,log,3,correctionRequest);
        assertStatistics(statisticsDaily(statisticsDate()),0,1,0,0,1);
        var weak=get("/api/learning/statistics/words",tokenA).get("records").get(0);
        assertEquals(3,weak.get("lastResult").asInt()); assertEquals(1,weak.get("answerCount").asInt());
        assertFalse(weak.get("completed").asBoolean());
        correction(batch,item,log,1,requestId());
        assertStatistics(statisticsDaily(statisticsDate()),1,1,1,0,0);
        correction(batch,item,log,3,requestId());
        dailyAnswer(batch,item,1,2);
        // A retry of an older correction returns its old event, without undoing the latest answer.
        correction(batch,item,log,3,correctionRequest);
        assertStatistics(statisticsDaily(statisticsDate()),0,2,0,0,0);
        assertEquals(1,get("/api/learning/statistics/words",tokenA).get("records").get(0).get("lastResult").asInt());
        assertEquals(409,call("PUT",dailyItem(batch,item)+"/correction",
            Map.of("result",3,"logId",log,"requestId",requestId()),tokenA).statusCode());
        assertTrue(statisticsDaily(statisticsDate()).get("statisticsComplete").asBoolean());
    }

    @Test void statisticsUseShanghaiDatesAndPreserveHistoryAcrossLeapDayAndMonthBoundary() throws Exception {
        clock.at(Instant.parse("2024-02-28T15:59:59Z")); tokenA=jwt.issue(a);
        joinPlan(wordIds.get(1)); long previous=startDay().get("batchId").asLong();
        dailyAnswer(previous,itemId(previous),3,1);
        assertEquals(LocalDate.of(2024,2,28),batches.selectOneById(previous).getLearningDate());
        assertStatistics(statisticsDaily(LocalDate.of(2024,2,28)),0,1,0,0,1);
        clock.advanceSeconds(2); tokenA=jwt.issue(a);
        assertEquals("2024-02-29",get("/api/learning/statistics/daily",tokenA).get("learningDate").asText());
        assertStatistics(statisticsDaily(LocalDate.of(2024,2,28)),0,1,0,0,1);
        assertStatistics(statisticsDaily(statisticsDate()),0,0,0,0,0);
        long today=startDay().get("batchId").asLong(); dailyAnswer(today,itemId(today),1,1);
        assertStatistics(statisticsDaily(statisticsDate()),1,1,0,1,0);
        assertEquals(3,get("/api/learning/statistics/words?date=2024-02-28",tokenA).get("records").get(0).get("lastResult").asInt());
        var month=get("/api/learning/statistics/calendar",tokenA);
        assertEquals("2024-02",month.get("month").asText()); assertEquals(29,month.get("days").size());
        assertEquals(1,month.get("days").get(27).get("answerCount").asInt());
        assertEquals(0,month.get("days").get(27).get("completedCount").asInt());
        assertEquals(1,month.get("days").get(28).get("completedCount").asInt());
        assertFalse(month.get("days").get(26).get("hasLearningActivity").asBoolean());
        clock.at(Instant.parse("2024-02-29T16:00:01Z")); tokenA=jwt.issue(a);
        assertEquals("2024-03-01",get("/api/learning/statistics/daily",tokenA).get("learningDate").asText());
        assertEquals("2024-03",get("/api/learning/statistics/calendar",tokenA).get("month").asText());
        assertStatistics(statisticsDaily(LocalDate.of(2024,2,29)),1,1,0,1,0);
    }

    @Test void statisticsIgnorePracticeAndCurrentWordMemory() throws Exception {
        long practice=historicalPractice(wordIds.get(1)); historicalFeedback(practice,itemId(practice),3);
        assertStatistics(statisticsDaily(statisticsDate()),0,0,0,0,0);
        assertFalse(statisticsDaily(statisticsDate()).get("hasLearningActivity").asBoolean());
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); dailyAnswer(batch,itemId(batch),1,1);
        var before=statisticsDaily(statisticsDate());
        long anotherPractice=historicalPractice(wordIds.get(1)); historicalFeedback(anotherPractice,itemId(anotherPractice),2);
        var memory=state(a,wordIds.get(1)); memory.setLastResult(3); memory.setPlanStatus("REMOVED"); states.update(memory);
        assertEquals(before,statisticsDaily(statisticsDate()));
        assertEquals(1,get("/api/learning/statistics/words",tokenA).get("records").get(0).get("lastResult").asInt());
    }

    @Test void statisticsReportPartialAndMissingEventsWithoutInventingAttempts() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        long first=dailyAnswer(batch,item,3,1).get("logId").asLong();
        for(int attempt=2;attempt<=6;attempt++) { dailyAnswer(batch,item,3,attempt); }
        reviewLogs.deleteById(first); // Delete only this test fixture's event to model an incomplete import.
        var partial=statisticsDaily(statisticsDate());
        assertStatistics(partial,1,5,1,0,1);
        assertFalse(partial.get("statisticsComplete").asBoolean());
        assertEquals(1,partial.get("incompleteWordCount").asInt());
        reviewLogs.deleteByQuery(QueryWrapper.create().where(column("batch_id").eq(batch)));
        var missing=statisticsDaily(statisticsDate());
        assertStatistics(missing,1,0,1,0,1);
        assertTrue(missing.get("hasLearningActivity").asBoolean());
        assertFalse(missing.get("statisticsComplete").asBoolean());
        var word=get("/api/learning/statistics/words",tokenA).get("records").get(0);
        assertEquals(0,word.get("answerCount").asInt()); assertEquals(3,word.get("lastResult").asInt());
        assertFalse(word.get("statisticsComplete").asBoolean());
        var month=get("/api/learning/statistics/calendar",tokenA);
        assertFalse(month.get("statisticsComplete").asBoolean());
        var day=month.get("days").get(statisticsDate().getDayOfMonth()-1);
        assertEquals(0,day.get("answerCount").asInt()); assertTrue(day.get("hasLearningActivity").asBoolean());
        assertFalse(day.get("statisticsComplete").asBoolean());
    }

    @Test void statisticsPaginateStudiedWordsOnlyAndUseBoundedReadQueries() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3)); long batch=startDay().get("batchId").asLong();
        var entries=batchDetails(batch); dailyAnswer(batch,entries.get(0).getId(),1,1); dailyAnswer(batch,entries.get(1).getId(),1,1);
        String memory=stateSnapshot(),queue=batchSnapshot(batch); long logs=logCount(batch);
        probe.reads.set(0); int writes=probe.writes.get();
        statisticsDaily(statisticsDate()); assertEquals(1,probe.reads.get());
        probe.reads.set(0); get("/api/learning/statistics/calendar",tokenA); assertEquals(1,probe.reads.get());
        probe.reads.set(0); var page=get("/api/learning/statistics/words?size=1&page=2",tokenA);
        assertEquals(2,probe.reads.get()); assertEquals(2,page.get("total").asInt()); assertEquals(1,page.get("records").size());
        assertEquals(entries.get(1).getWordId(),page.get("records").get(0).get("wordId").asLong());
        probe.reads.set(0); var all=get("/api/learning/statistics/words?size=100",tokenA);
        assertEquals(2,probe.reads.get()); assertEquals(2,all.get("records").size());
        assertEquals(0,get("/api/learning/statistics/words?page=2147483647&size=100",tokenA).get("records").size());
        assertEquals(writes,probe.writes.get());
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(logs,logCount(batch));
    }

    @Test void statisticsAreUserScopedAndRejectInvalidDatesMonthsAndPagination() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); dailyAnswer(batch,itemId(batch),1,1);
        for(String path:List.of("/daily","/calendar","/words")) {
            assertEquals(401,call("GET","/api/learning/statistics"+path,null,null).statusCode());
        }
        assertEquals(0,get("/api/learning/statistics/daily",tokenB).get("answerCount").asInt());
        assertEquals(0,get("/api/learning/statistics/words",tokenB).get("total").asInt());
        for(var day:get("/api/learning/statistics/calendar",tokenB).get("days")) assertFalse(day.get("hasLearningActivity").asBoolean());
        assertEquals(statisticsDaily(statisticsDate()),get("/api/learning/statistics/daily?userId="+b,tokenA));
        for(String date:List.of("","2025-02-29","2026-02-30","2026-13-01","2026-00-01","2026-1-01","0000-01-01","0999-12-31","10000-01-01","null")) {
            for(String endpoint:List.of("daily","words")) {
                var response=call("GET","/api/learning/statistics/"+endpoint+"?date="+date,null,tokenA);
                assertEquals(400,response.statusCode(),response.body()+" date="+date);
                assertEquals("INVALID_PARAMETER",json.readTree(response.body()).get("code").asText());
            }
        }
        for(String month:List.of("","2026-00","2026-13","2026-2","2026-02-01","0000-01","10000-01","null")) {
            assertEquals(400,call("GET","/api/learning/statistics/calendar?month="+month,null,tokenA).statusCode(),month);
        }
        for(String query:List.of("page=0","size=101","size=0","page=text")) {
            assertEquals(400,call("GET","/api/learning/statistics/words?"+query,null,tokenA).statusCode());
        }
    }

    @Test void statisticsKeepEffectiveFeedbackWhenCorrectionEventIsMissing() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        long answerLog=dailyAnswer(batch,item,1,1).get("logId").asLong();
        long correctionLog=correction(batch,item,answerLog,3,requestId()).get("logId").asLong();
        reviewLogs.deleteById(correctionLog); // Simulate missing historical audit data for this fixture only.
        var result=statisticsDaily(statisticsDate());
        assertStatistics(result,0,1,0,0,1);
        assertFalse(result.get("statisticsComplete").asBoolean());
        assertEquals(1,result.get("incompleteWordCount").asInt());
        var word=get("/api/learning/statistics/words",tokenA).get("records").get(0);
        assertEquals(3,word.get("lastResult").asInt());
        assertFalse(word.get("completed").asBoolean());
        assertFalse(word.get("statisticsComplete").asBoolean());
    }

    @Test void statisticsSeparateUsersLearningTheSameWordWithTheSameRequestKey() throws Exception {
        String key=requestId(); joinPlan(wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        dailyAnswer(batch,itemId(batch),1,1,key);
        assertEquals(200,call("POST","/api/learning-plan/words",
            Map.of("bookId",systemBook,"wordIds",List.of(wordIds.get(2))),tokenB).statusCode());
        var start=call("POST","/api/learning/today/start",null,tokenB); assertEquals(200,start.statusCode(),start.body());
        long otherBatch=json.readTree(start.body()).at("/data/batchId").asLong(),otherItem=itemId(otherBatch);
        var body=Map.of("result",3,"attemptNo",1,"requestId",key);
        assertEquals(200,call("PUT",dailyItem(otherBatch,otherItem)+"/answer",body,tokenB).statusCode());
        assertEquals(200,call("PUT",dailyItem(otherBatch,otherItem)+"/answer",body,tokenB).statusCode());
        assertStatistics(statisticsDaily(statisticsDate()),1,1,1,0,0);
        assertStatistics(get("/api/learning/statistics/daily",tokenB),0,1,0,0,1);
        var otherWords=get("/api/learning/statistics/words",tokenB);
        assertEquals(1,otherWords.get("total").asInt());
        assertEquals(3,otherWords.get("records").get(0).get("lastResult").asInt());
        assertEquals(1,get("/api/learning/statistics/words",tokenA).get("records").get(0).get("lastResult").asInt());
    }

    private JsonNode updateSettings(int target) throws Exception {
        var response=call("PUT","/api/learning/settings",Map.of("dailyTarget",target),tokenA);
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).get("data");
    }

    private long settingsCount(long userId) {
        return learningSettings.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(userId)));
    }

    @Test void settingsDefaultTo20WithoutPersistingOnReadsOrTaskCreation() throws Exception {
        assertEquals(0,settingsCount(a)); int writes=probe.writes.get();
        assertEquals(20,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        assertEquals(20,get("/api/learning/today",tokenA).get("dailyTarget").asInt());
        assertEquals(writes,probe.writes.get());
        joinPlan(wordIds.get(1),wordIds.get(2)); var task=startDay();
        assertEquals(20,task.get("dailyTarget").asInt());
        assertEquals(20,batches.selectOneById(task.get("batchId").asLong()).getDailyTarget());
        assertEquals(2,task.get("totalCount").asInt()); assertEquals(0,settingsCount(a));
    }

    @Test void settingsUpsertOneUserRowAndPreserveIdentityAndIdempotentTimestamps() throws Exception {
        assertEquals(1,updateSettings(1).get("dailyTarget").asInt());
        var first=learningSettings.selectOneById(a); assertEquals(a,first.getUserId());
        clock.advanceSeconds(10); assertEquals(200,updateSettings(200).get("dailyTarget").asInt());
        var changed=learningSettings.selectOneById(a);
        assertEquals(first.getCreatedAt(),changed.getCreatedAt());
        assertTrue(changed.getUpdatedAt().isAfter(first.getUpdatedAt()));
        clock.advanceSeconds(10); updateSettings(200);
        assertEquals(changed.getUpdatedAt(),learningSettings.selectOneById(a).getUpdatedAt());
        assertEquals(1,settingsCount(a));
        assertEquals(20,get("/api/learning/settings",tokenB).get("dailyTarget").asInt());
        assertEquals(0,settingsCount(b));
        assertEquals(200,get("/api/learning/settings?user_id="+b,tokenA).get("dailyTarget").asInt());
        assertEquals(200,get("/api/learning/today",tokenA).get("dailyTarget").asInt());
        var duplicate=new UserLearningSetting(); duplicate.setUserId(a); duplicate.setDailyTarget(20);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->learningSettings.insertSelective(duplicate));
    }

    @Test void settingsRequireLoginAndStrictIntegerBodyWithoutClientIdentity() throws Exception {
        assertEquals(401,call("GET","/api/learning/settings",null,null).statusCode());
        assertEquals(401,call("PUT","/api/learning/settings",Map.of("dailyTarget",20),null).statusCode());
        for(String body:List.of("{}","{\"dailyTarget\":null}","{\"dailyTarget\":0}","{\"dailyTarget\":-1}",
                "{\"dailyTarget\":201}","{\"dailyTarget\":20.0}","{\"dailyTarget\":1.5}","{\"dailyTarget\":2e1}",
                "{\"dailyTarget\":\"20\"}","{\"dailyTarget\":true}","{\"dailyTarget\":[]}","{\"dailyTarget\":{}}",
                "{\"dailyTarget\":2147483648}","{\"dailyTarget\":20,\"user_id\":1}","{\"dailyTarget\":20,\"userId\":1}")) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"
                +environment.getRequiredProperty("local.server.port")+"/api/learning/settings"))
                .header("Authorization","Bearer "+tokenA).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(400,response.statusCode(),response.body()+" request="+body);
            assertEquals("INVALID_PARAMETER",json.readTree(response.body()).get("code").asText());
        }
        assertEquals(0,settingsCount(a)); assertEquals(20,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
    }

    @Test void settingsChangesLeaveEvenUnansweredTaskFrozenAndApplyToTheNextDay() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        updateSettings(2); var task=startDay(); long batch=task.get("batchId").asLong();
        String queue=batchSnapshot(batch),memory=stateSnapshot(); long logs=logCount(batch);
        for(int target:List.of(5,1,4)) {
            updateSettings(target);
            assertEquals(target,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
            assertEquals(2,get("/api/learning/today",tokenA).get("dailyTarget").asInt());
            assertEquals(task,startDay());
            assertEquals(queue,batchSnapshot(batch)); assertEquals(memory,stateSnapshot()); assertEquals(logs,logCount(batch));
        }
        advanceDays(1); assertEquals(4,get("/api/learning/today",tokenA).get("dailyTarget").asInt());
        var next=startDay(); assertNotEquals(batch,next.get("batchId").asLong());
        assertEquals(4,next.get("dailyTarget").asInt()); assertEquals(4,next.get("totalCount").asInt());
        assertEquals(queue,batchSnapshot(batch)); assertEquals(2,batches.selectOneById(batch).getDailyTarget());
    }

    @Test void settingsNeverResetReinforcementOrCompletedTasksAndExtraAnswersDoNotConsumeTarget() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); updateSettings(1);
        long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        dailyAnswer(batch,item,3,1); var saved=get(dailyPath(batch),tokenA); String queue=batchSnapshot(batch);
        updateSettings(200); assertEquals(saved,startDay()); assertEquals(queue,batchSnapshot(batch));
        dailyAnswer(batch,item,3,2); dailyAnswer(batch,item,1,3); dailyAnswer(batch,item,1,4); dailyAnswer(batch,item,1,5);
        var complete=get(dailyPath(batch),tokenA);
        assertEquals("COMPLETED",complete.get("status").asText());
        assertEquals(1,complete.get("dailyTarget").asInt()); assertEquals(1,complete.get("totalCount").asInt());
        assertEquals(5,complete.get("answerCount").asInt());
        updateSettings(2); assertEquals(complete,get(dailyPath(batch),tokenA));
        var continued=startDay(); assertEquals(batch,continued.get("batchId").asLong());
        assertEquals(1,continued.get("dailyTarget").asInt()); assertEquals(2,batchDetails(batch).size());
        advanceDays(1); var next=startDay();
        assertEquals(2,next.get("dailyTarget").asInt()); assertEquals(2,next.get("totalCount").asInt());
        assertEquals(1,next.get("reviewCount").asInt()); assertEquals(1,next.get("newCount").asInt());
        assertEquals(batchDetails(batch).getFirst().getWordId(),batchDetails(next.get("batchId").asLong()).getFirst().getWordId());
    }

    @Test void settingsSupport200DistinctWordsAndRetainOverdueReviewPriority() throws Exception {
        var extras=new ArrayList<Long>();
        for(int i=0;i<205;i++) { long id=word(prefix+"_setting_extra_"+i); associate(systemBook,id,100+i); extras.add(id); }
        joinPlan(extras.subList(0,100).toArray(Long[]::new));
        joinPlan(extras.subList(100,200).toArray(Long[]::new));
        joinPlan(extras.subList(200,205).toArray(Long[]::new));
        for(int i=200;i<205;i++) {
            var state=state(a,extras.get(i)); state.setFirstLearnedAt(statisticsDate().minusDays(100).atStartOfDay());
            state.setNextReviewAt(statisticsDate().minusDays(i-199).atStartOfDay()); states.update(state);
        }
        updateSettings(200); String memory=stateSnapshot(); var task=startDay();
        assertEquals(200,task.get("dailyTarget").asInt()); assertEquals(200,task.get("totalCount").asInt());
        assertEquals(5,task.get("reviewCount").asInt()); assertEquals(195,task.get("newCount").asInt());
        var entries=batchDetails(task.get("batchId").asLong());
        assertEquals(200,entries.stream().map(LearningBatchItem::getWordId).distinct().count());
        assertEquals(extras.getLast(),entries.getFirst().getWordId());
        assertEquals(memory,stateSnapshot());
    }

    @Test void settingsPersistAfterLogoutAndRelogin() throws Exception {
        updateSettings(47);
        var account=users.selectOneById(a); account.setPasswordHash(passwords.hash("password123")); users.update(account);
        var credentials=Map.of("username",account.getUsername(),"password","password123");
        var login=call("POST","/api/auth/login",credentials,null); assertEquals(200,login.statusCode());
        var pair=json.readTree(login.body()).get("data"); tokenA=pair.get("accessToken").asText();
        assertEquals(47,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        assertEquals(200,call("POST","/api/auth/logout",Map.of("refreshToken",pair.get("refreshToken").asText()),null).statusCode());
        var relogin=call("POST","/api/auth/login",credentials,null); assertEquals(200,relogin.statusCode());
        tokenA=json.readTree(relogin.body()).at("/data/accessToken").asText();
        assertEquals(47,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        assertEquals(47,learningSettings.selectOneById(a).getDailyTarget()); assertEquals(1,settingsCount(a));
    }

    @Test void settingsConcurrentFirstUpdatesCreateOneRowAndTaskCreationUsesOneSnapshot() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{gate.await();return call("PUT","/api/learning/settings",Map.of("dailyTarget",1),tokenA);});
            var second=pool.submit(()->{gate.await();return call("PUT","/api/learning/settings",Map.of("dailyTarget",200),tokenA);});
            gate.countDown(); assertEquals(200,first.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals(200,second.get(15,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(1,settingsCount(a));
        assertTrue(Set.of(1,200).contains(get("/api/learning/settings",tokenA).get("dailyTarget").asInt()));
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5)); updateSettings(2);
        var race=new CountDownLatch(1); JsonNode created;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var setting=pool.submit(()->{race.await();return call("PUT","/api/learning/settings",Map.of("dailyTarget",4),tokenA);});
            var start=pool.submit(()->{race.await();return call("POST","/api/learning/today/start",null,tokenA);});
            race.countDown(); assertEquals(200,setting.get(15,TimeUnit.SECONDS).statusCode());
            var response=start.get(15,TimeUnit.SECONDS); assertEquals(200,response.statusCode(),response.body());
            created=json.readTree(response.body()).get("data");
        }
        int target=created.get("dailyTarget").asInt(); assertTrue(Set.of(2,4).contains(target));
        assertEquals(target,created.get("totalCount").asInt());
        assertEquals(4,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        assertEquals(created,startDay()); assertEquals(1,settingsCount(a));
    }

    @Test void settingsWriteFailuresRollBackBothInsertAndUpdate() throws Exception {
        probe.failAfterMapper="com.wordtrail.learning.mapper.UserLearningSettingMapper";
        try { assertEquals(500,call("PUT","/api/learning/settings",Map.of("dailyTarget",30),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; }
        assertEquals(0,settingsCount(a)); assertEquals(20,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        updateSettings(30); var before=json.valueToTree(learningSettings.selectOneById(a)); clock.advanceSeconds(10);
        probe.failAfterMapper="com.wordtrail.learning.mapper.UserLearningSettingMapper";
        try { assertEquals(500,call("PUT","/api/learning/settings",Map.of("dailyTarget",100),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; }
        assertEquals(before,json.valueToTree(learningSettings.selectOneById(a)));
    }

    private JsonNode planManagementStatus(String target,Long... ids) throws Exception {
        var response=call("PATCH","/api/learning-plan/words/status",Map.of("status",target,"wordIds",List.of(ids)),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }

    private List<Long> planWordIds(JsonNode page) {
        var ids=new ArrayList<Long>(); page.get("records").forEach(row->ids.add(row.get("wordId").asLong())); return ids;
    }

    @Test void planManagementSearchEscapesWildcardsAndSortsStablyWithNullDueDatesLast() throws Exception {
        joinPlan(wordIds.subList(0,7).toArray(Long[]::new));
        assertEquals(2,get("/api/learning-plan/words?keyword="+encoded(prefix+"a"),tokenA).get("total").asInt());
        for(int i=3;i<=6;i++) {
            String character=List.of("%","_","!","\\").get(i-3);
            assertEquals(List.of(wordIds.get(i)),planWordIds(get("/api/learning-plan/words?keyword="+encoded(prefix+character),tokenA)));
        }
        assertEquals(0,get("/api/learning-plan/words?keyword="+encoded(prefix+"%' OR 1=1 --"),tokenA).get("total").asInt());
        planManagementStatus("PAUSED",wordIds.get(3)); planManagementStatus("REMOVED",wordIds.get(4));
        assertEquals(List.of(wordIds.get(3)),planWordIds(get("/api/learning-plan/words?status=PAUSED",tokenA)));
        assertEquals(List.of(wordIds.get(4)),planWordIds(get("/api/learning-plan/words?status=REMOVED",tokenA)));
        var base=LocalDateTime.of(2025,1,1,0,0);
        var selected=List.of(wordIds.get(1),wordIds.get(2),wordIds.get(5),wordIds.get(6));
        for(int i=0;i<4;i++) {
            var state=state(a,selected.get(i)); state.setJoinedAt(base.plusDays(i<2?0:i));
            state.setNextReviewAt(i==0?null:base.plusDays(i==1?2:1));
            states.updateByQuery(state,false,QueryWrapper.create().where(column("id").eq(state.getId())));
        }
        String query="/api/learning-plan/words?learningStatus=NEW";
        assertEquals(List.of(selected.get(3),selected.get(2),selected.get(1),selected.get(0)),planWordIds(get(query,tokenA)));
        assertEquals(selected,planWordIds(get(query+"&sortBy=joined_at&direction=ASC",tokenA)));
        assertEquals(List.of(selected.get(2),selected.get(3),selected.get(1),selected.get(0)),
            planWordIds(get(query+"&sortBy=next_review_at&direction=ASC",tokenA)));
        assertEquals(List.of(selected.get(1),selected.get(3),selected.get(2),selected.get(0)),
            planWordIds(get(query+"&sortBy=next_review_at&direction=DESC",tokenA)));
        var one=planWordIds(get(query+"&sortBy=next_review_at&direction=ASC&size=2",tokenA));
        var two=planWordIds(get(query+"&sortBy=next_review_at&direction=ASC&size=2&page=2",tokenA));
        assertEquals(List.of(selected.get(2),selected.get(3)),one); assertEquals(List.of(selected.get(1),selected.get(0)),two);
        assertEquals(one,planWordIds(get(query+"&sortBy=next_review_at&direction=ASC&size=2",tokenA)));
        for(String invalid:List.of("sortBy=word","sortBy="+encoded("next_review_at DESC"),"direction=DOWN","direction=desc","keyword="+"a".repeat(256))) {
            assertEquals(400,call("GET","/api/learning-plan/words?"+invalid,null,tokenA).statusCode());
        }
    }

    @Test void planManagementBatchTransitionsAreAtomicIdempotentAndUserScoped() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3));
        var first=planManagementStatus("PAUSED",wordIds.get(1),wordIds.get(2),wordIds.get(1));
        assertEquals(2,first.get("requestedCount").asInt()); assertEquals(2,first.get("changedCount").asInt());
        String snapshot=stateSnapshot(); clock.advanceSeconds(10);
        var retry=planManagementStatus("PAUSED",wordIds.get(1),wordIds.get(2));
        assertEquals(0,retry.get("changedCount").asInt()); assertEquals(2,retry.get("unchangedCount").asInt());
        assertEquals(snapshot,stateSnapshot());
        planManagementStatus("ACTIVE",wordIds.get(1));
        planManagementStatus("REMOVED",wordIds.get(1),wordIds.get(2));
        planManagementStatus("PAUSED",wordIds.get(3)); snapshot=stateSnapshot();
        assertEquals(409,call("PATCH","/api/learning-plan/words/status",
            Map.of("status","ACTIVE","wordIds",List.of(wordIds.get(1),wordIds.get(3))),tokenA).statusCode());
        assertEquals(snapshot,stateSnapshot());
        assertEquals(404,call("PATCH","/api/learning-plan/words/status",
            Map.of("status","REMOVED","wordIds",List.of(wordIds.get(3),wordIds.get(7))),tokenA).statusCode());
        assertEquals(404,call("PATCH","/api/learning-plan/words/status",
            Map.of("status","REMOVED","wordIds",List.of(wordIds.get(2))),tokenB).statusCode());
        assertEquals(snapshot,stateSnapshot());
        assertEquals(409,call("PATCH","/api/learning-plan/words/"+wordIds.get(1),Map.of("status","ACTIVE"),tokenA).statusCode());
        assertEquals(409,call("PATCH","/api/learning-plan/words/status",
            Map.of("status","PAUSED","wordIds",List.of(wordIds.get(1))),tokenA).statusCode());
    }

    @Test void planManagementBatchValidatesPayloadAndSupports100DistinctWords() throws Exception {
        var extras=new ArrayList<Long>();
        for(int i=0;i<100;i++) { long id=word(prefix+"_bulk_"+i); associate(systemBook,id,100+i); extras.add(id); }
        joinPlan(extras.toArray(Long[]::new));
        var repeated=new ArrayList<>(extras); repeated.addAll(extras);
        var response=call("PATCH","/api/learning-plan/words/status",Map.of("status","PAUSED","wordIds",repeated),tokenA);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals(100,json.readTree(response.body()).at("/data/changedCount").asInt());
        assertEquals(100,get("/api/learning-plan/words?status=PAUSED",tokenA).get("total").asInt());
        for(Object body:List.of(Map.of("status","ACTIVE","wordIds",List.of()),Map.of("wordIds",List.of(wordIds.get(1))),
                Map.of("status","INVALID","wordIds",List.of(wordIds.get(1))),Map.of("status","ACTIVE","wordIds",List.of(0)),
                Map.of("status","ACTIVE","wordIds",java.util.stream.LongStream.rangeClosed(1,101).boxed().toList()),
                Map.of("status","ACTIVE","wordIds",List.of(wordIds.get(1)),"user_id",b),
                json.readTree("{\"status\":\"ACTIVE\",\"wordIds\":[null]}"))) {
            assertEquals(400,call("PATCH","/api/learning-plan/words/status",body,tokenA).statusCode());
        }
        assertEquals(401,call("PATCH","/api/learning-plan/words/status",Map.of("status","PAUSED","wordIds",extras),null).statusCode());
    }

    @Test void planManagementResumePreservesMemoryAndKeepsOverdueReviewDates() throws Exception {
        joinPlan(wordIds.get(1)); var state=state(a,wordIds.get(1));
        state.setFirstLearnedAt(statisticsDate().minusDays(100).atStartOfDay());
        state.setLastLearnedAt(statisticsDate().minusDays(10).atStartOfDay());
        state.setNextReviewAt(statisticsDate().minusDays(5).atStartOfDay()); state.setLastResult(2); state.setReviewLevel(2); states.update(state);
        var original=json.valueToTree(state(a,wordIds.get(1)));
        planManagementStatus("PAUSED",wordIds.get(1)); assertEquals(0,get("/api/learning/today",tokenA).get("dueCount").asInt());
        planManagementStatus("ACTIVE",wordIds.get(1));
        var after=json.valueToTree(state(a,wordIds.get(1)));
        for(String field:List.of("joinedAt","firstLearnedAt","lastLearnedAt","lastResult","lastBatchItemId","reviewLevel","nextReviewAt")) {
            assertEquals(original.get(field),after.get(field),field);
        }
        assertEquals(1,get("/api/learning/today",tokenA).get("dueCount").asInt());
        var task=startDay(); assertEquals(1,task.get("reviewCount").asInt());
        assertEquals(original.get("nextReviewAt"),json.valueToTree(state(a,wordIds.get(1))).get("nextReviewAt"));
    }

    @Test void planManagementSkipAndResumePendingAndRelearningWordsWithoutResettingQueue() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3)); long batch=startDay().get("batchId").asLong();
        var entries=batchDetails(batch); long first=entries.get(0).getId(),second=entries.get(1).getId(),word=entries.get(0).getWordId();
        var original=json.valueToTree(details.selectOneById(first));
        planManagementStatus("PAUSED",word); var skipped=details.selectOneById(first);
        assertEquals("SKIPPED",skipped.getLearningStatus()); assertEquals("PENDING",skipped.getStatusBeforeSkip());
        assertNotNull(skipped.getSkippedAt());
        var progress=get(dailyPath(batch),tokenA); assertEquals(1,progress.get("skippedCount").asInt());
        assertEquals(0,progress.get("completedCount").asInt()); assertEquals(second,progress.get("nextItemId").asLong());
        String queue=batchSnapshot(batch); clock.advanceSeconds(10); planManagementStatus("PAUSED",word); assertEquals(queue,batchSnapshot(batch));
        var stale=call("PUT",dailyItem(batch,first)+"/answer",Map.of("result",1,"attemptNo",1,"requestId",requestId()),tokenA);
        assertEquals(409,stale.statusCode()); assertEquals("PLAN_WORD_UNAVAILABLE",json.readTree(stale.body()).get("code").asText());
        var detail=get(dailyItem(batch,first),tokenA); assertTrue(detail.get("nextAttemptNo").isNull()); assertTrue(detail.get("correctableLogId").isNull());
        assertEquals(0,logCount(batch)); assertNull(state(a,word).getFirstLearnedAt());
        var stats=statisticsDaily(statisticsDate()); assertStatistics(stats,0,0,0,0,0);
        assertFalse(stats.get("hasLearningActivity").asBoolean()); assertTrue(stats.get("statisticsComplete").asBoolean());
        assertEquals(0,get("/api/learning/statistics/words",tokenA).get("total").asInt());
        planManagementStatus("ACTIVE",word); assertEquals(original,json.valueToTree(details.selectOneById(first)));
        assertEquals(first,get(dailyPath(batch),tokenA).get("nextItemId").asLong());
        dailyAnswer(batch,first,3,1); var reinforcing=details.selectOneById(first); var memory=json.valueToTree(state(a,word));
        planManagementStatus("PAUSED",word); assertEquals("RELEARNING",details.selectOneById(first).getStatusBeforeSkip());
        planManagementStatus("ACTIVE",word);
        assertEquals(json.valueToTree(reinforcing),json.valueToTree(details.selectOneById(first)));
        assertEquals(memory,json.valueToTree(state(a,word))); assertEquals(1,logCount(batch));
        assertEquals(second,get(dailyPath(batch),tokenA).get("nextItemId").asLong());
    }

    @Test void planManagementSkippedCompletedWordIsNotCountedCompleteAndCanResumeWhileTaskOpen() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong(); var first=batchDetails(batch).getFirst();
        long log=dailyAnswer(batch,first.getId(),1,1).get("logId").asLong();
        var completed=details.selectOneById(first.getId());
        planManagementStatus("PAUSED",first.getWordId());
        assertEquals("SKIPPED",details.selectOneById(first.getId()).getLearningStatus());
        assertEquals(completed.getCompletedAt(),details.selectOneById(first.getId()).getCompletedAt());
        assertEquals(0,get(dailyPath(batch),tokenA).get("completedCount").asInt());
        assertStatistics(statisticsDaily(statisticsDate()),0,1,0,0,0);
        assertEquals(409,call("PUT",dailyItem(batch,first.getId())+"/correction",
            Map.of("result",3,"logId",log,"requestId",requestId()),tokenA).statusCode());
        planManagementStatus("ACTIVE",first.getWordId());
        assertEquals(json.valueToTree(completed),json.valueToTree(details.selectOneById(first.getId())));
        assertEquals(1,get(dailyPath(batch),tokenA).get("completedCount").asInt()); assertEquals(1,logCount(batch));
    }

    @Test void planManagementRemovalRejoinAndClosedTaskRestorationFollowOneRule() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch);
        long first=entries.get(0).getWordId(),second=entries.get(1).getWordId();
        planManagementStatus("REMOVED",first);
        assertEquals(entries.get(1).getId(),get(dailyPath(batch),tokenA).get("nextItemId").asLong());
        assertEquals(409,call("PATCH","/api/learning-plan/words/status",Map.of("status","ACTIVE","wordIds",List.of(first)),tokenA).statusCode());
        assertEquals(1,joinPlan(first).get("reactivatedCount").asInt());
        assertEquals(entries.get(0).getId(),get(dailyPath(batch),tokenA).get("nextItemId").asLong());
        planManagementStatus("PAUSED",first,second); var ended=get(dailyPath(batch),tokenA);
        assertEquals("COMPLETED",ended.get("status").asText()); assertEquals(2,ended.get("skippedCount").asInt());
        assertEquals(0,ended.get("completedCount").asInt()); assertTrue(ended.get("nextItemId").isNull());
        String queue=batchSnapshot(batch); planManagementStatus("ACTIVE",first,second);
        assertEquals(ended,startDay()); assertEquals(queue,batchSnapshot(batch));
        assertEquals(409,call("PUT",dailyItem(batch,entries.get(0).getId())+"/answer",
            Map.of("result",1,"attemptNo",1,"requestId",requestId()),tokenA).statusCode());
        advanceDays(1); var next=startDay(); assertNotEquals(batch,next.get("batchId").asLong()); assertEquals(2,next.get("newCount").asInt());
        assertEquals(queue,batchSnapshot(batch));
    }

    @Test void planManagementPauseFinishedTaskPreservesAnswersAndResumeDoesNotReopenIt() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); long item=itemId(batch);
        for(int attempt=1;attempt<=6;attempt++) { dailyAnswer(batch,item,3,attempt); }
        var finished=batches.selectOneById(batch).getCompletedAt(); var answerTime=details.selectOneById(item).getAnsweredAt();
        planManagementStatus("PAUSED",wordIds.get(1)); var task=get(dailyPath(batch),tokenA);
        assertEquals("COMPLETED",task.get("status").asText()); assertEquals(0,task.get("completedCount").asInt());
        assertEquals(1,task.get("skippedCount").asInt()); assertEquals(6,task.get("answerCount").asInt());
        assertEquals(finished,batches.selectOneById(batch).getCompletedAt()); assertEquals(answerTime,details.selectOneById(item).getAnsweredAt());
        assertStatistics(statisticsDaily(statisticsDate()),0,6,0,0,1);
        planManagementStatus("ACTIVE",wordIds.get(1)); assertEquals(task,startDay());
        var oldQueue=batchSnapshot(batch); advanceDays(1); planManagementStatus("PAUSED",wordIds.get(1));
        assertEquals(oldQueue,batchSnapshot(batch)); planManagementStatus("ACTIVE",wordIds.get(1));
        var next=startDay(); assertEquals(1,next.get("reviewCount").asInt()); assertEquals(oldQueue,batchSnapshot(batch));
    }

    @Test void planManagementPriorDayQueueRemainsHistoricalAndNeverReturnsAnActionableNextWord() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong(); var first=batchDetails(batch).getFirst();
        String before=batchSnapshot(batch); advanceDays(1); planManagementStatus("PAUSED",first.getWordId());
        assertEquals(before,batchSnapshot(batch));
        assertTrue(get(dailyPath(batch),tokenA).get("nextItemId").isNull());
        var item=get(dailyItem(batch,first.getId()),tokenA);
        assertTrue(item.get("nextItemId").isNull()); assertTrue(item.get("nextAttemptNo").isNull());
        assertTrue(get("/api/learning/daily-tasks",tokenA).get("records").get(0).get("nextItemId").isNull());
        long today=startDay().get("batchId").asLong();
        assertEquals(1,batchDetails(today).size()); assertNotEquals(first.getWordId(),batchDetails(today).getFirst().getWordId());
        assertEquals(before,batchSnapshot(batch)); assertEquals(0,logCount(batch));
    }

    @Test void planManagementFailuresRollBackPlanQueueAndBatchTogether() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        String memory=stateSnapshot(),queue=batchSnapshot(batch);
        for(String mapper:List.of("UserWordStateMapper","LearningBatchItemMapper","LearningBatchMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try { assertEquals(500,call("PATCH","/api/learning-plan/words/status",
                Map.of("status","PAUSED","wordIds",List.of(wordIds.get(1),wordIds.get(2))),tokenA).statusCode()); }
            finally { probe.failAfterMapper=null; }
            assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(0,logCount(batch));
        }
        planManagementStatus("PAUSED",wordIds.get(1),wordIds.get(2));
        assertEquals(2,get(dailyPath(batch),tokenA).get("skippedCount").asInt());
    }

    @Test void planManagementConcurrentPauseAndAnswerNeverScheduleAfterPause() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch); var first=entries.getFirst();
        var gate=new CountDownLatch(1); int answerCode;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pause=pool.submit(()->{gate.await();return call("PATCH","/api/learning-plan/words/status",Map.of("status","PAUSED","wordIds",List.of(first.getWordId())),tokenA);});
            var answer=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,first.getId())+"/answer",Map.of("result",1,"attemptNo",1,"requestId",requestId()),tokenA);});
            gate.countDown(); assertEquals(200,pause.get(15,TimeUnit.SECONDS).statusCode()); answerCode=answer.get(15,TimeUnit.SECONDS).statusCode();
        }
        assertTrue(Set.of(200,409).contains(answerCode)); assertEquals(answerCode==200?1:0,logCount(batch));
        assertEquals("SKIPPED",details.selectOneById(first.getId()).getLearningStatus());
        assertEquals("PAUSED",state(a,first.getWordId()).getPlanStatus());
        assertEquals(entries.get(1).getId(),get(dailyPath(batch),tokenA).get("nextItemId").asLong());
    }

    @Test void wordHistoryIncludesPracticeDailyAndCorrectionEventsWithAvailableSchedules() throws Exception {
        long word=wordIds.get(1),practice=historicalPractice(word); historicalFeedback(practice,itemId(practice),1); historicalFeedback(practice,itemId(practice),2);
        joinPlan(word); var state=state(a,word); state.setFirstLearnedAt(statisticsDate().minusDays(10).atStartOfDay());
        state.setNextReviewAt(statisticsDate().minusDays(2).atStartOfDay()); state.setReviewLevel(2); states.update(state);
        long batch=startDay().get("batchId").asLong(),item=itemId(batch); long normal=dailyAnswer(batch,item,1,1).get("logId").asLong();
        long changed=correction(batch,item,normal,3,requestId()).get("logId").asLong();
        String path="/api/learning-plan/words/"+word+"/history";
        int writes=probe.writes.get(); probe.reads.set(0);
        var page=get(path+"?size=2",tokenA); assertEquals(2,probe.reads.get()); assertEquals(4,page.get("total").asInt());
        var latest=page.get("records").get(0); assertEquals(changed,latest.get("logId").asLong());
        assertEquals("CORRECTION",latest.get("eventType").asText()); assertEquals(normal,latest.get("correctsLogId").asLong());
        assertEquals("DAILY",latest.get("taskType").asText()); assertTrue(latest.get("affectsSchedule").asBoolean());
        assertTrue(latest.get("scheduleAvailable").asBoolean());
        assertEquals(3,latest.at("/scheduleBefore/reviewLevel").asInt()); assertEquals(0,latest.at("/scheduleAfter/reviewLevel").asInt());
        assertEquals(1,page.get("records").get(1).get("result").asInt());
        var older=get(path+"?size=2&page=2",tokenA).get("records");
        assertEquals("PRACTICE",older.get(0).get("taskType").asText()); assertEquals("CORRECTION",older.get(0).get("eventType").asText());
        assertFalse(older.get(0).get("affectsSchedule").asBoolean()); assertFalse(older.get(0).get("scheduleAvailable").asBoolean());
        assertTrue(older.get(0).get("scheduleBefore").isNull()); assertEquals("ANSWER",older.get(1).get("eventType").asText());
        probe.reads.set(0); var all=get(path+"?size=100",tokenA); assertEquals(2,probe.reads.get()); assertEquals(4,all.get("records").size());
        assertEquals(writes,probe.writes.get());
        assertEquals(0,get(path,tokenB).get("total").asInt());
        assertEquals(all,get(path+"?size=100&user_id="+b,tokenA));
        assertEquals(0,get(path+"?page=2147483647",tokenA).get("records").size());
    }

    @Test void wordHistoryDoesNotFabricateLegacyEventsOrMissingSchedulesAndValidatesPagination() throws Exception {
        assertEquals(0,get("/api/learning-plan/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        long word=wordIds.get(1); joinPlan(word); long batch=startDay().get("batchId").asLong();
        long log=dailyAnswer(batch,itemId(batch),1,1).get("logId").asLong();
        var patch=new WordReviewLog(); patch.setId(log); patch.setScheduleBefore("\"legacy\""); reviewLogs.update(patch);
        String path="/api/learning-plan/words/"+word+"/history"; var row=get(path,tokenA).get("records").get(0);
        assertFalse(row.get("scheduleAvailable").asBoolean()); assertTrue(row.get("scheduleBefore").isNull());
        planManagementStatus("REMOVED",word); assertEquals(1,get(path,tokenA).get("total").asInt());
        assertEquals(0,get("/api/learning-plan/words/"+Long.MAX_VALUE+"/history",tokenA).get("total").asInt());
        for(String query:List.of("page=0","size=101","size=0","page=text")) assertEquals(400,call("GET",path+"?"+query,null,tokenA).statusCode());
        assertEquals(400,call("GET","/api/learning-plan/words/0/history",null,tokenA).statusCode());
        assertEquals(401,call("GET",path,null,null).statusCode());
    }

    @Test void intradayFirstKnownCompletesButInitiallyWeakWordsNeedSeveralRecallChecks() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        dailyAnswer(batch,item,1,1); var known=details.selectOneById(item);
        assertEquals("DONE",known.getLearningStatus()); assertEquals(0,known.getReinforcementRemaining());
        assertEquals("RECALLED",known.getCompletionReason()); assertEquals(1,known.getAnswerCount());
        for(int firstResult:List.of(2,3)) {
            advanceDays(1); batch=startDay().get("batchId").asLong(); item=itemId(batch);
            dailyAnswer(batch,item,firstResult,1);
            int required=firstResult==2?2:3; var memory=state(a,wordIds.get(1));
            assertEquals(required,details.selectOneById(item).getReinforcementRemaining());
            for(int recalled=1;recalled<=required;recalled++) {
                String key=requestId(); var answer=dailyAnswer(batch,item,1,recalled+1,key);
                assertEquals(answer,dailyAnswer(batch,item,1,recalled+1,key));
                var entry=details.selectOneById(item);
                assertEquals(required-recalled,entry.getReinforcementRemaining());
                assertEquals(recalled==required?"DONE":"RELEARNING",entry.getLearningStatus());
                assertEquals(memory.getReviewLevel(),state(a,wordIds.get(1)).getReviewLevel());
                assertEquals(memory.getNextReviewAt(),state(a,wordIds.get(1)).getNextReviewAt());
                assertEquals(required-recalled,get(dailyItem(batch,item),tokenA).get("reinforcementRemaining").asInt());
            }
            assertEquals(required+1,logCount(batch));
            assertEquals(1,reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch)).and(column("affects_schedule").eq(true))));
        }
    }

    @Test void intradayWeakFeedbackRestoresChecksAndLimitDoesNotMeanRecalled() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        int[] feedback={3,1,2,1,3,1}; int[] remaining={3,2,2,1,3,2};
        for(int i=0;i<feedback.length;i++) {
            dailyAnswer(batch,item,feedback[i],i+1);
            assertEquals(remaining[i],details.selectOneById(item).getReinforcementRemaining());
        }
        var entry=details.selectOneById(item); assertEquals("DONE",entry.getLearningStatus());
        assertEquals("LIMIT_REACHED",entry.getCompletionReason());
        assertEquals(1,get(dailyPath(batch),tokenA).get("unfamiliarCount").asInt());
        assertEquals(6,get(dailyItem(batch,item),tokenA).get("attemptLimit").asInt());
        assertTrue(get(dailyItem(batch,item),tokenA).get("nextAttemptNo").isNull());
        assertStatistics(statisticsDaily(statisticsDate()),1,6,1,0,0); // Statistics retain their last-effective-feedback definition.
        String before=batchSnapshot(batch); assertEquals(400,call("PUT",dailyItem(batch,item)+"/answer",
            Map.of("result",1,"attemptNo",7,"requestId",requestId()),tokenA).statusCode());
        assertEquals(before,batchSnapshot(batch)); assertEquals(6,logCount(batch));
        advanceDays(1); assertEquals(1,startDay().get("reviewCount").asInt());
    }

    @Test void intradaySuccessfulChecksMoveFurtherApartAndSurvivePauseAndResume() throws Exception {
        var words=new ArrayList<Long>();
        for(int i=0;i<10;i++) { long word=word(prefix+"_spacing_"+i); associate(systemBook,word,100+i); words.add(word); }
        joinPlan(words.toArray(Long[]::new)); long batch=startDay().get("batchId").asLong(); var entries=batchDetails(batch);
        long weak=entries.getFirst().getId(); dailyAnswer(batch,weak,3,1);
        for(int i=1;i<=3;i++) { dailyAnswer(batch,entries.get(i).getId(),1,1); }
        dailyAnswer(batch,weak,1,2); var saved=json.valueToTree(details.selectOneById(weak));
        planManagementStatus("PAUSED",entries.getFirst().getWordId());
        planManagementStatus("ACTIVE",entries.getFirst().getWordId());
        assertEquals(saved,json.valueToTree(details.selectOneById(weak)));
        for(int i=4;i<=8;i++) {
            assertEquals(entries.get(i).getId(),get(dailyPath(batch),tokenA).get("nextItemId").asLong());
            dailyAnswer(batch,entries.get(i).getId(),1,1);
        }
        assertEquals(weak,get(dailyPath(batch),tokenA).get("nextItemId").asLong());
        dailyAnswer(batch,weak,1,3); assertEquals(1,details.selectOneById(weak).getReinforcementRemaining());
        dailyAnswer(batch,entries.get(9).getId(),1,1); dailyAnswer(batch,weak,1,4);
        assertEquals("RECALLED",details.selectOneById(weak).getCompletionReason());
        assertStatistics(statisticsDaily(statisticsDate()),10,13,10,0,0);
    }

    @Test void intradayConcurrentKnownRetriesReduceTheRemainingChecksOnlyOnce() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        dailyAnswer(batch,item,3,1);
        var body=Map.of("result",1,"attemptNo",2,"requestId",requestId()); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",body,tokenA);});
            var two=pool.submit(()->{gate.await();return call("PUT",dailyItem(batch,item)+"/answer",body,tokenA);});
            gate.countDown(); var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode()); assertEquals(200,second.statusCode());
            assertEquals(json.readTree(first.body()),json.readTree(second.body()));
        }
        assertEquals(2,details.selectOneById(item).getReinforcementRemaining());
        assertEquals(2,details.selectOneById(item).getAnswerCount()); assertEquals(2,logCount(batch));
    }

    @Test void intradayCorrectionsRecomputeChecksFromTheOriginalAttemptWithoutDoubleCounting() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        long first=dailyAnswer(batch,item,3,1).get("logId").asLong();
        correction(batch,item,first,1,requestId()); assertEquals("DONE",details.selectOneById(item).getLearningStatus());
        correction(batch,item,first,2,requestId()); assertEquals(2,details.selectOneById(item).getReinforcementRemaining());
        long second=dailyAnswer(batch,item,1,2).get("logId").asLong();
        assertEquals(1,details.selectOneById(item).getReinforcementRemaining());
        var memory=state(a,wordIds.get(1));
        String request=requestId(); var changed=correction(batch,item,second,3,request);
        assertEquals(3,details.selectOneById(item).getReinforcementRemaining());
        assertEquals(changed,correction(batch,item,second,3,request));
        correction(batch,item,second,1,requestId()); correction(batch,item,second,1,requestId());
        assertEquals(1,details.selectOneById(item).getReinforcementRemaining());
        assertEquals(2,details.selectOneById(item).getAnswerCount());
        assertEquals(memory.getNextReviewAt(),state(a,wordIds.get(1)).getNextReviewAt());
        dailyAnswer(batch,item,1,3); assertEquals("RECALLED",details.selectOneById(item).getCompletionReason());
        assertStatistics(statisticsDaily(statisticsDate()),1,3,1,0,0);
    }

    private void finishDailyWithKnown(long batch) throws Exception {
        for(int count=0;count<1200;count++) {
            var task=get(dailyPath(batch),tokenA); if(task.get("nextItemId").isNull()) { return; }
            long item=task.get("nextItemId").asLong();
            dailyAnswer(batch,item,1,details.selectOneById(item).getAnswerCount()+1);
        }
        fail("Daily queue did not terminate");
    }

    @Test void continuedDailySevenWordsThenNewSelectionReusesBatchAndPreservesEveryOldResult() throws Exception {
        var selected=new ArrayList<Long>();
        for(int i=0;i<10;i++) { long word=word(prefix+"_continued_"+i); associate(systemBook,word,100+i); selected.add(word); }
        joinPlan(selected.subList(0,7).toArray(Long[]::new));
        long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch);
        var old=batchDetails(batch).stream().map(json::valueToTree).toList();
        var completed=get(dailyPath(batch),tokenA); assertEquals(7,completed.get("completedCount").asInt());
        assertEquals(completed,startDay());
        joinPlan(selected.subList(7,10).toArray(Long[]::new)); var today=get("/api/learning/today",tokenA);
        assertTrue(today.get("canAddMore").asBoolean()); assertEquals(3,today.get("unassignedCount").asInt());
        assertEquals("COMPLETED",today.at("/task/status").asText());
        var continued=startDay(); assertEquals(batch,continued.get("batchId").asLong());
        assertEquals("IN_PROGRESS",continued.get("status").asText()); assertEquals(10,continued.get("totalCount").asInt());
        assertEquals(7,continued.get("completedCount").asInt()); assertEquals(7,continued.get("answerCount").asInt());
        assertEquals(20,continued.get("dailyTarget").asInt()); assertNull(batches.selectOneById(batch).getCompletedAt());
        assertEquals(old,batchDetails(batch).subList(0,7).stream().map(json::valueToTree).toList());
        assertEquals(continued,startDay()); finishDailyWithKnown(batch);
        assertStatistics(statisticsDaily(statisticsDate()),10,10,10,0,0);
        assertEquals(10,get("/api/learning/statistics/words",tokenA).get("total").asInt());
        assertEquals(1,get("/api/learning/daily-tasks?status=COMPLETED",tokenA).get("total").asInt());
        assertEquals(10,batchDetails(batch).stream().map(LearningBatchItem::getWordId).distinct().count());
    }

    @Test void continuedDailyUsesOriginalTargetAndPrioritizesUnassignedOverdueReviews() throws Exception {
        updateSettings(1); joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch);
        var old=json.valueToTree(batchDetails(batch).getFirst());
        joinPlan(wordIds.get(2),wordIds.get(3)); var review=state(a,wordIds.get(3));
        review.setFirstLearnedAt(statisticsDate().minusDays(50).atStartOfDay());
        review.setNextReviewAt(statisticsDate().minusDays(5).atStartOfDay()); states.update(review);
        updateSettings(200); var continued=startDay(); assertEquals(1,continued.get("dailyTarget").asInt());
        assertEquals(2,continued.get("totalCount").asInt()); assertEquals(1,continued.get("reviewCount").asInt());
        assertEquals(wordIds.get(3),batchDetails(batch).get(1).getWordId());
        assertEquals(old,json.valueToTree(batchDetails(batch).getFirst()));
        assertEquals(continued,startDay()); assertEquals(1,get("/api/learning/today",tokenA).get("unassignedCount").asInt());
        finishDailyWithKnown(batch); var third=startDay(); assertEquals(3,third.get("totalCount").asInt());
        assertEquals(wordIds.get(2),batchDetails(batch).get(2).getWordId());
    }

    @Test void continuedDailyConcurrentRetriesAppendOnlyOneRoundAndNeverAnotherUsersWords() throws Exception {
        updateSettings(1); joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch);
        joinPlan(wordIds.get(2),wordIds.get(3)); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            var two=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            gate.countDown(); var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body()); assertEquals(200,second.statusCode(),second.body());
            assertEquals(json.readTree(first.body()),json.readTree(second.body()));
        }
        assertEquals(2,batchDetails(batch).size()); assertEquals(1,logCount(batch));
        assertEquals(409,call("POST","/api/learning/today/start",null,tokenB).statusCode());
        assertEquals(1,batches.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a)).and(column("task_type").eq("DAILY"))));
    }

    @Test void continuedDailyAppendFailureRollsBackWithoutTouchingCompletedItemsAndHistory() throws Exception {
        allocation(0,1); joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch);
        joinPlan(wordIds.get(2)); String queue=batchSnapshot(batch),memory=stateSnapshot();
        for(String mapper:List.of("LearningBatchItemMapper","LearningBatchMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try { assertEquals(500,call("POST","/api/learning/today/start",null,tokenA).statusCode()); }
            finally { probe.failAfterMapper=null; }
            assertEquals(queue,batchSnapshot(batch)); assertEquals(memory,stateSnapshot()); assertEquals(1,logCount(batch));
        }
        assertEquals(2,startDay().get("totalCount").asInt());
    }

    @Test void intradayLegacyOpenItemsUseRealEventsAndRefuseToInventMissingChecks() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(),item=itemId(batch);
        long first=dailyAnswer(batch,item,3,1).get("logId").asLong();
        var legacy=details.selectOneById(item); legacy.setReinforcementRemaining(null);
        details.updateByQuery(legacy,false,QueryWrapper.create().where(column("id").eq(item)));
        dailyAnswer(batch,item,1,2); assertEquals(2,details.selectOneById(item).getReinforcementRemaining());
        var latest=reviewLogs.selectListByQuery(QueryWrapper.create().where(column("batch_item_id").eq(item)).orderBy(column("id").desc())).getFirst();
        var missing=details.selectOneById(item); missing.setReinforcementRemaining(null);
        details.updateByQuery(missing,false,QueryWrapper.create().where(column("id").eq(item)));
        reviewLogs.deleteById(first); String queue=batchSnapshot(batch),memory=stateSnapshot();
        var failed=call("PUT",dailyItem(batch,item)+"/answer",Map.of("result",1,"attemptNo",3,"requestId",requestId()),tokenA);
        assertEquals(409,failed.statusCode()); assertEquals("REVIEW_HISTORY_INCOMPLETE",json.readTree(failed.body()).get("code").asText());
        assertEquals(queue,batchSnapshot(batch)); assertEquals(memory,stateSnapshot()); assertEquals(1,logCount(batch));
        assertEquals(latest.getId(),reviewLogs.selectOneById(latest.getId()).getId());
    }

    private long personalBook(String name, String description) throws Exception {
        var response=call("POST","/api/word-books",Map.of("name",name,"description",description),tokenA);
        assertEquals(201,response.statusCode(),response.body());
        long id=json.readTree(response.body()).at("/data/id").asLong(); bookIds.add(id); return id;
    }

    private JsonNode personalBookWords(String method,long book,Long... ids) throws Exception {
        var response=call(method,wordsPath(book),Map.of("wordIds",List.of(ids)),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }

    private String personalBookSnapshot(long book) throws Exception {
        return json.writeValueAsString(bookItems.selectListByQuery(QueryWrapper.create().where(column("book_id").eq(book))
            .orderBy(column("sort_order").asc(),column("id").asc())));
    }

    @Test void personalBooksCreateOwnedMetadataAndExposeSystemAndPersonalScopes() throws Exception {
        long id=personalBook("  "+prefix+"_vocabulary  ","  我的词本  ");
        var record=books.selectOneById(id); assertEquals(a,record.getOwnerId());
        assertEquals(prefix+"_vocabulary",record.getName()); assertEquals("我的词本",record.getDescription());
        var detail=get("/api/word-books/"+id,tokenA);
        assertEquals("PERSONAL",detail.get("bookType").asText()); assertTrue(detail.get("editable").asBoolean());
        for(String field:List.of("wordCount","unlearnedCount","startedCount","dueCount")) assertEquals(0,detail.get(field).asInt());
        var personal=get("/api/word-books?keyword="+prefix+"&scope=PERSONAL",tokenA);
        assertEquals(List.of(ownBook,emptyBook,id),ids(personal.get("records")));
        assertEquals(List.of(systemBook),ids(get("/api/word-books?keyword="+prefix+"&scope=SYSTEM",tokenA).get("records")));
        assertEquals(List.of(otherBook),ids(get("/api/word-books?keyword="+prefix+"&scope=PERSONAL",tokenB).get("records")));
        var system=get("/api/word-books/"+systemBook,tokenA);
        assertEquals("SYSTEM",system.get("bookType").asText()); assertFalse(system.get("editable").asBoolean());
        assertEquals(404,call("GET","/api/word-books/"+id,null,tokenB).statusCode());
    }

    @Test void personalBooksUpdateCanClearDescriptionAndRepeatedMetadataDoesNotChangeTimestamps() throws Exception {
        long id=personalBook(prefix+"_edit","description"); var original=books.selectOneById(id);
        clock.advanceSeconds(10);
        var response=call("PUT","/api/word-books/"+id,Map.of("name","  "+prefix+"_renamed  "),tokenA);
        assertEquals(200,response.statusCode(),response.body()); var edited=books.selectOneById(id);
        assertEquals(prefix+"_renamed",edited.getName()); assertNull(edited.getDescription());
        assertEquals(original.getOwnerId(),edited.getOwnerId()); assertEquals(original.getCreatedAt(),edited.getCreatedAt());
        assertTrue(edited.getUpdatedAt().isAfter(original.getUpdatedAt()));
        clock.advanceSeconds(10);
        assertEquals(200,call("PUT","/api/word-books/"+id,Map.of("name",prefix+"_renamed","description","  "),tokenA).statusCode());
        assertEquals(json.valueToTree(edited),json.valueToTree(books.selectOneById(id)));
        assertEquals(200,call("PUT","/api/word-books/"+id,Map.of("name","n".repeat(128),"description","d".repeat(2000)),tokenA).statusCode());
        assertEquals(2000,books.selectOneById(id).getDescription().length());
    }

    @Test void personalBooksValidateMetadataAndNeverAcceptClientOwnership() throws Exception {
        long count=books.selectCountByQuery(QueryWrapper.create());
        for(Object body:List.of(Map.of(),Map.of("name",""),Map.of("name"," \t\n "),Map.of("name","n".repeat(129)),
                Map.of("name","valid","description","d".repeat(2001)),Map.of("name","valid","owner_id",b),
                Map.of("name","valid","ownerId",b),Map.of("name","valid","user_id",b),Map.of("name","valid","id",otherBook),
                json.readTree("{\"name\":null}"))) {
            assertEquals(400,call("POST","/api/word-books",body,tokenA).statusCode(),body.toString());
        }
        assertEquals(count,books.selectCountByQuery(QueryWrapper.create()));
        assertEquals(401,call("POST","/api/word-books",Map.of("name","valid"),null).statusCode());
        assertEquals(401,call("PUT","/api/word-books/"+ownBook,Map.of("name","valid"),null).statusCode());
        assertEquals(400,call("PUT","/api/word-books/0",Map.of("name","valid"),tokenA).statusCode());
        for(String query:List.of("scope=USER","scope=personal","keyword="+"n".repeat(129))) {
            assertEquals(400,call("GET","/api/word-books?"+query,null,tokenA).statusCode());
        }
    }

    @Test void personalBooksSystemBooksAreReadOnlyAndOtherUsersAreIndistinguishableFromMissing() throws Exception {
        var system=json.valueToTree(books.selectOneById(systemBook)); String associations=personalBookSnapshot(systemBook);
        for(String method:List.of("POST","DELETE")) {
            var body=Map.of("wordIds",List.of(wordIds.get(1)));
            var rejected=call(method,wordsPath(systemBook),body,tokenA);
            assertEquals(403,rejected.statusCode()); assertEquals("BOOK_READ_ONLY",json.readTree(rejected.body()).get("code").asText());
            var forbidden=call(method,wordsPath(otherBook),body,tokenA);
            var missing=call(method,wordsPath(Long.MAX_VALUE),body,tokenA);
            assertEquals(404,forbidden.statusCode()); assertEquals(missing.body(),forbidden.body());
        }
        assertEquals(403,call("PUT","/api/word-books/"+systemBook,Map.of("name","changed"),tokenA).statusCode());
        assertEquals(403,call("DELETE",wordsPath(systemBook)+"/"+wordIds.get(1),null,tokenA).statusCode());
        var forbidden=call("PUT","/api/word-books/"+otherBook,Map.of("name","changed"),tokenA);
        assertEquals(404,forbidden.statusCode());
        assertEquals(forbidden.body(),call("PUT","/api/word-books/"+Long.MAX_VALUE,Map.of("name","changed"),tokenA).body());
        assertEquals(system,json.valueToTree(books.selectOneById(systemBook))); assertEquals(associations,personalBookSnapshot(systemBook));
        assertEquals(404,call("GET","/api/word-books/"+otherBook,null,tokenA).statusCode());
    }

    @Test void dictionarySearchUsesLiteralPrefixExactMatchingAndStableIdPagination() throws Exception {
        String query="/api/words?keyword=";
        var page=get(query+encoded(prefix+"ap"),tokenA); assertEquals(List.of(wordIds.get(0),wordIds.get(1)),ids(page.get("records")));
        assertTrue(page.get("records").get(0).get("inBook").isNull());
        for(int i=3;i<=6;i++) {
            String literal=List.of("%","_","!","\\").get(i-3);
            assertEquals(List.of(wordIds.get(i)),ids(get(query+encoded(prefix+literal),tokenA).get("records")));
        }
        assertEquals(0,get(query+encoded(prefix+"%' OR 1=1 --"),tokenA).get("total").asInt());
        var missing=call("GET",query+encoded(prefix+"_not_in_dictionary"),null,tokenA);
        assertEquals(200,missing.statusCode()); assertEquals("OK",json.readTree(missing.body()).get("code").asText());
        assertTrue(json.readTree(missing.body()).get("message").asText().contains("词库中未找到"));
        assertEquals(0,get(query+encoded(prefix+"app")+"&matchType=EXACT",tokenA).get("total").asInt());
        long duplicate=word(prefix+"apple");
        String exact=query+encoded(prefix+"apple")+"&matchType=EXACT&size=1";
        assertEquals(List.of(wordIds.get(0)),ids(get(exact,tokenA).get("records")));
        assertEquals(List.of(duplicate),ids(get(exact+"&page=2",tokenA).get("records")));
        assertEquals(2,get(exact,tokenA).get("total").asInt());
        assertEquals(0,get(exact+"&page=2147483647",tokenA).get("records").size());
        long upper=word(prefix+"Apple");
        assertEquals(List.of(upper),ids(get(query+encoded(prefix+"App"),tokenA).get("records")));
    }

    @Test void dictionarySearchValidatesParametersAndChecksTargetBookAccess() throws Exception {
        assertEquals(401,call("GET","/api/words?keyword=app",null,null).statusCode());
        for(String query:List.of("","keyword=","keyword="+encoded("  "),"keyword="+"a".repeat(256),
                "keyword=app&page=0","keyword=app&size=101","keyword=app&size=0","keyword=app&bookId=0",
                "keyword=app&bookId=text","keyword=app&matchType=ANY")) {
            assertEquals(400,call("GET","/api/words?"+query,null,tokenA).statusCode(),query);
        }
        String query="/api/words?keyword="+prefix+"&bookId=";
        var missing=call("GET",query+Long.MAX_VALUE,null,tokenA);
        var forbidden=call("GET",query+otherBook,null,tokenA);
        assertEquals(404,forbidden.statusCode()); assertEquals(missing.body(),forbidden.body());
        assertEquals(200,call("GET",query+systemBook,null,tokenA).statusCode());
    }

    @Test void dictionarySearchShowsTargetMembershipWithoutPerWordQueriesOrLearningWrites() throws Exception {
        String query="/api/words?keyword="+encoded(prefix+"ap")+"&bookId="+emptyBook;
        var empty=get(query,tokenA); assertFalse(empty.get("records").get(1).get("inBook").asBoolean());
        personalBookWords("POST",emptyBook,wordIds.get(1));
        var collected=get(query,tokenA); assertFalse(collected.get("records").get(0).get("inBook").asBoolean());
        assertTrue(collected.get("records").get(1).get("inBook").asBoolean());
        int writes=probe.writes.get(); probe.reads.set(0);
        get("/api/words?keyword="+prefix+"&size=1",tokenA); int one=probe.reads.get();
        probe.reads.set(0); get("/api/words?keyword="+prefix+"&size=100",tokenA);
        assertEquals(2,one); assertEquals(one,probe.reads.get()); assertEquals(writes,probe.writes.get());
        personalBookWords("DELETE",emptyBook,wordIds.get(1));
        assertFalse(get(query,tokenA).get("records").get(1).get("inBook").asBoolean());
    }

    @Test void personalBooksCollectIdempotentlyPreserveOrderAndDoNotAutomaticallyJoinLearningPlans() throws Exception {
        var original=bookItems.selectOneByQuery(QueryWrapper.create().where(column("book_id").eq(ownBook)));
        String memory=stateSnapshot(); long batchCount=countBatches();
        var first=personalBookWords("POST",ownBook,wordIds.get(1),wordIds.get(2),wordIds.get(0),wordIds.get(1));
        assertEquals(3,first.get("requestedCount").asInt()); assertEquals(2,first.get("changedCount").asInt());
        assertEquals(1,first.get("unchangedCount").asInt());
        assertEquals(List.of(wordIds.get(2),wordIds.get(1),wordIds.get(0)),ids(get(wordsPath(ownBook),tokenA).get("records")));
        assertEquals(json.valueToTree(original),json.valueToTree(bookItems.selectOneById(original.getId())));
        String before=personalBookSnapshot(ownBook); var metadata=json.valueToTree(books.selectOneById(ownBook)); clock.advanceSeconds(10);
        var retry=personalBookWords("POST",ownBook,wordIds.get(0),wordIds.get(1));
        assertEquals(0,retry.get("changedCount").asInt()); assertEquals(2,retry.get("unchangedCount").asInt());
        assertEquals(before,personalBookSnapshot(ownBook)); assertEquals(metadata,json.valueToTree(books.selectOneById(ownBook)));
        assertEquals(memory,stateSnapshot()); assertEquals(batchCount,countBatches());
        assertEquals(3,get("/api/word-books/"+ownBook,tokenA).get("wordCount").asInt());
    }

    @Test void personalBooksInvalidWordsRejectWholeAddsAndRemovalsAndAbsentMembershipIsIdempotent() throws Exception {
        String before=personalBookSnapshot(ownBook); var metadata=json.valueToTree(books.selectOneById(ownBook));
        for(String method:List.of("POST","DELETE")) {
            var failed=call(method,wordsPath(ownBook),Map.of("wordIds",List.of(wordIds.get(2),Long.MAX_VALUE)),tokenA);
            assertEquals(404,failed.statusCode()); assertEquals("WORD_NOT_FOUND",json.readTree(failed.body()).get("code").asText());
            assertEquals(before,personalBookSnapshot(ownBook)); assertEquals(metadata,json.valueToTree(books.selectOneById(ownBook)));
        }
        assertEquals(0,personalBookWords("DELETE",ownBook,wordIds.get(1)).get("changedCount").asInt());
        assertEquals(metadata,json.valueToTree(books.selectOneById(ownBook)));
        assertEquals(1,personalBookWords("DELETE",ownBook,wordIds.get(2),wordIds.get(2)).get("changedCount").asInt());
        var repeated=call("DELETE",wordsPath(ownBook)+"/"+wordIds.get(2),null,tokenA);
        assertEquals(200,repeated.statusCode()); assertEquals(0,json.readTree(repeated.body()).at("/data/changedCount").asInt());
        assertEquals(0,get("/api/word-books/"+ownBook,tokenA).get("wordCount").asInt());
        assertNotNull(words.selectOneById(wordIds.get(2)));
    }

    @Test void personalBooksBatchValidationSupports100DistinctWordsAndRejectsSpoofedIdentity() throws Exception {
        var extras=new ArrayList<Long>();
        for(int i=0;i<100;i++) extras.add(word(prefix+"_collection_"+i));
        var repeated=new ArrayList<>(extras); repeated.addAll(extras);
        var added=personalBookWords("POST",emptyBook,repeated.toArray(Long[]::new));
        assertEquals(100,added.get("requestedCount").asInt()); assertEquals(100,added.get("changedCount").asInt());
        var tooMany=new ArrayList<>(extras); tooMany.add(wordIds.get(7));
        for(String method:List.of("POST","DELETE")) {
            for(Object body:List.of(Map.of(),Map.of("wordIds",List.of()),Map.of("wordIds",List.of(0)),
                    Map.of("wordIds",tooMany),Map.of("wordIds",List.of(wordIds.get(1)),"user_id",b),
                    json.readTree("{\"wordIds\":[null]}"),json.readTree("{\"wordIds\":[1.5]}"))) {
                assertEquals(400,call(method,wordsPath(emptyBook),body,tokenA).statusCode(),body.toString());
            }
            assertEquals(401,call(method,wordsPath(emptyBook),Map.of("wordIds",extras),null).statusCode());
        }
        assertEquals(100,get("/api/word-books/"+emptyBook,tokenA).get("wordCount").asInt());
        assertEquals(100,personalBookWords("DELETE",emptyBook,extras.toArray(Long[]::new)).get("changedCount").asInt());
    }

    @Test void personalBooksRemovalKeepsWordsPlansDailyQueuePracticeAndEventHistory() throws Exception {
        long word=word(prefix+"_only_personal"); personalBookWords("POST",emptyBook,word);
        var joined=call("POST","/api/learning-plan/words",Map.of("bookId",emptyBook,"wordIds",List.of(word)),tokenA);
        assertEquals(200,joined.statusCode()); long daily=startDay().get("batchId").asLong(); long item=itemId(daily);
        String queue=batchSnapshot(daily),memory=stateSnapshot();
        assertEquals(1,personalBookWords("DELETE",emptyBook,word).get("changedCount").asInt());
        assertEquals(queue,batchSnapshot(daily)); assertEquals(memory,stateSnapshot()); assertNotNull(words.selectOneById(word));
        personalBookWords("POST",emptyBook,word);
        dailyAnswer(daily,item,1,1);
        long practice=historicalPractice(word);
        historicalFeedback(practice,itemId(practice),3);
        long unfinished=historicalPractice(word);
        String unfinishedBefore=batchSnapshot(unfinished);
        queue=batchSnapshot(daily); memory=stateSnapshot(); String practiceBefore=batchSnapshot(practice);
        var history=get("/api/learning-plan/words/"+word+"/history",tokenA);
        personalBookWords("DELETE",emptyBook,word);
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(daily)); assertEquals(practiceBefore,batchSnapshot(practice));
        assertEquals(history,get("/api/learning-plan/words/"+word+"/history",tokenA));
        assertEquals(2,history.get("total").asInt());
        assertEquals(unfinishedBefore,batchSnapshot(unfinished));
        assertEquals(404,call("PUT",itemPath(unfinished,itemId(unfinished))+"/answer",Map.of("result",1),tokenA).statusCode());
        assertEquals(unfinishedBefore,batchSnapshot(unfinished));
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(daily));
        assertEquals(2,get("/api/learning-plan/words/"+word+"/history",tokenA).get("total").asInt());
        assertEquals("ACTIVE",state(a,word).getPlanStatus()); assertEquals(1,statisticsDaily(statisticsDate()).get("completedCount").asInt());
        assertEquals(0,get("/api/word-books/"+emptyBook,tokenA).get("wordCount").asInt());
    }

    @Test void personalBooksReuseTheExistingJoinPlanEndpointAndShareMemoryAcrossBooks() throws Exception {
        long first=personalBook(prefix+"_first",""),second=personalBook(prefix+"_second",""); long word=wordIds.get(1);
        personalBookWords("POST",first,word); personalBookWords("POST",second,word);
        var joined=call("POST","/api/learning-plan/words",Map.of("bookId",first,"wordIds",List.of(word,word)),tokenA);
        assertEquals(200,joined.statusCode()); assertEquals(1,json.readTree(joined.body()).at("/data/addedCount").asInt());
        assertEquals(0,get("/api/word-books/"+first,tokenA).get("startedCount").asInt());
        long daily=startDay().get("batchId").asLong(); dailyAnswer(daily,itemId(daily),1,1); String memory=stateSnapshot();
        var again=call("POST","/api/learning-plan/words",Map.of("bookId",second,"wordIds",List.of(word)),tokenA);
        assertEquals(200,again.statusCode()); assertEquals(1,json.readTree(again.body()).at("/data/unchangedCount").asInt());
        assertEquals(memory,stateSnapshot());
        assertEquals(1,get("/api/word-books/"+first,tokenA).get("startedCount").asInt());
        assertEquals(1,get("/api/word-books/"+second,tokenA).get("startedCount").asInt());
        assertEquals(1,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a)).and(column("word_id").eq(word))));
        personalBookWords("DELETE",first,word);
        assertEquals(400,call("POST","/api/learning-plan/words",Map.of("bookId",first,"wordIds",List.of(word)),tokenA).statusCode());
        assertEquals(404,call("POST","/api/learning-plan/words",Map.of("bookId",second,"wordIds",List.of(word)),tokenB).statusCode());
        assertEquals(memory,stateSnapshot());
    }

    @Test void personalBooksProgressSeparatesUnstartedStartedAndActiveDueUsingShanghaiTime() throws Exception {
        clock.at(Instant.parse("2026-10-20T16:00:00Z")); tokenA=jwt.issue(a); tokenB=jwt.issue(b);
        personalBookWords("POST",emptyBook,wordIds.subList(0,7).toArray(Long[]::new)); joinPlan(wordIds.subList(1,7).toArray(Long[]::new));
        var now=LocalDateTime.of(2026,10,21,0,0);
        for(int i=2;i<=5;i++) {
            var state=state(a,wordIds.get(i)); state.setFirstLearnedAt(now.minusDays(10));
            state.setNextReviewAt(i==2?now.plusSeconds(1):now.minusDays(1));
            state.setPlanStatus(i==3?"PAUSED":i==4?"REMOVED":"ACTIVE"); states.update(state);
        }
        var original=state(a,wordIds.get(0)); original.setNextReviewAt(now.plusDays(5)); states.update(original);
        var due=state(a,wordIds.get(5)); due.setNextReviewAt(now); states.update(due);
        var progress=get("/api/word-books/"+emptyBook,tokenA);
        assertEquals(7,progress.get("wordCount").asInt()); assertEquals(2,progress.get("unlearnedCount").asInt());
        assertEquals(5,progress.get("startedCount").asInt()); assertEquals(1,progress.get("dueCount").asInt());
        var other=get("/api/word-books/"+systemBook,tokenB); assertEquals(1,other.get("startedCount").asInt()); assertEquals(0,other.get("dueCount").asInt());
        clock.advanceSeconds(1); assertEquals(2,get("/api/word-books/"+emptyBook,tokenA).get("dueCount").asInt());
        planManagementStatus("PAUSED",wordIds.get(5));
        assertEquals(1,get("/api/word-books/"+emptyBook,tokenA).get("dueCount").asInt());
        assertEquals(5,get("/api/word-books/"+emptyBook,tokenA).get("startedCount").asInt());
    }

    @Test void personalBooksPracticeOnlyDoesNotTurnAnUnlearnedWordIntoStarted() throws Exception {
        personalBookWords("POST",emptyBook,wordIds.get(1)); long practice=historicalPractice(wordIds.get(1)); historicalFeedback(practice,itemId(practice),1);
        var progress=get("/api/word-books/"+emptyBook,tokenA);
        assertEquals(1,progress.get("unlearnedCount").asInt()); assertEquals(0,progress.get("startedCount").asInt());
        assertEquals(0,progress.get("dueCount").asInt());
        assertFalse(get(wordsPath(emptyBook),tokenA).get("records").get(0).get("learned").asBoolean());
    }

    @Test void personalBooksProgressIsGroupedAndQueriesDoNotGrowWithBookCount() throws Exception {
        for(int i=0;i<5;i++) { long id=personalBook(prefix+"_progress_"+i,""); personalBookWords("POST",id,wordIds.get(0),wordIds.get(1)); }
        int writes=probe.writes.get(); probe.reads.set(0); get("/api/word-books?keyword="+prefix+"&size=1",tokenA);
        int one=probe.reads.get(); probe.reads.set(0); var many=get("/api/word-books?keyword="+prefix+"&size=100",tokenA);
        assertEquals(one,probe.reads.get()); assertTrue(one<=3); assertEquals(writes,probe.writes.get());
        for(var book:many.get("records")) {
            assertEquals(book.get("wordCount").asInt(),book.get("unlearnedCount").asInt()+book.get("startedCount").asInt());
            assertTrue(book.get("dueCount").asInt()<=book.get("startedCount").asInt());
        }
    }

    @Test void personalBooksConcurrentDuplicateCollectionsKeepOneAssociationAndStableOrders() throws Exception {
        var body=Map.of("wordIds",List.of(wordIds.get(1),wordIds.get(3))); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("POST",wordsPath(emptyBook),body,tokenA);});
            var two=pool.submit(()->{gate.await();return call("POST",wordsPath(emptyBook),body,tokenA);});
            gate.countDown(); var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body()); assertEquals(200,second.statusCode(),second.body());
            assertEquals(2,json.readTree(first.body()).at("/data/changedCount").asInt()+json.readTree(second.body()).at("/data/changedCount").asInt());
        }
        assertEquals(List.of(wordIds.get(1),wordIds.get(3)),ids(get(wordsPath(emptyBook),tokenA).get("records")));
        var entries=bookItems.selectListByQuery(QueryWrapper.create().where(column("book_id").eq(emptyBook)).orderBy(column("sort_order").asc()));
        assertEquals(List.of(1,2),entries.stream().map(WordBookItem::getSortOrder).toList());
        var duplicate=entries.getFirst(); duplicate.setId(null);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->bookItems.insertSelective(duplicate));
    }

    @Test void personalBooksRemovalAndJoiningPlansSerializeWithoutPartialLearningState() throws Exception {
        long word=wordIds.get(7); personalBookWords("POST",emptyBook,word); var gate=new CountDownLatch(1); int joinStatus;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var remove=pool.submit(()->{gate.await();return call("DELETE",wordsPath(emptyBook),Map.of("wordIds",List.of(word)),tokenA);});
            var join=pool.submit(()->{gate.await();return call("POST","/api/learning-plan/words",Map.of("bookId",emptyBook,"wordIds",List.of(word)),tokenA);});
            gate.countDown(); assertEquals(200,remove.get(15,TimeUnit.SECONDS).statusCode()); joinStatus=join.get(15,TimeUnit.SECONDS).statusCode();
        }
        assertTrue(Set.of(200,400).contains(joinStatus));
        assertEquals(joinStatus==200?1:0,states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a)).and(column("word_id").eq(word))));
        assertEquals(0,get("/api/word-books/"+emptyBook,tokenA).get("wordCount").asInt()); assertNotNull(words.selectOneById(word));
    }

    @Test void personalBooksMutationFailuresRollBackMetadataAssociationsAndTimestamps() throws Exception {
        String associations=personalBookSnapshot(ownBook),memory=stateSnapshot(); var metadata=json.valueToTree(books.selectOneById(ownBook));
        long count=books.selectCountByQuery(QueryWrapper.create());
        probe.failAfterMapper="com.wordtrail.book.mapper.WordBookMapper";
        try {
            assertEquals(500,call("POST","/api/word-books",Map.of("name",prefix+"_failed"),tokenA).statusCode());
            assertEquals(500,call("PUT","/api/word-books/"+ownBook,Map.of("name","failed"),tokenA).statusCode());
        } finally { probe.failAfterMapper=null; }
        assertEquals(count,books.selectCountByQuery(QueryWrapper.create()));
        for(String mapper:List.of("WordBookItemMapper","WordBookMapper")) {
            for(String method:List.of("POST","DELETE")) {
                probe.failAfterMapper="com.wordtrail.book.mapper."+mapper;
                var selected="POST".equals(method)?List.of(wordIds.get(1),wordIds.get(3)):List.of(wordIds.get(2));
                try { assertEquals(500,call(method,wordsPath(ownBook),Map.of("wordIds",selected),tokenA).statusCode()); }
                finally { probe.failAfterMapper=null; }
                assertEquals(associations,personalBookSnapshot(ownBook)); assertEquals(metadata,json.valueToTree(books.selectOneById(ownBook)));
                assertEquals(memory,stateSnapshot());
            }
        }
    }

    @Test void personalBooksOrderOverflowRejectsCollectionWithoutChangingExistingMembership() throws Exception {
        var old=bookItems.selectOneByQuery(QueryWrapper.create().where(column("book_id").eq(ownBook)));
        old.setSortOrder(Integer.MAX_VALUE); bookItems.update(old); String snapshot=personalBookSnapshot(ownBook);
        var response=call("POST",wordsPath(ownBook),Map.of("wordIds",List.of(wordIds.get(1))),tokenA);
        assertEquals(409,response.statusCode()); assertEquals("BOOK_ORDER_LIMIT",json.readTree(response.body()).get("code").asText());
        assertEquals(snapshot,personalBookSnapshot(ownBook));
    }

    private JsonNode weakWords(String source) throws Exception {
        return get("/api/learning/weak-words?source="+source,tokenA);
    }

    @Test void wordDetailsShowOnlyOwnPersonalBooksAndCurrentPlanWithoutInventingHistory() throws Exception {
        long word=wordIds.get(1); associate(ownBook,word,1); associate(otherBook,word,1);
        var before=get("/api/words/"+word,tokenA);
        assertTrue(before.get("plan").isNull()); assertEquals(List.of(ownBook),ids(before.get("personalBooks")));
        assertEquals("测试",before.get("translation").asText());
        joinPlan(word); var planned=get("/api/words/"+word,tokenA).get("plan");
        assertEquals("ACTIVE",planned.get("planStatus").asText()); assertFalse(planned.get("started").asBoolean());
        assertFalse(planned.get("due").asBoolean()); assertTrue(planned.get("lastResult").isNull());
        var other=get("/api/words/"+word,tokenB);
        assertEquals(List.of(otherBook),ids(other.get("personalBooks"))); assertTrue(other.get("plan").get("started").asBoolean());
        assertEquals(0,get("/api/learning-plan/words/"+word+"/history",tokenA).get("total").asInt());
    }

    @Test void wordDetailsValidateIdsAndUseShanghaiDueBoundaryWithoutWritesOrPerBookQueries() throws Exception {
        clock.at(Instant.parse("2026-10-22T16:00:00Z")); tokenA=jwt.issue(a);
        long word=wordIds.get(0); var state=state(a,word); state.setNextReviewAt(LocalDateTime.of(2026,10,23,0,0)); states.update(state);
        assertTrue(get("/api/words/"+word,tokenA).get("plan").get("due").asBoolean());
        planManagementStatus("PAUSED",word);
        assertFalse(get("/api/words/"+word,tokenA).get("plan").get("due").asBoolean());
        for(int i=0;i<4;i++) associate(personalBook(prefix+"_detail_"+i,""),word,1);
        int writes=probe.writes.get(); probe.reads.set(0); get("/api/words/"+word,tokenA);
        assertEquals(3,probe.reads.get()); assertEquals(writes,probe.writes.get());
        assertEquals(400,call("GET","/api/words/0",null,tokenA).statusCode());
        assertEquals(404,call("GET","/api/words/"+Long.MAX_VALUE,null,tokenA).statusCode());
        assertEquals(401,call("GET","/api/words/"+word,null,null).statusCode());
    }

    @Test void weakWordsUseEffectiveLatestAnswersAndSeparateDailyFromPractice() throws Exception {
        long word=wordIds.get(1); joinPlan(word); long daily=startDay().get("batchId").asLong();
        long item=itemId(daily); dailyAnswer(daily,item,2,1);
        var weak=weakWords("DAILY"); assertEquals(1,weak.get("total").asInt());
        assertEquals(word,weak.get("records").get(0).get("wordId").asLong());
        assertEquals("DAILY",weak.get("records").get(0).get("source").asText());
        long practice=historicalPractice(word); historicalFeedback(practice,itemId(practice),1);
        assertEquals(1,weakWords("DAILY").get("total").asInt()); assertEquals(0,weakWords("PRACTICE").get("total").asInt());
        dailyAnswer(daily,item,1,2); assertEquals(0,weakWords("DAILY").get("total").asInt());
        assertEquals("IN_PROGRESS",get(dailyPath(daily),tokenA).get("status").asText());
    }

    @Test void historicalWeakWordsRespectCorrectionsAndLatestAnswerOrder() throws Exception {
        long word=wordIds.get(1), first=historicalPractice(word), item=itemId(first);
        historicalFeedback(first,item,3);
        assertEquals(1,weakWords("PRACTICE").get("records").get(0).get("answerCount").asInt());
        historicalFeedback(first,item,1); assertEquals(0,weakWords("PRACTICE").get("total").asInt());
        historicalFeedback(first,item,2);
        assertEquals(2,weakWords("PRACTICE").get("records").get(0).get("lastResult").asInt());
        assertEquals(1,weakWords("PRACTICE").get("records").get(0).get("answerCount").asInt());
        long second=historicalPractice(word); historicalFeedback(second,itemId(second),1);
        assertEquals(0,weakWords("PRACTICE").get("total").asInt());
    }

    @Test void weakDailyCorrectionsAndReinforcementUseRealEventsIncludingAcrossDays() throws Exception {
        long word=wordIds.get(1); joinPlan(word); long batch=startDay().get("batchId").asLong(); long item=itemId(batch);
        var answer=dailyAnswer(batch,item,1,1); long log=answer.get("logId").asLong();
        String key=requestId(); correction(batch,item,log,3,key); correction(batch,item,log,3,key);
        var weak=weakWords("DAILY").get("records").get(0);
        assertEquals(1,weak.get("answerCount").asInt()); assertEquals(3,weak.get("lastResult").asInt());
        dailyAnswer(batch,item,2,2); assertEquals(2,weakWords("DAILY").get("records").get(0).get("answerCount").asInt());
        String memory=stateSnapshot(),queue=batchSnapshot(batch);
        long practice=historicalPractice(word); historicalFeedback(practice,itemId(practice),1);
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch));
        assertEquals(1,weakWords("DAILY").get("total").asInt()); assertEquals(0,weakWords("PRACTICE").get("total").asInt());
        advanceDays(1); assertEquals(1,weakWords("DAILY").get("total").asInt());
        long next=startDay().get("batchId").asLong(); dailyAnswer(next,itemId(next),1,1);
        assertEquals(0,weakWords("DAILY").get("total").asInt());
        assertEquals(1,get("/api/learning/statistics/daily?date="+batches.selectOneById(batch).getLearningDate(),tokenA).get("unfamiliarCount").asInt());
    }

    @Test void weakWordsPaginateStablyValidateSourceAndNeverExposeOtherUsersEvents() throws Exception {
        long practice=historicalPractice(wordIds.get(1),wordIds.get(2)); var entries=batchDetails(practice);
        for(var item:entries) historicalFeedback(practice,item.getId(),3);
        var one=get("/api/learning/weak-words?source=PRACTICE&size=1",tokenA);
        var two=get("/api/learning/weak-words?source=PRACTICE&size=1&page=2",tokenA);
        assertEquals(2,one.get("total").asInt());
        assertEquals(wordIds.get(2).longValue(),one.get("records").get(0).get("wordId").asLong());
        assertEquals(wordIds.get(1).longValue(),two.get("records").get(0).get("wordId").asLong());
        assertEquals(0,get("/api/learning/weak-words?source=PRACTICE",tokenB).get("total").asInt());
        assertEquals(0,get("/api/learning/weak-words",tokenA).get("total").asInt());
        for(int i=0;i<3;i++) {
            assertEquals(2,weakWords("PRACTICE").get("total").asInt());
            assertEquals(0,get("/api/learning/weak-words?source=PRACTICE",tokenB).get("total").asInt());
            assertEquals(0,weakWords("DAILY").get("total").asInt());
        }
        assertEquals(0,get("/api/learning/weak-words?source=PRACTICE&page=2147483647",tokenA).get("records").size());
        for(String query:List.of("source=ALL","source=daily","source=","page=0","size=101","page=no"))
            assertEquals(400,call("GET","/api/learning/weak-words?"+query,null,tokenA).statusCode());
        assertEquals(401,call("GET","/api/learning/weak-words",null,null).statusCode());
        probe.reads.set(0); get("/api/learning/weak-words?source=PRACTICE&size=1",tokenA); int count=probe.reads.get();
        probe.reads.set(0); get("/api/learning/weak-words?source=PRACTICE&size=100",tokenA); assertEquals(count,probe.reads.get());
        assertEquals(2,count);
    }

    @Test void weakWordsDoNotInferLegacyEventsFromCurrentMemoryOrOldDetails() throws Exception {
        var practice=weakWords("PRACTICE");
        assertEquals(0,practice.get("total").asInt()); assertFalse(practice.get("statisticsComplete").asBoolean());
        assertEquals(1,practice.get("incompleteWordCount").asInt()); assertTrue(practice.get("firstRecordedAt").isNull());
        assertEquals("RECORDED_EVENTS",practice.get("dataScope").asText());
        var daily=weakWords("DAILY"); assertTrue(daily.get("statisticsComplete").asBoolean());
        assertEquals(0,daily.get("total").asInt());
        assertEquals(404,call("POST","/api/learning/weak-words/practice",Map.of(),tokenA).statusCode());
    }







    private void insightDate(LocalDate date) {
        clock.at(date.atTime(9,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        tokenA=jwt.issue(a); tokenB=jwt.issue(b);
    }
    private JsonNode insightSummary(String source) throws Exception {
        return get("/api/learning/statistics/summary?source="+source,tokenA);
    }
    private JsonNode insightTrend(String source,int days) throws Exception {
        return get("/api/learning/statistics/trends?source="+source+"&days="+days,tokenA);
    }
    private JsonNode insightDay(JsonNode trend,LocalDate date) {
        for(var row:trend.get("records")) if(date.toString().equals(row.get("learningDate").asText())) return row;
        throw new AssertionError("Missing trend day "+date);
    }

    @Test void insightsDefaultToSevenShanghaiDaysAndNeverCreateRecords() throws Exception {
        var today=LocalDate.of(2028,3,1); insightDate(today);
        long before=countBatches(); int writes=probe.writes.get();
        var trend=get("/api/learning/statistics/trends",tokenA);
        assertEquals("DAILY",trend.get("source").asText()); assertEquals(7,trend.get("records").size());
        assertEquals(today.minusDays(6).toString(),trend.get("from").asText());
        assertEquals(today.toString(),trend.get("to").asText());
        for(var row:trend.get("records")) {
            assertEquals(0,row.get("completedCount").asInt()); assertEquals(0,row.get("answerCount").asInt());
            assertEquals(0,row.get("studiedCount").asInt()); assertFalse(row.get("hasRecordedAnswers").asBoolean());
        }
        var thirty=insightTrend("DAILY",30); assertEquals(30,thirty.get("records").size());
        assertEquals("2028-02-01",thirty.get("from").asText()); insightDay(thirty,LocalDate.of(2028,2,29));
        var summary=get("/api/learning/statistics/summary",tokenA);
        assertEquals(0,summary.get("currentStreak").asInt()); assertEquals(0,summary.get("longestStreak").asInt());
        assertEquals(0,summary.get("totalStudyDays").asInt()); assertFalse(summary.get("hasStudiedToday").asBoolean());
        var goal=summary.get("goal"); assertEquals(20,goal.get("dailyTarget").asInt());
        assertEquals(20,goal.get("remainingCount").asInt()); assertFalse(goal.get("reached").asBoolean());
        assertEquals("SETTING",goal.get("targetSource").asText()); assertTrue(goal.get("batchId").isNull());
        assertEquals(writes,probe.writes.get()); assertEquals(before,countBatches());
    }

    @Test void insightsValidateWindowAndSourceAndIsolateRepeatedUserSwitches() throws Exception {
        long batch=historicalPractice(wordIds.get(1)); historicalFeedback(batch,itemId(batch),1);
        for(String query:List.of("days=0","days=1","days=8","days=29","days=31","days=7.0","days=abc","days=",
                "days=2147483648","source=ALL","source=daily","source="))
            assertEquals(400,call("GET","/api/learning/statistics/trends?"+query,null,tokenA).statusCode(),query);
        for(String endpoint:List.of("trends","summary")) {
            assertEquals(401,call("GET","/api/learning/statistics/"+endpoint,null,null).statusCode());
            assertEquals(400,call("GET","/api/learning/statistics/"+endpoint+"?source=ALL",null,tokenA).statusCode());
            for(int i=0;i<3;i++) {
                var own=get("/api/learning/statistics/"+endpoint+"?source=PRACTICE",tokenA);
                var other=get("/api/learning/statistics/"+endpoint+"?source=PRACTICE&user_id="+a,tokenB);
                assertEquals(1,own.get("summary".equals(endpoint)?"todayAnswerCount":"totalAnswerCount").asInt());
                assertEquals(0,other.get("summary".equals(endpoint)?"todayAnswerCount":"totalAnswerCount").asInt());
            }
        }
    }

    @Test void insightsDailyTrendReusesDailyCompletionsAndPadsEmptyDaysAcrossLeapMonth() throws Exception {
        var today=LocalDate.of(2028,3,1);
        for(int i=0;i<3;i++) {
            insightDate(today.minusDays(4-i*2)); joinPlan(wordIds.get(i+1));
            var task=startDay(); long batch=task.get("batchId").asLong();
            while(!get(dailyPath(batch),tokenA).get("nextItemId").isNull()) {
                long item=get(dailyPath(batch),tokenA).get("nextItemId").asLong(); dailyAnswer(batch,item,1,1);
            }
        }
        var trend=insightTrend("DAILY",7); long completions=0,answers=0;
        for(var row:trend.get("records")) {
            var date=LocalDate.parse(row.get("learningDate").asText()); var old=statisticsDaily(date);
            assertEquals(old.get("completedCount"),row.get("completedCount"));
            assertEquals(old.get("answerCount"),row.get("answerCount"));
            completions+=row.get("completedCount").asLong(); answers+=row.get("answerCount").asLong();
        }
        assertEquals(completions,trend.get("totalCompletedCount").asLong());
        assertEquals(answers,trend.get("totalAnswerCount").asLong());
        assertEquals(0,insightDay(trend,today.minusDays(1)).get("answerCount").asInt());
        assertTrue(trend.get("coverage").get("statisticsComplete").asBoolean());
    }

    @Test void insightsReinforcementCorrectionRetriesAndSkippingDoNotInflateDaysOrGoals() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); long item=itemId(batch); String key=requestId();
        var original=dailyAnswer(batch,item,1,1,key); dailyAnswer(batch,item,1,1,key);
        String correctionKey=requestId(); correction(batch,item,original.get("logId").asLong(),3,correctionKey);
        correction(batch,item,original.get("logId").asLong(),3,correctionKey);
        var corrected=insightSummary("DAILY"); assertEquals(1,corrected.get("todayAnswerCount").asInt());
        assertEquals(0,corrected.get("todayCompletedCount").asInt()); assertEquals(1,corrected.get("totalStudyDays").asInt());
        for(int attempt=2;attempt<=4;attempt++) dailyAnswer(batch,item,1,attempt);
        var summary=insightSummary("DAILY"); assertEquals(4,summary.get("todayAnswerCount").asInt());
        assertEquals(1,summary.get("todayStudiedCount").asInt()); assertEquals(1,summary.get("todayCompletedCount").asInt());
        assertEquals(19,summary.get("goal").get("remainingCount").asInt());
        var point=insightDay(insightTrend("DAILY",7),statisticsDate());
        assertEquals(4,point.get("answerCount").asInt()); assertEquals(1,point.get("completedCount").asInt());
        planManagementStatus("PAUSED",wordIds.get(1)); var skipped=insightSummary("DAILY");
        assertEquals(0,skipped.get("todayCompletedCount").asInt()); assertEquals(4,skipped.get("todayAnswerCount").asInt());
        assertEquals(1,skipped.get("currentStreak").asInt()); assertTrue(skipped.get("hasStudiedToday").asBoolean());
        assertEquals(20,skipped.get("goal").get("remainingCount").asInt());
    }

    @Test void insightsHistoricalPracticeDeduplicatesWordsAndCorrections() throws Exception {
        long one=historicalPractice(wordIds.get(1)), two=historicalPractice(wordIds.get(1));
        historicalFeedback(one,itemId(one),3); historicalFeedback(two,itemId(two),3); historicalFeedback(two,itemId(two),1);
        var summary=insightSummary("PRACTICE"); assertEquals(1,summary.get("todayStudiedCount").asInt());
        assertEquals(1,summary.get("todayCompletedCount").asInt()); assertEquals(2,summary.get("todayAnswerCount").asInt());
        assertEquals(1,summary.get("totalStudyDays").asInt()); assertTrue(summary.get("goal").isNull());
        var day=insightDay(insightTrend("PRACTICE",7),statisticsDate());
        assertEquals(1,day.get("completedCount").asInt()); assertEquals(2,day.get("answerCount").asInt());
        assertEquals(0,insightSummary("DAILY").get("totalStudyDays").asInt());
    }

    @Test void insightsStreaksSpanHistoryAndKeepYesterdayRunUntilNextMissedDay() throws Exception {
        var base=LocalDate.of(2026,1,10);
        for(int offset:List.of(0,1,2,5,6)) {
            insightDate(base.plusDays(offset)); long batch=historicalPractice(wordIds.get(1)); historicalFeedback(batch,itemId(batch),1);
        }
        var current=insightSummary("PRACTICE"); assertEquals(2,current.get("currentStreak").asInt());
        assertEquals(3,current.get("longestStreak").asInt()); assertEquals(5,current.get("totalStudyDays").asInt());
        insightDate(base.plusDays(7)); var yesterday=insightSummary("PRACTICE");
        assertEquals(2,yesterday.get("currentStreak").asInt()); assertFalse(yesterday.get("hasStudiedToday").asBoolean());
        insightDate(base.plusDays(8)); assertEquals(0,insightSummary("PRACTICE").get("currentStreak").asInt());
        insightDate(base.plusDays(50)); var distant=insightSummary("PRACTICE");
        assertEquals(3,distant.get("longestStreak").asInt()); assertEquals(5,distant.get("totalStudyDays").asInt());
        assertEquals(0,insightTrend("PRACTICE",30).get("totalAnswerCount").asInt());
    }

    @Test void insightsPracticeSpanningMidnightUsesAnswerDatesAndEachSourceHasItsOwnStreak() throws Exception {
        var yesterday=LocalDate.of(2026,10,22); insightDate(yesterday);
        long practice=historicalPractice(wordIds.get(1),wordIds.get(2)); var items=batchDetails(practice);
        historicalFeedback(practice,items.get(0).getId(),3);
        insightDate(yesterday.plusDays(1)); historicalFeedback(practice,items.get(1).getId(),1);
        var summary=insightSummary("PRACTICE"); assertEquals(2,summary.get("currentStreak").asInt());
        assertEquals(1,summary.get("todayCompletedCount").asInt());
        var trend=insightTrend("PRACTICE",7);
        assertEquals(1,insightDay(trend,yesterday).get("completedCount").asInt());
        assertEquals(1,insightDay(trend,yesterday.plusDays(1)).get("completedCount").asInt());
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); dailyAnswer(daily,itemId(daily),1,1);
        assertEquals(1,insightSummary("DAILY").get("currentStreak").asInt());
        assertEquals(2,insightSummary("PRACTICE").get("currentStreak").asInt());
    }

    @Test void insightsGoalUsesTaskSnapshotAndContinuationCannotMakeRemainingNegative() throws Exception {
        call("PUT","/api/learning/settings",Map.of("dailyTarget",1),tokenA);
        assertEquals(1,insightSummary("DAILY").get("goal").get("dailyTarget").asInt());
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        call("PUT","/api/learning/settings",Map.of("dailyTarget",50),tokenA);
        var before=insightSummary("DAILY").get("goal"); assertEquals(1,before.get("dailyTarget").asInt());
        assertEquals("TASK_SNAPSHOT",before.get("targetSource").asText()); assertEquals(batch,before.get("batchId").asLong());
        dailyAnswer(batch,itemId(batch),1,1); var reached=insightSummary("DAILY").get("goal");
        assertTrue(reached.get("reached").asBoolean()); assertEquals(0,reached.get("remainingCount").asInt());
        startDay(); long next=get(dailyPath(batch),tokenA).get("nextItemId").asLong(); dailyAnswer(batch,next,1,1);
        var continued=insightSummary("DAILY"); assertEquals(2,continued.get("todayCompletedCount").asInt());
        assertEquals(0,continued.get("goal").get("remainingCount").asInt());
        assertEquals(1,continued.get("goal").get("dailyTarget").asInt());
        advanceDays(1); var nextDay=insightSummary("DAILY");
        assertEquals(50,nextDay.get("goal").get("dailyTarget").asInt());
        assertEquals("SETTING",nextDay.get("goal").get("targetSource").asText());
    }

    @Test void insightsCompletedSmallTaskCanStillBeBelowTargetAndPracticeDoesNotFillIt() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); dailyAnswer(batch,itemId(batch),1,1);
        long practice=historicalPractice(wordIds.get(2)); historicalFeedback(practice,itemId(practice),1);
        var daily=insightSummary("DAILY"); assertEquals("COMPLETED",daily.get("goal").get("taskStatus").asText());
        assertFalse(daily.get("goal").get("reached").asBoolean()); assertEquals(19,daily.get("goal").get("remainingCount").asInt());
        assertEquals(1,daily.get("todayCompletedCount").asInt()); assertTrue(insightSummary("PRACTICE").get("goal").isNull());
    }

    @Test void insightsLegacyDataMarksCoverageAndDoesNotInventAnswersOrStreaks() throws Exception {
        var practice=insightSummary("PRACTICE"); assertFalse(practice.get("coverage").get("statisticsComplete").asBoolean());
        assertEquals(1,practice.get("coverage").get("incompleteWordCount").asInt());
        assertEquals(0,practice.get("totalStudyDays").asInt()); assertEquals(0,practice.get("todayAnswerCount").asInt());
        assertTrue(practice.get("coverage").get("firstRecordedAt").isNull());
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong();
        var item=details.selectOneById(itemId(batch)); item.setLearningStatus("DONE"); item.setResult(1);
        item.setAnsweredAt(statisticsDate().atTime(9,0)); item.setCompletedAt(item.getAnsweredAt()); details.update(item);
        var daily=insightSummary("DAILY"); assertEquals(1,daily.get("todayCompletedCount").asInt());
        assertEquals(0,daily.get("todayAnswerCount").asInt()); assertEquals(0,daily.get("totalStudyDays").asInt());
        assertFalse(daily.get("hasStudiedToday").asBoolean()); assertFalse(daily.get("coverage").get("statisticsComplete").asBoolean());
        var trend=insightTrend("DAILY",7); assertEquals(1,trend.get("totalCompletedCount").asInt());
        assertEquals(0,trend.get("totalAnswerCount").asInt());
    }

    @Test void insightsShanghaiMidnightAndMidRequestRolloverUseOneDate() throws Exception {
        clock.at(Instant.parse("2026-10-22T15:59:59Z")); tokenA=jwt.issue(a);
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); dailyAnswer(batch,itemId(batch),1,1);
        probe.afterNextInsightsRead=()->clock.advanceSeconds(1);
        var old=insightSummary("DAILY"); assertEquals("2026-10-22",old.get("learningDate").asText());
        assertEquals(batch,old.get("goal").get("batchId").asLong()); assertEquals(1,old.get("todayAnswerCount").asInt());
        var next=insightSummary("DAILY"); assertEquals("2026-10-23",next.get("learningDate").asText());
        assertFalse(next.get("hasStudiedToday").asBoolean()); assertEquals(1,next.get("currentStreak").asInt());
        assertTrue(next.get("goal").get("batchId").isNull());
        var trend=insightTrend("DAILY",7); assertEquals("2026-10-23",trend.get("to").asText());
        assertEquals(1,insightDay(trend,LocalDate.of(2026,10,22)).get("answerCount").asInt());
    }

    @Test void insightsQueriesStayBoundedAndNeverWriteLearningState() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); dailyAnswer(daily,itemId(daily),1,1);
        long practice=historicalPractice(wordIds.get(2)); historicalFeedback(practice,itemId(practice),3);
        String state=stateSnapshot(),queue=batchSnapshot(daily),exercise=batchSnapshot(practice); long logs=logCount(daily)+logCount(practice);
        int writes=probe.writes.get();
        for(String source:List.of("DAILY","PRACTICE")) {
            probe.reads.set(0); insightTrend(source,7); int seven=probe.reads.get();
            probe.reads.set(0); insightTrend(source,30); assertEquals(seven,probe.reads.get());
            assertEquals("DAILY".equals(source)?3:2,seven);
            probe.reads.set(0); insightSummary(source); assertEquals("DAILY".equals(source)?4:2,probe.reads.get());
        }
        assertEquals(writes,probe.writes.get()); assertEquals(state,stateSnapshot()); assertEquals(queue,batchSnapshot(daily));
        assertEquals(exercise,batchSnapshot(practice)); assertEquals(logs,logCount(daily)+logCount(practice));
    }

    private static final String GROUPED_TRANSLATION="n. 记录；记载\\nvt. 记录；录制\\n[计] 日志";
    private void dictionaryTranslation(long wordId,String translation) {
        var word=words.selectOneById(wordId); word.setTranslation(translation);
        assertEquals(1,words.updateByQuery(word,false,QueryWrapper.create().where(column("id").eq(wordId))));
    }
    private void assertTranslationGroups(JsonNode value) throws Exception {
        assertEquals(GROUPED_TRANSLATION,value.get("translation").asText());
        assertEquals(json.readTree("[{\"partOfSpeech\":\"n.\",\"meaning\":\"记录；记载\"},{\"partOfSpeech\":\"vt.\",\"meaning\":\"记录；录制\"},{\"partOfSpeech\":null,\"meaning\":\"[计] 日志\"}]"),
            value.get("translationGroups"));
    }

    @Test void translationGroupsAreSharedByDictionaryBooksPlansAndWordDetailWithoutChangingStoredText() throws Exception {
        long word=wordIds.get(1); dictionaryTranslation(word,GROUPED_TRANSLATION);
        personalBookWords("POST",emptyBook,word); joinPlan(word);
        int writes=probe.writes.get();
        assertTranslationGroups(get("/api/words/"+word,tokenA));
        assertTranslationGroups(get("/api/words?keyword="+encoded(words.selectOneById(word).getWord())+"&matchType=EXACT",tokenA).get("records").get(0));
        assertTranslationGroups(get(wordsPath(emptyBook),tokenA).get("records").get(0));
        assertTranslationGroups(get("/api/learning-plan/words?keyword="+encoded(words.selectOneById(word).getWord()),tokenA).get("records").get(0));
        assertEquals(GROUPED_TRANSLATION,words.selectOneById(word).getTranslation()); assertEquals(writes,probe.writes.get());
    }

    @Test void translationGroupsDoNotLeakFromDailyPendingReinforcementOrCorrectedItems() throws Exception {
        long word=wordIds.get(1); dictionaryTranslation(word,GROUPED_TRANSLATION); joinPlan(word);
        long batch=startDay().get("batchId").asLong(); long item=itemId(batch);
        var pending=get(dailyItem(batch,item),tokenA);
        assertFalse(pending.has("translation")); assertFalse(pending.has("translationGroups"));
        assertTranslationGroups(dailyAnswer(batch,item,2,1));
        var reinforcement=get(dailyItem(batch,item),tokenA);
        assertFalse(reinforcement.has("translation")); assertFalse(reinforcement.has("translationGroups"));
        assertTranslationGroups(dailyAnswer(batch,item,1,2)); var done=dailyAnswer(batch,item,1,3);
        assertTranslationGroups(done); assertTranslationGroups(get(dailyItem(batch,item),tokenA));
        String key=requestId(); assertTranslationGroups(correction(batch,item,done.get("logId").asLong(),2,key));
        assertTranslationGroups(correction(batch,item,done.get("logId").asLong(),2,key));
        var hiddenAgain=get(dailyItem(batch,item),tokenA);
        assertFalse(hiddenAgain.has("translation")); assertFalse(hiddenAgain.has("translationGroups"));
        assertTranslationGroups(get("/api/learning/statistics/words",tokenA).get("records").get(0));
        assertTranslationGroups(weakWords("DAILY").get("records").get(0));
    }

    @Test void translationGroupsRemainAvailableForHistoricalPracticeWeakWords() throws Exception {
        long word=wordIds.get(1); dictionaryTranslation(word,GROUPED_TRANSLATION);
        long batch=historicalPractice(word); String memory=stateSnapshot();
        historicalFeedback(batch,itemId(batch),2);
        assertTranslationGroups(weakWords("PRACTICE").get("records").get(0));
        assertEquals(memory,stateSnapshot()); assertEquals(GROUPED_TRANSLATION,words.selectOneById(word).getTranslation());
    }

    @Test void translationGroupsFallbackAndEmptyValuesRemainCompatibleWithOriginalTranslation() throws Exception {
        long word=wordIds.get(1);
        for(String text:new String[]{null,"","  ","xyz. 未知标识","[医] 补充说明"}) {
            dictionaryTranslation(word,text); var detail=get("/api/words/"+word,tokenA);
            assertEquals(json.valueToTree(text),detail.get("translation"));
            var groups=detail.get("translationGroups"); assertTrue(groups.isArray());
            if(text==null || text.isBlank()) assertEquals(0,groups.size());
            else { assertEquals(text,groups.get(0).get("meaning").asText()); assertTrue(groups.get(0).get("partOfSpeech").isNull()); }
        }
    }

    private JsonNode importPreview(long book,String text,String format) throws Exception {
        var response=call("POST","/api/word-books/"+book+"/import/preview",Map.of("text",text,"format",format),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private HttpResponse<String> importFile(long book,String name,byte[] bytes,String token) throws Exception {
        String boundary="WordTrail"+UUID.randomUUID().toString().replace("-","");
        var body=new java.io.ByteArrayOutputStream();
        body.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes); body.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+"/api/word-books/"+book+"/import/preview"))
            .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String spelling(int index) { return words.selectOneById(wordIds.get(index)).getWord(); }

    @Test void bookImportPreviewSeparatesMatchesExistingMembershipDuplicatesMissingAndAmbiguity() throws Exception {
        word(spelling(1)); String text=spelling(2)+"\n"+spelling(0)+"\n"+spelling(0)+"\n"+prefix+"missing\n"+spelling(1)+"\n"+"x".repeat(256);
        int writes=probe.writes.get(); var preview=importPreview(ownBook,text,"TXT");
        assertEquals(6,preview.get("totalCount").asInt());
        for(String field:List.of("matchedCount","alreadyInBookCount","duplicateCount","notFoundCount","ambiguousCount","invalidCount"))
            assertEquals(1,preview.get(field).asInt(),field);
        assertEquals(List.of(wordIds.get(2),wordIds.get(0)),java.util.stream.StreamSupport.stream(preview.get("readyWordIds").spliterator(),false).map(JsonNode::asLong).toList());
        var rows=preview.get("records"); assertEquals("ALREADY_IN_BOOK",rows.get(0).get("status").asText());
        assertEquals(2,rows.get(2).get("duplicateOfRow").asInt()); assertEquals(2,rows.get(4).get("candidateCount").asInt());
        assertEquals(2,rows.get(4).get("candidates").size()); assertFalse(rows.get(4).get("hasMoreCandidates").asBoolean());
        assertTrue(rows.get(0).get("candidates").get(0).get("inBook").asBoolean());
        assertEquals(writes,probe.writes.get());
    }

    @Test void bookImportMatchesLiteralSpellingAndCaseAndReturnsBoundedAmbiguousCandidates() throws Exception {
        long capital=word(prefix+"Apple"),phrase=word(prefix+" take off");
        String text=spelling(3)+"\n"+spelling(4)+"\n"+spelling(5)+"\n"+spelling(6)+"\n"+prefix+"Apple\n"+prefix+" take off\n"+prefix+"' OR 1=1 --";
        var rows=importPreview(emptyBook,text,"TXT").get("records");
        assertEquals(7,rows.size()); for(int i=0;i<6;i++) assertEquals("MATCHED",rows.get(i).get("status").asText());
        assertEquals(capital,rows.get(4).get("candidates").get(0).get("id").asLong());
        assertEquals(phrase,rows.get(5).get("candidates").get(0).get("id").asLong());
        assertEquals("NOT_FOUND",rows.get(6).get("status").asText());
        for(int i=0;i<25;i++) word(spelling(1));
        var ambiguous=importPreview(emptyBook,spelling(1),"TXT").get("records").get(0);
        assertEquals(26,ambiguous.get("candidateCount").asInt()); assertEquals(20,ambiguous.get("candidates").size());
        assertTrue(ambiguous.get("hasMoreCandidates").asBoolean());
        assertEquals(26,get("/api/words?keyword="+encoded(spelling(1))+"&matchType=EXACT",tokenA).get("total").asInt());
    }

    @Test void bookImportPreviewUploadsUtf8TxtAndCsvWithoutTreatingExtraColumnsAsDictionaryUpdates() throws Exception {
        String text="\uFEFFtranslation,word,phonetic\r\n\"line1\r\nline2, \"\"quoted\"\"\","+spelling(1)+",/override/\r\n";
        var uploaded=importFile(emptyBook,"words.CSV",text.getBytes(StandardCharsets.UTF_8),tokenA);
        assertEquals(200,uploaded.statusCode(),uploaded.body()); var data=json.readTree(uploaded.body()).get("data");
        assertEquals(1,data.get("matchedCount").asInt()); assertEquals(2,data.get("records").get(0).get("rowNumber").asInt());
        assertEquals(json.readTree("[\"translation\",\"phonetic\"]"),data.get("ignoredColumns"));
        var txt=importFile(emptyBook,"words.txt",("\uFEFF"+spelling(2)+"\n\n"+spelling(1)).getBytes(StandardCharsets.UTF_8),tokenA);
        assertEquals(200,txt.statusCode()); assertEquals(2,json.readTree(txt.body()).get("data").get("matchedCount").asInt());
        assertEquals("测试",words.selectOneById(wordIds.get(1)).getTranslation()); assertEquals("/test/",words.selectOneById(wordIds.get(1)).getPhonetic());
    }

    @Test void bookImportFileAndJsonValidateLimitsEncodingFormatAndUnknownFields() throws Exception {
        for(String name:List.of("words.exe","words.csv.exe","words")) assertEquals(400,importFile(emptyBook,name,new byte[]{1},tokenA).statusCode());
        assertEquals(400,importFile(emptyBook,"words.txt",new byte[]{(byte)0xff},tokenA).statusCode());
        assertEquals(400,importFile(emptyBook,"words.txt",new byte[0],tokenA).statusCode());
        var tooLarge=importFile(emptyBook,"words.txt",new byte[262145],tokenA);
        assertEquals(413,tooLarge.statusCode(),tooLarge.body()); assertEquals("IMPORT_TOO_LARGE",json.readTree(tooLarge.body()).get("code").asText());
        for(Object request:List.of(Map.of("text"," "),Map.of("text",spelling(1),"format","XLSX"),Map.of("text",spelling(1),"user_id",b),
                Map.of("text",spelling(1)+"\n".repeat(2)+spelling(2),"ownerId",b)))
            assertEquals(400,call("POST","/api/word-books/"+emptyBook+"/import/preview",request,tokenA).statusCode());
        var defaultFormat=call("POST","/api/word-books/"+emptyBook+"/import/preview",Map.of("text",spelling(1)),tokenA);
        assertEquals("TXT",json.readTree(defaultFormat.body()).get("data").get("format").asText());
        assertEquals(400,call("POST","/api/word-books/"+emptyBook+"/import/preview",Map.of("text",(spelling(1)+"\n").repeat(101)),tokenA).statusCode());
        assertEquals(413,call("POST","/api/word-books/"+emptyBook+"/import/preview",Map.of("text","汉".repeat(100000)),tokenA).statusCode());
    }

    @Test void bookImportAndExportOnlyAllowOwnedPersonalBooksAndKeepErrorsIndistinguishable() throws Exception {
        for(long book:List.of(otherBook,Long.MAX_VALUE,systemBook)) {
            int status=book==systemBook?403:404;
            assertEquals(status,call("POST","/api/word-books/"+book+"/import/preview",Map.of("text",spelling(1)),tokenA).statusCode());
            assertEquals(status,importFile(book,"words.txt",spelling(1).getBytes(StandardCharsets.UTF_8),tokenA).statusCode());
            assertEquals(status,call("GET","/api/word-books/"+book+"/export",null,tokenA).statusCode());
        }
        assertEquals(401,importFile(emptyBook,"words.txt",new byte[]{1},null).statusCode());
        assertEquals(401,call("GET","/api/word-books/"+emptyBook+"/export",null,null).statusCode());
        assertEquals(404,call("GET","/api/word-books/"+emptyBook+"/export?user_id="+a,null,tokenB).statusCode());
        assertEquals(call("GET","/api/word-books/"+otherBook+"/export",null,tokenA).body(),call("GET","/api/word-books/"+Long.MAX_VALUE+"/export",null,tokenA).body());
    }

    @Test void bookImportConfirmationReusesCollectionAndLeavesPlansDailyPracticeAndHistoryUnchanged() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); dailyAnswer(daily,itemId(daily),2,1);
        long practice=historicalPractice(wordIds.get(2)); historicalFeedback(practice,itemId(practice),3);
        String memory=stateSnapshot(),queue=batchSnapshot(daily),exercise=batchSnapshot(practice); long logs=logCount(daily)+logCount(practice);
        var preview=importPreview(emptyBook,spelling(1)+"\n"+spelling(2)+"\n"+spelling(1),"TXT");
        var request=Map.of("wordIds",preview.get("readyWordIds"));
        var first=call("POST",wordsPath(emptyBook),request,tokenA); assertEquals(200,first.statusCode());
        assertEquals(2,json.readTree(first.body()).get("data").get("changedCount").asInt());
        var second=call("POST",wordsPath(emptyBook),request,tokenA);
        assertEquals(0,json.readTree(second.body()).get("data").get("changedCount").asInt());
        assertEquals(2,importPreview(emptyBook,spelling(1)+"\n"+spelling(2),"TXT").get("alreadyInBookCount").asInt());
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(daily)); assertEquals(exercise,batchSnapshot(practice));
        assertEquals(logs,logCount(daily)+logCount(practice)); assertNull(state(a,wordIds.get(2)));
    }

    @Test void bookImportConfirmationRechecksEveryWordAndRollsBackMembershipAndMetadata() throws Exception {
        var preview=importPreview(emptyBook,spelling(1),"TXT"); assertEquals(1,preview.get("readyWordIds").size());
        String before=personalBookSnapshot(emptyBook); var metadata=json.valueToTree(books.selectOneById(emptyBook));
        assertEquals(404,call("POST",wordsPath(emptyBook),Map.of("wordIds",List.of(wordIds.get(1),Long.MAX_VALUE)),tokenA).statusCode());
        probe.failAfterMapper="com.wordtrail.book.mapper.WordBookMapper";
        try { assertEquals(500,call("POST",wordsPath(emptyBook),Map.of("wordIds",preview.get("readyWordIds")),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; }
        assertEquals(before,personalBookSnapshot(emptyBook)); assertEquals(metadata,json.valueToTree(books.selectOneById(emptyBook)));
        var changed=books.selectOneById(emptyBook); changed.setOwnerId(b); books.update(changed);
        assertEquals(404,call("POST",wordsPath(emptyBook),Map.of("wordIds",preview.get("readyWordIds")),tokenA).statusCode());
    }

    @Test void bookExportPreservesOrderQuotesNewlinesAndCanBePreviewedAgain() throws Exception {
        personalBookWords("POST",emptyBook,wordIds.get(2),wordIds.get(1));
        dictionaryTranslation(wordIds.get(2),"n. 苹果,\"quoted\"\r\n[计] line2\\n原文");
        var response=call("GET","/api/word-books/"+emptyBook+"/export",null,tokenA);
        assertEquals(200,response.statusCode()); assertEquals("text/csv;charset=UTF-8",response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("wordtrail-book-"+emptyBook+".csv"));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.body().startsWith("\uFEFFword,phonetic,translation\r\n"));
        assertTrue(response.body().contains("n. 苹果,\"\"quoted\"\"\r\n[计] line2\\n原文"));
        assertTrue(response.body().indexOf(spelling(2))<response.body().indexOf(spelling(1)));
        var preview=importPreview(emptyBook,response.body(),"CSV"); assertEquals(2,preview.get("alreadyInBookCount").asInt());
        assertEquals(2,preview.get("readyWordIds").size());
    }

    @Test void bookExportSpreadsheetProtectionRoundTripsFormulaWordsAndApostrophes() throws Exception {
        var ids=new ArrayList<Long>();
        for(String text:List.of("=SUM(1,1)","-word","@word","'tis","'=literal")) ids.add(word(text+prefix));
        personalBookWords("POST",emptyBook,ids.toArray(Long[]::new)); dictionaryTranslation(ids.get(0),"=CMD()");
        var exported=getExport(emptyBook); assertTrue(exported.contains("\"'=CMD()\""));
        var preview=importPreview(emptyBook,exported,"CSV"); assertEquals(5,preview.get("alreadyInBookCount").asInt());
        assertEquals(0,preview.get("notFoundCount").asInt()); assertEquals(0,preview.get("ambiguousCount").asInt());
        assertEquals("=CMD()",words.selectOneById(ids.get(0)).getTranslation());
    }
    private String getExport(long book) throws Exception {
        var response=call("GET","/api/word-books/"+book+"/export",null,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return response.body();
    }

    @Test void bookTransferPreviewAndExportDoNotWriteAndPreviewQueriesDoNotGrowPerWord() throws Exception {
        String before=personalBookSnapshot(ownBook),memory=stateSnapshot(); int writes=probe.writes.get();
        String term=spelling(1),many=term+"\n"+spelling(2)+"\n"+spelling(3);
        probe.reads.set(0); importPreview(ownBook,term,"TXT"); int one=probe.reads.get();
        probe.reads.set(0); importPreview(ownBook,many,"TXT");
        assertEquals(one,probe.reads.get()); assertEquals(2,one);
        assertEquals("\uFEFFword,phonetic,translation\r\n",getExport(emptyBook)); getExport(ownBook);
        assertEquals(writes,probe.writes.get()); assertEquals(before,personalBookSnapshot(ownBook)); assertEquals(memory,stateSnapshot());
    }

    @Test void bookExportPaginationReturnsAllWordsAndExposesDownloadFilenameThroughCors() throws Exception {
        var extras=new ArrayList<Long>(); for(int i=0;i<205;i++) {long word=word(prefix+"_export_"+i); extras.add(word); associate(emptyBook,word,i+1);}
        var exported=getExport(emptyBook); assertEquals(205,importCsvDataRows(exported));
        assertTrue(exported.contains(prefix+"_export_204"));
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+"/api/word-books/"+emptyBook+"/export"))
            .header("Authorization","Bearer "+tokenA).header("Origin","http://localhost:5173").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(200,response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow().contains("Content-Disposition"));
        assertEquals(400,call("POST","/api/word-books/"+emptyBook+"/import/preview",Map.of("text",exported,"format","CSV"),tokenA).statusCode());
    }
    private int importCsvDataRows(String csv) { return csv.split("\r\n").length-1; }

    @Test void bookImportAmbiguousWordsRequireChoosingAnIdEvenWhenOneCandidateIsAlreadyCollected() throws Exception {
        long duplicate=word(spelling(1)); personalBookWords("POST",emptyBook,wordIds.get(1));
        var row=importPreview(emptyBook,spelling(1),"TXT"); assertEquals(1,row.get("ambiguousCount").asInt());
        assertEquals(0,row.get("readyWordIds").size());
        var result=personalBookWords("POST",emptyBook,duplicate);
        assertEquals(1,result.get("changedCount").asInt()); assertEquals(2,get("/api/word-books/"+emptyBook,tokenA).get("wordCount").asInt());
        assertNull(state(a,duplicate));
    }

    private void seedExportLimitBook(int count,String translation) {
        var entries=new ArrayList<Word>();
        for(int i=0;i<count;i++) {var word=new Word(); word.setWord(prefix+"_transfer_limit_"+i); word.setTranslation(translation); word.setCreatedAt(LocalDateTime.now()); word.setUpdatedAt(word.getCreatedAt()); entries.add(word);}
        for(int i=0;i<entries.size();i+=50) words.insertBatch(entries.subList(i,Math.min(i+50,entries.size())));
        var created=words.selectListByQuery(QueryWrapper.create().where(column("word").like(prefix+"_transfer_limit_")));
        assertEquals(count,created.size()); var associations=new ArrayList<WordBookItem>(); int order=0;
        for(var word:created) {wordIds.add(word.getId()); var entry=new WordBookItem(); entry.setBookId(emptyBook); entry.setWordId(word.getId()); entry.setSortOrder(++order); entry.setCreatedAt(LocalDateTime.now()); associations.add(entry);}
        bookItems.insertBatch(associations);
    }

    @Test void bookExportOverWordLimitReturnsJsonErrorInsteadOfTruncatingTheFile() throws Exception {
        seedExportLimitBook(10001,"limit fixture");
        var response=call("GET","/api/word-books/"+emptyBook+"/export",null,tokenA);
        assertEquals(413,response.statusCode(),response.body()); assertEquals("BOOK_EXPORT_TOO_LARGE",json.readTree(response.body()).get("code").asText());
        assertFalse(response.headers().firstValue("Content-Disposition").isPresent());
        assertEquals(10001,bookItems.selectCountByQuery(QueryWrapper.create().where(column("book_id").eq(emptyBook))));
    }

    @Test void bookExportOverByteLimitReturnsJsonErrorAndPreservesSourceWords() throws Exception {
        seedExportLimitBook(170,"x".repeat(64000));
        var response=call("GET","/api/word-books/"+emptyBook+"/export",null,tokenA);
        assertEquals(413,response.statusCode(),response.body()); assertEquals("BOOK_EXPORT_TOO_LARGE",json.readTree(response.body()).get("code").asText());
        assertFalse(response.headers().firstValue("Content-Disposition").isPresent());
        assertEquals(64000,words.selectOneById(wordIds.getLast()).getTranslation().length());
    }

    private JsonNode studyMode(long batch,String mode) throws Exception {
        var current=get(dailyPath(batch),tokenA);
        var response=call("PUT",dailyPath(batch)+"/mode",Map.of("mode",mode,"expectedVersion",current.get("modeVersion").asLong()),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }
    private JsonNode trainingQuestion(long batch,Long item) throws Exception {
        var response=call("POST",dailyPath(batch)+"/training/next",item==null?null:Map.of("itemId",item),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private Map<String,Object> trainingBody(JsonNode question,String input,Integer result,String key) {
        var body=new LinkedHashMap<String,Object>(); body.put("requestId",key);
        body.put("presentationId",question.get("presentationId").asText()); body.put("modeVersion",question.get("modeVersion").asLong());
        body.put("attemptNo",question.get("attemptNo").asInt()); body.put("action",input==null && result==null?"REVEAL":"ANSWER");
        if(input!=null) body.put("input",input); if(result!=null) body.put("result",result); return body;
    }
    private String trainingPath(long batch,long item) { return dailyItem(batch,item)+"/training-answer"; }
    private JsonNode train(long batch,JsonNode question,String input,Integer result) throws Exception {
        var response=call("PUT",trainingPath(batch,question.get("itemId").asLong()),trainingBody(question,input,result,requestId()),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode trainingModeStatistics(String mode) throws Exception {
        for(var value:get("/api/learning/statistics/training",tokenA).get("modes")) if(mode.equals(value.get("mode").asText())) return value;
        throw new AssertionError("Missing mode "+mode);
    }

    @Test void independentFollowTypingCompletesPracticeWithoutChangingDailyGoalOrMemory() throws Exception {
        joinPlan(wordIds.get(0)); var state=state(a,wordIds.get(0));
        state.setReviewLevel(4); state.setNextReviewAt(statisticsDate().atStartOfDay()); states.update(state);
        long daily=startDay().get("batchId").asLong(); String memory=stateSnapshot(),queue=batchSnapshot(daily);
        var overview=insightSummary("DAILY"); var stats=statisticsDaily(statisticsDate());
        long batch=followCreate().get("batchId").asLong(); var q=followNext(batch);
        assertEquals(spelling(0),q.get("word").asText()); assertEquals("PRACTICE",q.get("progress").get("taskType").asText());
        assertEquals(20,q.get("progress").get("followTypingTarget").asInt());
        var answer=followAnswer(batch,q,spelling(0)); assertEquals("CORRECT",answer.get("verdict").asText());
        assertEquals(1,answer.get("progress").get("completedCount").asInt()); assertEquals(0,answer.get("progress").get("dailyTarget").asInt());
        assertEquals("COMPLETED",answer.get("progress").get("status").asText()); assertFalse(answer.get("affectsSchedule").asBoolean());
        assertTrue(answer.get("reviewLogId").isNull()); assertNull(details.selectOneById(q.get("itemId").asLong()).getResult());
        assertEquals(0,logCount(batch)); assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(daily));
        assertEquals(overview,insightSummary("DAILY")); assertEquals(stats,statisticsDaily(statisticsDate()));
        assertEquals(0,trainingModeStatistics("FOLLOW_TYPING").get("answerCount").asInt());
        assertEquals(1,followStatistics().get("completedWordCount").asInt());
    }

    @Test void unifiedSwitchPreservesWordsQueueCompletionAndCurrentItemAndInvalidatesOldPage() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        var first=trainingQuestion(batch,null); train(batch,first,null,1);
        var second=trainingQuestion(batch,null); var before=batchDetails(batch); var queue=before.stream().map(LearningBatchItem::getQueueOrder).toList();
        var mode=studyMode(batch,"DICTATION"); assertEquals(1,mode.get("completedCount").asInt());
        var switched=trainingQuestion(batch,null); assertEquals(second.get("itemId"),switched.get("itemId"));
        assertEquals(before.stream().map(LearningBatchItem::getId).toList(),batchDetails(batch).stream().map(LearningBatchItem::getId).toList());
        assertEquals(queue,batchDetails(batch).stream().map(LearningBatchItem::getQueueOrder).toList());
        assertFalse(switched.hasNonNull("word")); assertFalse(switched.hasNonNull("phonetic")); assertFalse(switched.hasNonNull("wordId"));
        assertTrue(switched.get("hintUsed").asBoolean());
        assertEquals(409,call("PUT",trainingPath(batch,second.get("itemId").asLong()),trainingBody(second,null,1,requestId()),tokenA).statusCode());
        assertEquals(409,call("GET",dailyItem(batch,second.get("itemId").asLong()),null,tokenA).statusCode());
        assertEquals(switched,trainingQuestion(batch,null));
    }

    @Test void unifiedExtraTrainingRecordsBothModesButCountsWordOnlyOnce() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); long item=q.get("itemId").asLong(); train(batch,q,spelling(1),null);
        String memory=stateSnapshot(); studyMode(batch,"RECOGNITION"); var extra=trainingQuestion(batch,item);
        assertTrue(extra.get("extraTraining").asBoolean());
        var answer=train(batch,extra,null,1); assertFalse(answer.get("affectsSchedule").asBoolean());
        assertEquals(memory,stateSnapshot()); assertEquals(1,answer.get("progress").get("completedCount").asInt());
        assertEquals(1,statisticsDaily(statisticsDate()).get("completedCount").asInt());
        assertEquals(1,trainingModeStatistics("RECOGNITION").get("answerCount").asInt());
        assertEquals(1,trainingModeStatistics("DICTATION").get("answerCount").asInt());
        assertEquals(2,get(dailyPath(batch)+"/training/attempts",tokenA).get("total").asInt());
        assertEquals(1,get("/api/learning/statistics/training",tokenA).get("completedWordCount").asInt());
    }

    @Test void unifiedDictationAcceptsNewWordsAndRevealIsPersistedWithoutCompletingTheWord() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); assertFalse(q.get("hintUsed").asBoolean());
        assertFalse(q.toString().contains(spelling(1))); String memory=stateSnapshot();
        var revealed=train(batch,q,null,null); assertEquals("REVEALED",revealed.get("verdict").asText());
        assertEquals(spelling(1),revealed.get("word").asText()); assertEquals(memory,stateSnapshot());
        assertEquals(0,revealed.get("progress").get("completedCount").asInt());
        var retry=trainingQuestion(batch,null); assertEquals(q.get("itemId"),retry.get("itemId")); assertTrue(retry.get("hintUsed").asBoolean());
        var answer=train(batch,retry,spelling(1),null); assertEquals("COMPLETED",answer.get("progress").get("status").asText());
        assertEquals("ASSISTED",details.selectOneById(q.get("itemId").asLong()).getCompletionReason());
        assertEquals(2,state(a,wordIds.get(1)).getLastResult());
        assertEquals(1,weakWords("DAILY").get("total").asInt());
        assertEquals(statisticsDate().plusDays(1).atStartOfDay(),state(a,wordIds.get(1)).getNextReviewAt());
        assertEquals(1,trainingModeStatistics("DICTATION").get("revealCount").asInt());
        assertEquals(1,trainingModeStatistics("DICTATION").get("hintedAnswerCount").asInt());
    }

    @Test void unifiedUnpromptedDictationCanAdvanceRecallButPromptedCopyCannot() throws Exception {
        joinPlan(wordIds.get(0)); var memory=state(a,wordIds.get(0));
        memory.setReviewLevel(2); memory.setNextReviewAt(statisticsDate().atStartOfDay()); states.update(memory);
        long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION"); var q=trainingQuestion(batch,null);
        var answer=train(batch,q,"  "+spelling(0).toUpperCase(Locale.ROOT)+"  ",null);
        assertFalse(answer.get("hintUsed").asBoolean()); assertEquals(3,state(a,wordIds.get(0)).getReviewLevel());
        assertEquals(statisticsDate().plusDays(15).atStartOfDay(),state(a,wordIds.get(0)).getNextReviewAt());
    }

    @Test void independentWrongCopyKeepsPracticeOpenAndAccuracySeparateFromSelfAssessment() throws Exception {
        followPlan(wordIds.get(1)); String memory=stateSnapshot(); long batch=followCreate().get("batchId").asLong();
        var wrong=followAnswer(batch,followNext(batch),"wrong");
        assertEquals(0,wrong.get("progress").get("completedCount").asInt()); assertEquals(memory,stateSnapshot());
        assertEquals(0,followStatistics().get("completedWordCount").asInt());
        followAnswer(batch,followNext(batch),spelling(1)); var stats=followStatistics();
        assertEquals(2,stats.get("answerCount").asInt()); assertEquals(0.5,stats.get("inputAccuracy").asDouble(),0.0001);
        assertEquals(1,stats.get("practicedWordCount").asInt()); assertEquals(1,stats.get("completedWordCount").asInt());
        assertEquals(memory,stateSnapshot()); assertTrue(statisticsDaily(statisticsDate()).get("statisticsComplete").asBoolean());
        assertEquals(0,statisticsDaily(statisticsDate()).get("completedCount").asInt());
    }

    @Test void unifiedDictationWrongAnswersRequeueAndLimitCompletesWithoutClaimingRecall() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        for(int i=1;i<=6;i++) {
            var q=trainingQuestion(batch,null); var answer=train(batch,q,"wrong",null);
            assertEquals(i==6?1:0,answer.get("progress").get("completedCount").asInt());
        }
        assertEquals("LIMIT_REACHED",batchDetails(batch).getFirst().getCompletionReason());
        assertEquals(6,trainingModeStatistics("DICTATION").get("answerCount").asInt());
        assertTrue(statisticsDaily(statisticsDate()).get("statisticsComplete").asBoolean());
    }

    @Test void independentFollowRetriesAndConcurrentSubmissionsOnlyConsumeOnePresentation() throws Exception {
        followPlan(wordIds.get(1)); long batch=followCreate().get("batchId").asLong(); var q=followNext(batch);
        long item=q.get("itemId").asLong(); var body=trainingBody(q,spelling(1),null,requestId());
        String path=followPath(batch)+"/items/"+item+"/answer"; var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->{gate.await();return call("PUT",path,body,tokenA);});
            var two=executor.submit(()->{gate.await();return call("PUT",path,body,tokenA);}); gate.countDown();
            var first=one.get(10,TimeUnit.SECONDS); var second=two.get(10,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body()); assertEquals(200,second.statusCode(),second.body());
            assertEquals(data(first).get("trainingId"),data(second).get("trainingId"));
        }
        assertEquals(1,get(followPath(batch)+"/attempts",tokenA).get("total").asInt());
        assertEquals(1,batchDetails(batch).getFirst().getAnswerCount()); assertEquals(0,logCount(batch));
        var changed=new LinkedHashMap<>(body); changed.put("input","wrong"); assertEquals(409,call("PUT",path,changed,tokenA).statusCode());
        var different=new LinkedHashMap<>(body); different.put("requestId",requestId()); assertEquals(409,call("PUT",path,different,tokenA).statusCode());
    }

    @Test void independentFollowSnapshotsAndProgressSurviveRefreshAndMidnightWithoutChangingPreference() throws Exception {
        followPlan(wordIds.get(1)); var body=Map.of("requestId",requestId(),"count",10);
        long batch=followCreate(body).get("batchId").asLong(); var q=followNext(batch);
        String original=spelling(1); var word=words.selectOneById(wordIds.get(1)); word.setWord("changed-reference"); words.update(word);
        assertEquals(q,followNext(batch)); advanceDays(1); assertEquals(q,followNext(batch));
        assertEquals(batch,followCreate(body).get("batchId").asLong());
        assertEquals(original,followNext(batch).get("word").asText()); followAnswer(batch,q,original);
        var daily=startDay(); assertEquals("RECOGNITION",daily.get("studyMode").asText());
        assertEquals("RECOGNITION",get("/api/learning/settings",tokenA).get("preferredStudyMode").asText());
        assertEquals(1,get(followPath(batch)+"/attempts",tokenA).get("total").asInt());
        assertEquals(1,followStatistics().get("answerCount").asInt());
    }

    @Test void unifiedTrainingRejectsOtherUsersPausedWordsForgedResultsAndStaleModes() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); long item=q.get("itemId").asLong();
        assertEquals(404,call("POST",dailyPath(batch)+"/training/next",null,tokenB).statusCode());
        assertEquals(404,call("GET",dailyPath(batch)+"/training/attempts",null,tokenB).statusCode());
        assertEquals(404,call("PUT",trainingPath(batch,item),trainingBody(q,spelling(1),null,requestId()),tokenB).statusCode());
        assertEquals(401,call("GET","/api/learning/statistics/training",null,null).statusCode());
        assertEquals(409,call("PUT",dailyPath(batch)+"/mode",Map.of("mode","FOLLOW_TYPING","expectedVersion",0),tokenA).statusCode());
        assertEquals(409,call("PUT",trainingPath(batch,item),trainingBody(q,spelling(1),1,requestId()),tokenA).statusCode());
        var forged=new LinkedHashMap<>(trainingBody(q,spelling(1),null,requestId())); forged.put("hintUsed",false);
        assertEquals(400,call("PUT",trainingPath(batch,item),forged,tokenA).statusCode());
        var state=state(a,wordIds.get(1)); state.setPlanStatus("PAUSED"); states.update(state);
        assertEquals(409,call("PUT",trainingPath(batch,item),trainingBody(q,spelling(1),null,requestId()),tokenA).statusCode());
        assertEquals(0,get(dailyPath(batch)+"/training/attempts",tokenA).get("total").asInt());
    }

    @Test void unifiedTrainingRollsBackEffectiveFeedbackScheduleAndPresentationWithEventFailure() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); long item=q.get("itemId").asLong(); String memory=stateSnapshot(),queue=batchSnapshot(batch);
        probe.failAfterMapper="com.wordtrail.learning.mapper.WordTrainingAttemptMapper";
        try { assertEquals(500,call("PUT",trainingPath(batch,item),trainingBody(q,spelling(1),null,requestId()),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; }
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(q,trainingQuestion(batch,null));
        assertEquals(0,get(dailyPath(batch)+"/training/attempts",tokenA).get("total").asInt());
        train(batch,q,spelling(1),null);
    }

    @Test void unifiedRecognitionAndLegacyAnswersAreCountedOnceInSeparateTrainingStatistics() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        var q=trainingQuestion(batch,null); train(batch,q,null,1);
        long next=get(dailyPath(batch),tokenA).get("nextItemId").asLong(); dailyAnswer(batch,next,1,1);
        var mode=trainingModeStatistics("RECOGNITION"); assertEquals(2,mode.get("answerCount").asInt());
        assertEquals(2,mode.get("recognizedCount").asInt()); assertTrue(mode.get("inputAccuracy").isNull());
        assertEquals(2,get("/api/learning/statistics/training",tokenA).get("completedWordCount").asInt());
    }

    @Test void unifiedRecognitionStatisticsUseCorrectionButHistoryKeepsOriginalSelfAssessment() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); var q=trainingQuestion(batch,null);
        var answer=train(batch,q,null,1); long item=q.get("itemId").asLong();
        assertEquals(wordIds.get(1).longValue(),answer.get("wordId").asLong());
        correction(batch,item,answer.get("reviewLogId").asLong(),3,requestId());
        var stats=trainingModeStatistics("RECOGNITION"); assertEquals(1,stats.get("answerCount").asInt());
        assertEquals(0,stats.get("recognizedCount").asInt()); assertEquals(1,stats.get("unknownCount").asInt());
        assertEquals(1,get(dailyPath(batch)+"/training/attempts",tokenA).get("records").get(0).get("selfAssessment").asInt());
        assertEquals(0,statisticsDaily(statisticsDate()).get("completedCount").asInt());
    }

    @Test void unifiedDictationWithMissingMeaningCanSwitchToListeningWithoutReplacingTheWord() throws Exception {
        dictionaryTranslation(wordIds.get(1),null); joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong();
        long item=itemId(batch); studyMode(batch,"DICTATION");
        var unavailable=call("POST",dailyPath(batch)+"/training/next",null,tokenA);
        assertEquals(409,unavailable.statusCode()); assertEquals("TRAINING_PROMPT_UNAVAILABLE",json.readTree(unavailable.body()).get("code").asText());
        studyMode(batch,"LISTEN_DICTATION"); var q=trainingQuestion(batch,null); assertEquals(item,q.get("itemId").asLong());
        train(batch,q,spelling(1),null); assertEquals(1,get(dailyPath(batch),tokenA).get("completedCount").asInt());
    }

    @Test void unifiedTrainingAndEffectiveFeedbackShareOneShanghaiDateAcrossMidnight() throws Exception {
        clock.at(Instant.parse("2026-10-25T15:59:59Z")); tokenA=jwt.issue(a);
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); probe.afterNextStateRead=()->clock.advanceSeconds(2);
        var answer=train(batch,q,spelling(1),null); var event=reviewLogs.selectOneById(answer.get("reviewLogId").asLong());
        var training=trainingAttempts.selectOneById(answer.get("trainingId").asLong());
        assertEquals(LocalDate.of(2026,10,25),event.getLearningDate()); assertEquals(event.getLearningDate(),training.getLearningDate());
        assertEquals(event.getAnsweredAt(),training.getAnsweredAt());
        assertEquals(LocalDate.of(2026,10,26).atStartOfDay(),state(a,wordIds.get(1)).getNextReviewAt());
    }

    @Test void trainingPolishRevealedRecognitionRestoresSelfAssessmentAndDoesNotDuplicateReveals() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); var initial=trainingQuestion(batch,null);
        assertEquals("QUESTION",initial.get("phase").asText()); assertFalse(initial.get("answerRevealed").asBoolean());
        var reveal=train(batch,initial,null,null); assertEquals("PENDING",reveal.get("itemStatus").asText());
        var restored=trainingQuestion(batch,null); assertTrue(restored.get("answerRevealed").asBoolean());
        assertEquals("AWAITING_SELF_ASSESSMENT",restored.get("phase").asText());
        assertEquals("测试",restored.get("translation").asText()); assertEquals(restored,trainingQuestion(batch,null));
        assertEquals(409,call("PUT",trainingPath(batch,restored.get("itemId").asLong()),trainingBody(restored,null,null,requestId()),tokenA).statusCode());
        assertEquals(1,trainingModeStatistics("RECOGNITION").get("revealCount").asInt());
        var answered=train(batch,restored,null,1); assertEquals("DONE",answered.get("itemStatus").asText());
        assertEquals("RECALLED",answered.get("completionReason").asText());
        correction(batch,restored.get("itemId").asLong(),answered.get("reviewLogId").asLong(),3,requestId());
        var reopened=trainingQuestion(batch,null); assertEquals("QUESTION",reopened.get("phase").asText());
        assertFalse(reopened.hasNonNull("translation")); assertFalse(reopened.get("answerRevealed").asBoolean());
    }

    @Test void trainingPolishRevealSurvivesModeSwitchAndDictationRevealRestoresReference() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong();
        train(batch,trainingQuestion(batch,null),null,null); studyMode(batch,"DICTATION");
        var hidden=trainingQuestion(batch,null); assertFalse(hidden.hasNonNull("word"));
        studyMode(batch,"RECOGNITION"); assertEquals("AWAITING_SELF_ASSESSMENT",trainingQuestion(batch,null).get("phase").asText());
        studyMode(batch,"DICTATION"); train(batch,trainingQuestion(batch,null),null,null);
        var revealed=trainingQuestion(batch,null); assertTrue(revealed.get("answerRevealed").asBoolean());
        assertEquals(spelling(1),revealed.get("word").asText()); assertEquals("AWAITING_INPUT",revealed.get("phase").asText());
        assertEquals(revealed,trainingQuestion(batch,null));
        var answered=train(batch,revealed,spelling(1),null);
        assertEquals("DONE",answered.get("itemStatus").asText()); assertEquals("ASSISTED",answered.get("completionReason").asText());
    }

    @Test void trainingPolishTaskInventoryIsPagedReadOnlyAndDoesNotExposePendingAnswers() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); long batch=startDay().get("batchId").asLong();
        var first=trainingQuestion(batch,null); long item=first.get("itemId").asLong(); train(batch,first,null,1);
        studyMode(batch,"DICTATION"); String before=batchSnapshot(batch),memory=stateSnapshot(); int writes=probe.writes.get();
        var list=get(dailyPath(batch)+"/training/items?size=1",tokenA); assertEquals(2,list.get("total").asInt());
        var done=list.get("records").get(0); assertEquals(item,done.get("itemId").asLong());
        assertEquals("DONE",done.get("itemStatus").asText()); assertEquals("RECALLED",done.get("completionReason").asText());
        assertEquals(spelling(1),done.get("word").asText()); assertTrue(done.get("canExtraTrain").asBoolean());
        var pending=get(dailyPath(batch)+"/training/items?page=2&size=1",tokenA).get("records").get(0);
        assertEquals("PENDING",pending.get("itemStatus").asText()); assertTrue(pending.get("current").asBoolean());
        for(String field:List.of("wordId","word","phonetic","translation")) assertFalse(pending.hasNonNull(field),field);
        assertEquals(0,get(dailyPath(batch)+"/training/items?page=2147483647",tokenA).get("records").size());
        assertEquals(404,call("GET",dailyPath(batch)+"/training/items",null,tokenB).statusCode());
        assertEquals(400,call("GET",dailyPath(batch)+"/training/items?size=101",null,tokenA).statusCode());
        assertEquals(before,batchSnapshot(batch)); assertEquals(memory,stateSnapshot()); assertEquals(writes,probe.writes.get());
        var replay=trainingQuestion(batch,done.get("itemId").asLong()); assertTrue(replay.get("extraTraining").asBoolean());
    }

    @Test void trainingPolishLegacyInventoryUsesCurrentWordWithoutFabricatingSnapshotOrAllowingExpiredReplay() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); long item=itemId(batch); dailyAnswer(batch,item,1,1);
        var old=details.selectOneById(item); old.setStudyWord(null); old.setStudyTranslation(null); old.setStudyPhonetic(null);
        details.updateByQuery(old,false,QueryWrapper.create().where(column("id").eq(item)));
        String before=batchSnapshot(batch); var row=get(dailyPath(batch)+"/training/items",tokenA).get("records").get(0);
        assertEquals(spelling(1),row.get("word").asText()); assertEquals(before,batchSnapshot(batch));
        advanceDays(1); row=get(dailyPath(batch)+"/training/items",tokenA).get("records").get(0);
        assertFalse(row.get("canExtraTrain").asBoolean()); assertEquals("DONE",row.get("itemStatus").asText());
    }

    @Test void trainingPolishCompletionReasonsAreReturnedForIndependentCopyDailyRecallAndAttemptLimit() throws Exception {
        followPlan(wordIds.get(1)); long follow=followCreate().get("batchId").asLong();
        var copied=followAnswer(follow,followNext(follow),spelling(1));
        assertEquals("FOLLOW_TYPED",copied.get("completionReason").asText()); assertEquals("DONE",copied.get("itemStatus").asText());
        long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        assertEquals("RECALLED",train(batch,trainingQuestion(batch,null),spelling(1),null).get("completionReason").asText());
        joinPlan(wordIds.get(2)); startDay(); JsonNode last=null;
        for(int attempt=0;attempt<6;attempt++) last=train(batch,trainingQuestion(batch,null),"wrong",null);
        assertEquals("LIMIT_REACHED",last.get("completionReason").asText());
        var statistics=statisticsDaily(statisticsDate()); assertEquals(2,statistics.get("completedCount").asInt());
        assertEquals(1,statistics.get("recalledCount").asInt()); assertEquals(1,followStatistics().get("completedWordCount").asInt());
    }

    @Test void independentWrongCopyAndExtraTrainingStayOutOfDailyOverview() throws Exception {
        followPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong();
        var summary=insightSummary("DAILY"); var day=statisticsDaily(statisticsDate());
        long batch=followCreate().get("batchId").asLong(); var q=followNext(batch); long item=q.get("itemId").asLong();
        followAnswer(batch,q,"wrong"); followAnswer(batch,followNext(batch),spelling(1));
        var extra=call("POST",followPath(batch)+"/next",Map.of("itemId",item),tokenA); assertEquals(200,extra.statusCode());
        followAnswer(batch,data(extra),spelling(1)); assertEquals(3,followStatistics().get("answerCount").asInt());
        assertEquals(1,followStatistics().get("completedWordCount").asInt());
        assertEquals(summary,insightSummary("DAILY")); assertEquals(day,statisticsDaily(statisticsDate()));
        assertEquals(0,get(dailyPath(daily),tokenA).get("completedCount").asInt());
        assertEquals(0,insightSummary("PRACTICE").get("todayTrainingCount").asInt());
    }

    @Test void trainingPolishRevealCountsAsActivityButNotAnswerCompletionOrRecall() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); var q=trainingQuestion(batch,null);
        train(batch,q,null,null); trainingQuestion(batch,null); trainingQuestion(batch,null);
        var summary=insightSummary("DAILY"); assertEquals(1,summary.get("totalStudyDays").asInt());
        assertEquals(1,summary.get("todayTrainingCount").asInt()); assertEquals(0,summary.get("todayAnswerCount").asInt());
        assertEquals(0,summary.get("todayCompletedCount").asInt());
        var day=insightDay(insightTrend("DAILY",7),statisticsDate()); assertTrue(day.get("hasLearningActivity").asBoolean());
        assertFalse(day.get("hasRecordedAnswers").asBoolean());
        var answered=train(batch,trainingQuestion(batch,null),null,1);
        assertEquals(2,insightSummary("DAILY").get("todayTrainingCount").asInt());
        assertEquals(1,insightSummary("DAILY").get("todayRecalledCount").asInt());
        correction(batch,q.get("itemId").asLong(),answered.get("reviewLogId").asLong(),3,requestId());
        assertEquals(0,insightSummary("DAILY").get("todayRecalledCount").asInt());
        assertEquals(2,insightSummary("DAILY").get("todayTrainingCount").asInt());
    }

    @Test void dailyActivityDaysAndCalendarIncludeRecognitionRevealsWithoutEffectiveFeedback() throws Exception {
        var firstDate=LocalDate.of(2026,10,25); insightDate(firstDate); joinPlan(wordIds.get(1));
        long batch=startDay().get("batchId").asLong(); train(batch,trainingQuestion(batch,null),null,null);
        advanceDays(1); long next=startDay().get("batchId").asLong(); train(next,trainingQuestion(next,null),null,null);
        var summary=insightSummary("DAILY"); assertEquals(2,summary.get("currentStreak").asInt()); assertEquals(2,summary.get("totalStudyDays").asInt());
        var calendar=get("/api/learning/statistics/calendar?month=2026-10",tokenA);
        JsonNode found=null; for(var row:calendar.get("days")) if(firstDate.toString().equals(row.get("learningDate").asText())) found=row;
        assertNotNull(found); assertTrue(found.get("hasLearningActivity").asBoolean()); assertEquals(1,found.get("trainingCount").asInt());
        assertEquals(0,found.get("completedCount").asInt());
        assertEquals(0,get("/api/learning/statistics/summary?source=DAILY",tokenB).get("totalStudyDays").asInt());
    }

    @Test void trainingPolishMissingTrainingEventsAreReportedIncompleteWithoutInventingActivity() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong();
        var answer=train(batch,trainingQuestion(batch,null),null,null); trainingAttempts.deleteById(answer.get("trainingId").asLong());
        var day=statisticsDaily(statisticsDate()); assertFalse(day.get("statisticsComplete").asBoolean());
        assertEquals(1,day.get("incompleteWordCount").asInt()); assertEquals(0,day.get("trainingCount").asInt());
        assertEquals(0,insightSummary("DAILY").get("totalStudyDays").asInt());
        assertFalse(insightSummary("DAILY").get("coverage").get("statisticsComplete").asBoolean());
    }

    private JsonNode allocation(int newWords,int reviews) throws Exception {
        var response=call("PUT","/api/learning/settings/allocation",Map.of("newWordTarget",newWords,"reviewLimit",reviews),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode consolidationIssues(String query) throws Exception { return get("/api/learning/consolidation/issues"+query,tokenA); }
    private String followPath(long batch) { return "/api/learning/follow-typing/batches/"+batch; }
    private void followPlan(Long... ids) throws Exception {
        joinPlan(ids);
        if(!List.of(ids).contains(wordIds.get(0))) {
            var old=state(a,wordIds.get(0)); old.setPlanStatus("PAUSED"); states.update(old);
        }
    }
    private JsonNode followCreate() throws Exception { return followCreate(Map.of("requestId",requestId())); }
    private JsonNode followCreate(Object request) throws Exception {
        var response=call("POST","/api/learning/follow-typing/batches",request,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode followNext(long batch) throws Exception {
        var response=call("POST",followPath(batch)+"/next",null,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode followAnswer(long batch,JsonNode q,String input) throws Exception {
        var response=call("PUT",followPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",trainingBody(q,input,null,requestId()),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode followStatistics() throws Exception { return get("/api/learning/follow-typing/statistics",tokenA); }
    private void finishFollow(long batch) throws Exception {
        for(int i=0;i<30;i++) {
            var q=followNext(batch); if(q.isNull()) return; followAnswer(batch,q,q.get("word").asText());
        }
        fail("Follow typing did not complete");
    }

    @Test void independentFollowCreateIsIdempotentAndOnlyOneRoundCanBeInProgress() throws Exception {
        followPlan(wordIds.get(1),wordIds.get(2)); var body=Map.of("requestId",requestId(),"count",10);
        long batch=followCreate(body).get("batchId").asLong(); String before=batchSnapshot(batch);
        assertEquals(batch,followCreate(body).get("batchId").asLong());
        assertEquals(409,call("POST","/api/learning/follow-typing/batches",Map.of("requestId",body.get("requestId"),"count",20),tokenA).statusCode());
        var active=call("POST","/api/learning/follow-typing/batches",Map.of("requestId",requestId()),tokenA);
        assertEquals(409,active.statusCode()); assertEquals("FOLLOW_TYPING_IN_PROGRESS",json.readTree(active.body()).get("code").asText());
        assertEquals(before,batchSnapshot(batch));
        var list=get("/api/learning/follow-typing/batches?status=IN_PROGRESS",tokenA); assertEquals(1,list.get("total").asInt());
        assertEquals(batch,list.get("records").get(0).get("batchId").asLong());
        var q=followNext(batch); followAnswer(batch,q,q.get("word").asText());
        assertEquals(1,followCreate(body).get("completedCount").asInt());
        assertEquals(followNext(batch),followNext(batch)); finishFollow(batch);
        assertEquals(batch,followCreate(body).get("batchId").asLong());
    }

    @Test void independentFollowSelectionLimitsPersistAcrossAbandonAndExpireAfterTwentyFourHours() throws Exception {
        followPlan(wordIds.get(1));
        for(int i=0;i<2;i++) {
            long batch=followCreate().get("batchId").asLong();
            var abandoned=call("POST",followPath(batch)+"/abandon",null,tokenA); assertEquals(200,abandoned.statusCode());
            assertEquals("ABANDONED",get(followPath(batch),tokenA).get("status").asText());
            assertTrue(get(followPath(batch),tokenA).get("nextItemId").isNull());
            assertEquals(409,call("POST",followPath(batch)+"/next",null,tokenA).statusCode());
        }
        var capped=call("POST","/api/learning/follow-typing/batches",Map.of("requestId",requestId()),tokenA);
        assertEquals(409,capped.statusCode()); assertEquals("NO_FOLLOW_TYPING_WORDS",json.readTree(capped.body()).get("code").asText());
        assertEquals(0,followStatistics().get("answerCount").asInt());
        advanceDays(1); assertEquals(1,followCreate().get("totalCount").asInt());
    }

    @Test void independentFollowUsesOnlyActiveOwnPlanAndSkipsWordsMadeUnavailableMidRound() throws Exception {
        followPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4));
        var paused=state(a,wordIds.get(3)); paused.setPlanStatus("PAUSED"); states.update(paused);
        var removed=state(a,wordIds.get(4)); removed.setPlanStatus("REMOVED"); states.update(removed);
        long batch=followCreate().get("batchId").asLong();
        var rows=get(followPath(batch)+"/items",tokenA).get("records"); assertEquals(2,rows.size());
        var actual=new HashSet<Long>(); for(var row:rows) { actual.add(row.get("wordId").asLong()); assertTrue(row.hasNonNull("word")); }
        assertEquals(Set.of(wordIds.get(1),wordIds.get(2)),actual);
        var q=followNext(batch); long first=q.get("wordId").asLong(); var firstState=state(a,first);
        firstState.setPlanStatus("PAUSED"); states.update(firstState);
        assertEquals(409,call("PUT",followPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",trainingBody(q,q.get("word").asText(),null,requestId()),tokenA).statusCode());
        var next=followNext(batch); assertNotEquals(first,next.get("wordId").asLong());
        var secondState=state(a,next.get("wordId").asLong()); secondState.setPlanStatus("REMOVED"); states.update(secondState);
        assertTrue(followNext(batch).isNull()); var progress=get(followPath(batch),tokenA);
        assertEquals("COMPLETED",progress.get("status").asText()); assertEquals(2,progress.get("skippedCount").asInt());
        assertEquals(0,progress.get("completedCount").asInt()); assertEquals(0,followStatistics().get("answerCount").asInt());
    }

    @Test void independentFollowRejectsOtherUsersFamiliesInvalidAnswersAndInvalidCounts() throws Exception {
        followPlan(wordIds.get(1)); long batch=followCreate().get("batchId").asLong(); var q=followNext(batch);
        for(String suffix:List.of("","/items","/attempts")) assertEquals(404,call("GET",followPath(batch)+suffix,null,tokenB).statusCode());
        assertEquals(404,call("POST",followPath(batch)+"/next",null,tokenB).statusCode());
        assertEquals(404,call("POST",followPath(batch)+"/abandon",null,tokenB).statusCode());
        String answer=followPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer";
        assertEquals(404,call("PUT",answer,trainingBody(q,q.get("word").asText(),null,requestId()),tokenB).statusCode());
        assertEquals(409,call("PUT",answer,trainingBody(q,q.get("word").asText(),1,requestId()),tokenA).statusCode());
        assertEquals(409,call("PUT",answer,trainingBody(q,null,null,requestId()),tokenA).statusCode());
        assertEquals(409,call("POST",dailyPath(batch)+"/training/next",null,tokenA).statusCode());
        assertEquals(409,call("POST",focusPath(batch)+"/next",null,tokenA).statusCode());
        assertEquals(200,download(q.get("audioUrl").asText(),tokenA).statusCode());
        assertEquals(404,download(q.get("audioUrl").asText(),tokenB).statusCode());
        for(int count:List.of(0,9,21)) assertEquals(400,call("POST","/api/learning/follow-typing/batches",Map.of("requestId",requestId(),"count",count),tokenA).statusCode());
        assertEquals(401,call("GET","/api/learning/follow-typing/statistics",null,null).statusCode());
        assertEquals(400,call("GET","/api/learning/follow-typing/statistics?date=2026-02-30",null,tokenA).statusCode());
        assertTrue(get("/api/learning/follow-typing/statistics",tokenB).get("inputAccuracy").isNull());
        assertEquals(0,get(followPath(batch)+"/attempts",tokenA).get("total").asInt());
    }

    @Test void independentFollowRollsBackCreationAndAnswerOnPersistenceFailure() throws Exception {
        followPlan(wordIds.get(1)); long count=countBatches(); probe.failAfterMapper="com.wordtrail.learning.mapper.LearningBatchItemMapper";
        try { assertEquals(500,call("POST","/api/learning/follow-typing/batches",Map.of("requestId",requestId()),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; } assertEquals(count,countBatches());
        long batch=followCreate().get("batchId").asLong(); var q=followNext(batch); String before=batchSnapshot(batch),memory=stateSnapshot();
        probe.failAfterMapper="com.wordtrail.learning.mapper.WordTrainingAttemptMapper";
        try { assertEquals(500,call("PUT",followPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",trainingBody(q,q.get("word").asText(),null,requestId()),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; }
        assertEquals(before,batchSnapshot(batch)); assertEquals(memory,stateSnapshot()); assertEquals(q,followNext(batch));
        assertEquals(0,followStatistics().get("answerCount").asInt());
    }

    @Test void independentFollowWeightsUseCorrectedRealAssessmentsAndNeverRawCopyFeedback() throws Exception {
        followPlan(wordIds.get(1)); var raw=state(a,wordIds.get(1)); raw.setLastResult(3); states.update(raw);
        var candidate=followSelection.candidates(a,contextTime().minusHours(24)).getFirst();
        assertNull(candidate.recognitionResult()); assertNull(candidate.recallVerdict());
        long follow=followCreate().get("batchId").asLong(); finishFollow(follow);
        candidate=followSelection.candidates(a,contextTime().minusHours(24)).getFirst();
        assertNull(candidate.recognitionResult()); assertNull(candidate.recallVerdict()); assertNotNull(candidate.lastPracticedAt());
        long daily=startDay().get("batchId").asLong(); var q=trainingQuestion(daily,null); var answer=train(daily,q,null,3);
        assertEquals(3,followSelection.candidates(a,contextTime().minusHours(24)).getFirst().recognitionResult());
        correction(daily,q.get("itemId").asLong(),answer.get("reviewLogId").asLong(),1,requestId());
        assertEquals(1,followSelection.candidates(a,contextTime().minusHours(24)).getFirst().recognitionResult());
        advanceDays(1); daily=startDay().get("batchId").asLong(); studyMode(daily,"DICTATION");
        train(daily,trainingQuestion(daily,null),spelling(1),null);
        candidate=followSelection.candidates(a,contextTime().minusHours(24)).getFirst();
        assertEquals("CORRECT",candidate.recallVerdict()); assertFalse(candidate.recallHintUsed());
        assertEquals(0.5,com.wordtrail.learning.service.FollowTypingSelector.weight(candidate,contextTime()));
    }

    @Test void dailyFollowModeIsRejectedAndLegacyPreferencesAndSnapshotsArePreserved() throws Exception {
        followPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); String before=batchSnapshot(batch),memory=stateSnapshot();
        var rejected=call("PUT",dailyPath(batch)+"/mode",Map.of("mode","FOLLOW_TYPING","expectedVersion",0),tokenA);
        assertEquals(409,rejected.statusCode()); assertEquals("FOLLOW_TYPING_PRACTICE_REQUIRED",json.readTree(rejected.body()).get("code").asText());
        assertEquals(before,batchSnapshot(batch)); assertEquals(memory,stateSnapshot());
        var old=batches.selectOneById(batch); old.setStudyMode("FOLLOW_TYPING"); batches.update(old);
        assertEquals(409,call("POST",dailyPath(batch)+"/training/next",null,tokenA).statusCode());
        assertEquals("FOLLOW_TYPING",batches.selectOneById(batch).getStudyMode());
        studyMode(batch,"RECOGNITION"); var setting=learningSettings.selectOneById(a); setting.setPreferredStudyMode("FOLLOW_TYPING"); learningSettings.update(setting);
        assertEquals("RECOGNITION",get("/api/learning/settings",tokenA).get("preferredStudyMode").asText());
        assertEquals("FOLLOW_TYPING",learningSettings.selectOneById(a).getPreferredStudyMode());
        advanceDays(1); assertEquals("RECOGNITION",startDay().get("studyMode").asText());
    }
    private JsonNode focusCreate(Object body) throws Exception {
        var response=call("POST","/api/learning/consolidation/batches",body,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private String focusPath(long batch) { return "/api/learning/consolidation/batches/"+batch; }
    private JsonNode focusNext(long batch) throws Exception {
        var response=call("POST",focusPath(batch)+"/next",null,tokenA); assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private JsonNode focusAnswer(long batch,JsonNode question,String input,Integer result) throws Exception {
        var response=call("PUT",focusPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",trainingBody(question,input,result,requestId()),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return data(response);
    }
    private HttpResponse<byte[]> download(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+path)).GET();
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void expansionAllocationReservesBothSourcesInEachRoundAndAllowsContinuation() throws Exception {
        joinPlan(wordIds.get(0),wordIds.get(1),wordIds.get(2),wordIds.get(3));
        for(int index=0;index<2;index++) { var value=state(a,wordIds.get(index)); value.setFirstLearnedAt(statisticsDate().minusDays(3).atStartOfDay()); value.setNextReviewAt(statisticsDate().minusDays(2-index).atStartOfDay()); states.update(value); }
        var preference=allocation(1,1); assertEquals(2,preference.get("dailyTarget").asInt());
        var task=startDay(); long batch=task.get("batchId").asLong(); assertEquals(1,task.get("newCount").asInt()); assertEquals(1,task.get("reviewCount").asInt());
        assertEquals(1,task.get("newWordTarget").asInt()); assertEquals(1,task.get("reviewLimit").asInt());
        while(!get(dailyPath(batch),tokenA).get("nextItemId").isNull()) { var q=trainingQuestion(batch,null); train(batch,q,null,1); }
        var today=get("/api/learning/today",tokenA); assertTrue(today.get("canAddMore").asBoolean()); assertEquals(1,today.get("reviewBacklogCount").asInt());
        assertFalse(today.get("needsMoreWords").asBoolean()); assertEquals(1,today.get("overdueReviewCount").asInt());
        var continued=startDay(); assertEquals(4,continued.get("totalCount").asInt());
        assertEquals(2,continued.get("newCount").asInt()); assertEquals(2,continued.get("reviewCount").asInt());
        assertEquals(batch,continued.get("batchId").asLong()); finishDailyWithKnown(batch);
        allocation(2,2); assertEquals(1,get(dailyPath(batch),tokenA).get("newWordTarget").asInt());
        advanceDays(1); var tomorrow=startDay(); assertEquals(2,tomorrow.get("newWordTarget").asInt()); assertEquals(2,tomorrow.get("reviewLimit").asInt());
    }

    private void makeDue(Long... ids) {
        for(long id:ids) {
            var value=state(a,id); value.setFirstLearnedAt(statisticsDate().minusDays(10).atStartOfDay());
            value.setNextReviewAt(statisticsDate().minusDays(1).atStartOfDay()); states.update(value);
        }
    }

    @Test void allocationFillsMissingNewWordsWithDueReviewsAndKeepsReviewOrder() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        makeDue(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        var oldest=state(a,wordIds.get(5)); oldest.setNextReviewAt(statisticsDate().minusDays(20).atStartOfDay()); states.update(oldest);
        allocation(2,2); String memory=stateSnapshot(); var task=startDay(); long batch=task.get("batchId").asLong();
        assertEquals(4,task.get("reviewCount").asInt()); assertEquals(0,task.get("newCount").asInt());
        assertEquals(wordIds.get(5),batchDetails(batch).getFirst().getWordId()); assertEquals(memory,stateSnapshot());
        assertEquals(task,startDay()); finishDailyWithKnown(batch);
        assertTrue(get("/api/learning/today",tokenA).get("canAddMore").asBoolean());
        var more=startDay(); assertEquals(batch,more.get("batchId").asLong()); assertEquals(5,more.get("totalCount").asInt());
        assertEquals(5,more.get("reviewCount").asInt()); finishDailyWithKnown(batch);
        assertTrue(get("/api/learning/today",tokenA).get("needsMoreWords").asBoolean());
        assertEquals(5,batchDetails(batch).stream().map(LearningBatchItem::getWordId).distinct().count());
    }

    @Test void allocationFillsMissingReviewsWithNewWordsAndPreservesJoinOrder() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        allocation(2,2); var task=startDay(); long batch=task.get("batchId").asLong();
        assertEquals(4,task.get("newCount").asInt()); assertEquals(0,task.get("reviewCount").asInt());
        assertEquals(wordIds.subList(1,5),batchDetails(batch).stream().map(LearningBatchItem::getWordId).toList());
        assertFalse(get("/api/learning/today",tokenA).get("needsMoreWords").asBoolean());
        finishDailyWithKnown(batch); var old=batchDetails(batch).stream().map(json::valueToTree).toList();
        var more=startDay(); assertEquals(5,more.get("newCount").asInt()); assertEquals(5,more.get("totalCount").asInt());
        assertEquals(old,batchDetails(batch).subList(0,4).stream().map(json::valueToTree).toList());
        finishDailyWithKnown(batch); assertFalse(get("/api/learning/today",tokenA).get("canAddMore").asBoolean());
    }

    @Test void allNewPreferenceFallsBackToReviewsEvenWhenReviewPreferenceIsZero() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); makeDue(wordIds.get(1),wordIds.get(2));
        allocation(2,0); var task=startDay(); assertEquals(2,task.get("reviewCount").asInt());
        assertEquals(0,task.get("newCount").asInt()); assertEquals(0,task.get("reviewLimit").asInt());
        finishDailyWithKnown(task.get("batchId").asLong()); assertTrue(insightSummary("DAILY").at("/goal/reached").asBoolean());
    }

    @Test void partiallyMissingNewWordsUseExtraReviewsWithoutDiscardingAvailableNewWords() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        makeDue(wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5)); allocation(2,2);
        var task=startDay(); assertEquals(4,task.get("totalCount").asInt());
        assertEquals(1,task.get("newCount").asInt()); assertEquals(3,task.get("reviewCount").asInt());
        assertTrue(batchDetails(task.get("batchId").asLong()).stream().anyMatch(i->i.getWordId().equals(wordIds.get(1))));
    }

    @Test void partiallyMissingReviewsUseExtraNewWordsWithoutDiscardingAvailableReviews() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        makeDue(wordIds.get(5)); allocation(2,2); var task=startDay();
        assertEquals(4,task.get("totalCount").asInt()); assertEquals(3,task.get("newCount").asInt());
        assertEquals(1,task.get("reviewCount").asInt());
        assertEquals(wordIds.get(5),batchDetails(task.get("batchId").asLong()).getFirst().getWordId());
    }

    @Test void allReviewPreferenceFallsBackToNewWordsEvenWhenNewPreferenceIsZero() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2)); allocation(0,2); var task=startDay();
        assertEquals(2,task.get("newCount").asInt()); assertEquals(0,task.get("reviewCount").asInt());
        assertEquals(0,task.get("newWordTarget").asInt());
        finishDailyWithKnown(task.get("batchId").asLong()); assertTrue(insightSummary("DAILY").at("/goal/reached").asBoolean());
    }

    @Test void insufficientWordsPromptAddingToPlanUntilOriginalDailyGoalIsReached() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3)); makeDue(wordIds.get(3)); allocation(10,10);
        var task=startDay(); long batch=task.get("batchId").asLong();
        assertEquals(3,task.get("totalCount").asInt()); assertEquals(2,task.get("newCount").asInt());
        assertEquals(1,task.get("reviewCount").asInt()); assertEquals(20,task.get("dailyTarget").asInt());
        var initial=insightSummary("DAILY").get("goal");
        assertEquals(20,initial.get("remainingCount").asInt()); assertFalse(initial.get("reached").asBoolean());
        finishDailyWithKnown(batch); var goal=insightSummary("DAILY").get("goal");
        assertFalse(goal.get("reached").asBoolean()); assertEquals(17,goal.get("remainingCount").asInt());
        assertEquals(20,goal.get("dailyTarget").asInt());
        assertEquals(20,get("/api/learning/settings",tokenA).get("dailyTarget").asInt());
        var today=get("/api/learning/today",tokenA); assertFalse(today.get("canAddMore").asBoolean()); assertTrue(today.get("needsMoreWords").asBoolean());
        String memory=stateSnapshot(),queue=batchSnapshot(batch); int writes=probe.writes.get();
        assertEquals(today.get("task"),startDay()); assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch));
        assertEquals(writes,probe.writes.get());
        joinPlan(wordIds.get(4)); assertFalse(get("/api/learning/today",tokenA).get("needsMoreWords").asBoolean());
        assertEquals(4,startDay().get("totalCount").asInt()); finishDailyWithKnown(batch);
        assertFalse(insightSummary("DAILY").at("/goal/reached").asBoolean());
        assertEquals(16,insightSummary("DAILY").at("/goal/remainingCount").asInt());
        var added=new ArrayList<Long>();
        for(int index=0;index<16;index++) {
            long id=word(prefix+"_complete_goal_"+index); associate(systemBook,id,100+index); added.add(id);
        }
        joinPlan(added.toArray(Long[]::new)); assertEquals(20,startDay().get("totalCount").asInt()); finishDailyWithKnown(batch);
        assertTrue(insightSummary("DAILY").at("/goal/reached").asBoolean());
        assertEquals(0,insightSummary("DAILY").at("/goal/remainingCount").asInt());
    }

    @Test void exhaustedPlanPromptsAddingWordsWithoutStartingOrInventingACompletedGoal() throws Exception {
        allocation(10,10); long before=countBatches(); int writes=probe.writes.get();
        var today=get("/api/learning/today",tokenA); assertTrue(today.get("needsMoreWords").asBoolean());
        assertFalse(today.get("canAddMore").asBoolean()); assertTrue(today.get("task").isNull());
        assertFalse(insightSummary("DAILY").at("/goal/reached").asBoolean());
        var response=call("POST","/api/learning/today/start",null,tokenA);
        assertEquals(409,response.statusCode()); assertEquals("NO_DAILY_WORDS",json.readTree(response.body()).get("code").asText());
        assertTrue(json.readTree(response.body()).get("message").asText().contains("请添加单词"));
        assertEquals(before,countBatches()); assertEquals(writes,probe.writes.get());
    }

    @Test void fallbackExcludesFuturePausedRemovedAndOtherUsersWords() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4),wordIds.get(5));
        makeDue(wordIds.get(2),wordIds.get(4),wordIds.get(5));
        var future=state(a,wordIds.get(2)); future.setNextReviewAt(statisticsDate().plusDays(1).atStartOfDay()); states.update(future);
        planManagementStatus("PAUSED",wordIds.get(3)); planManagementStatus("REMOVED",wordIds.get(4));
        allocation(5,5); String memory=stateSnapshot(); var task=startDay(); long batch=task.get("batchId").asLong();
        assertEquals(List.of(wordIds.get(5),wordIds.get(1)),batchDetails(batch).stream().map(LearningBatchItem::getWordId).toList());
        assertEquals(memory,stateSnapshot()); finishDailyWithKnown(batch);
        var today=get("/api/learning/today",tokenA); assertTrue(today.get("needsMoreWords").asBoolean());
        assertEquals(0,today.get("unassignedCount").asInt()); assertFalse(today.get("canAddMore").asBoolean());
        assertEquals(0,get("/api/learning/today",tokenB).get("unassignedCount").asInt());
    }

    @Test void allocatedContinuationCanExceed200WordsWithFallbackAndNoDuplicateScheduling() throws Exception {
        var selected=new ArrayList<Long>();
        for(int index=0;index<205;index++) {
            long id=word(prefix+"_unlimited_"+index); associate(systemBook,id,100+index); selected.add(id);
        }
        joinPlan(selected.subList(0,100).toArray(Long[]::new)); joinPlan(selected.subList(100,200).toArray(Long[]::new));
        joinPlan(selected.subList(200,205).toArray(Long[]::new)); allocation(50,50);
        long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch);
        assertEquals(100,batchDetails(batch).size()); assertTrue(get("/api/learning/today",tokenA).get("canAddMore").asBoolean());
        var old=batchDetails(batch).stream().map(json::valueToTree).toList();
        assertEquals(200,startDay().get("totalCount").asInt()); finishDailyWithKnown(batch);
        assertEquals(205,startDay().get("totalCount").asInt()); finishDailyWithKnown(batch);
        assertEquals(old,batchDetails(batch).subList(0,100).stream().map(json::valueToTree).toList());
        assertEquals(205,batchDetails(batch).stream().map(LearningBatchItem::getWordId).distinct().count());
        assertStatistics(statisticsDaily(statisticsDate()),205,205,205,0,0);
        var goal=insightSummary("DAILY").get("goal"); assertTrue(goal.get("reached").asBoolean());
        assertEquals(100,goal.get("dailyTarget").asInt()); assertEquals(0,goal.get("remainingCount").asInt());
        assertTrue(get("/api/learning/today",tokenA).get("needsMoreWords").asBoolean());
        assertEquals(1,get("/api/learning/daily-tasks?status=COMPLETED",tokenA).get("total").asInt());
    }

    @Test void allocatedConcurrentContinuationUsesOneRoundAndKeepsOriginalPreferences() throws Exception {
        joinPlan(wordIds.get(1),wordIds.get(2),wordIds.get(3),wordIds.get(4)); allocation(1,1);
        long batch=startDay().get("batchId").asLong(); finishDailyWithKnown(batch); allocation(2,2);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            var second=pool.submit(()->{gate.await();return call("POST","/api/learning/today/start",null,tokenA);});
            gate.countDown(); var one=first.get(15,TimeUnit.SECONDS); var two=second.get(15,TimeUnit.SECONDS);
            assertEquals(200,one.statusCode()); assertEquals(200,two.statusCode()); assertEquals(data(one),data(two));
        }
        var continued=get(dailyPath(batch),tokenA); assertEquals(4,continued.get("totalCount").asInt());
        assertEquals(1,continued.get("newWordTarget").asInt()); assertEquals(1,continued.get("reviewLimit").asInt());
        assertEquals(4,batchDetails(batch).stream().map(LearningBatchItem::getWordId).distinct().count());
        finishDailyWithKnown(batch); advanceDays(1);
        assertEquals(2,startDay().get("newWordTarget").asInt());
    }

    @Test void expansionZeroReviewQuotaAndLegacyTargetUpdatesRemainCompatibleAndPrivate() throws Exception {
        joinPlan(wordIds.get(0),wordIds.get(1)); var old=state(a,wordIds.get(0)); old.setFirstLearnedAt(statisticsDate().minusDays(1).atStartOfDay()); old.setNextReviewAt(statisticsDate().atStartOfDay()); states.update(old);
        allocation(1,0); var task=startDay(); assertEquals(0,task.get("reviewCount").asInt()); assertEquals(1,task.get("newCount").asInt());
        assertEquals(400,call("PUT","/api/learning/settings/allocation",Map.of("newWordTarget",0,"reviewLimit",0),tokenA).statusCode());
        assertEquals(400,call("PUT","/api/learning/settings/allocation",Map.of("newWordTarget",150,"reviewLimit",100),tokenA).statusCode());
        assertTrue(get("/api/learning/settings",tokenB).get("newWordTarget").isNull());
        var legacy=call("PUT","/api/learning/settings",Map.of("dailyTarget",20),tokenA); assertEquals(200,legacy.statusCode());
        assertTrue(data(legacy).get("newWordTarget").isNull()); assertTrue(data(legacy).get("reviewLimit").isNull());
        assertEquals(1,get(dailyPath(task.get("batchId").asLong()),tokenA).get("newWordTarget").asInt());
    }

    @Test void expansionWrongReasonsShowActualSpellingAndIndependentCopyingCreatesNoIssue() throws Exception {
        followPlan(wordIds.get(1)); long follow=followCreate().get("batchId").asLong();
        followAnswer(follow,followNext(follow),"wrong"); followAnswer(follow,followNext(follow),spelling(1));
        assertEquals(0,consolidationIssues("?category=ALL").get("total").asInt());
        assertEquals(0,consolidationIssues("?reason=PENDING_VERIFICATION").get("total").asInt());
        assertEquals(0,consolidationIssues("?category=VERIFICATION").get("total").asInt());
        var summary=get("/api/learning/consolidation/summary",tokenA); assertEquals(4,summary.size());
        for(var reason:summary) { assertNotEquals("PENDING_VERIFICATION",reason.get("reason").asText()); assertEquals(0,reason.get("wordCount").asInt()); }
        assertEquals(0,weakWords("DAILY").get("total").asInt()); assertEquals(404,call("POST","/api/learning/weak-words/practice",Map.of(),tokenA).statusCode());
        assertEquals(2,followStatistics().get("answerCount").asInt());
        assertEquals(409,call("POST","/api/learning/consolidation/batches",Map.of("requestId",requestId(),"category","ALL"),tokenA).statusCode());
        joinPlan(wordIds.get(2)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"DICTATION");
        var q=trainingQuestion(batch,null); train(batch,q,"misspelled",null);
        var errors=consolidationIssues("?reason=SPELLING_ERROR"); assertEquals(1,errors.get("total").asInt());
        assertEquals("misspelled",errors.get("records").get(0).get("lastInput").asText());
        assertEquals(0,get("/api/learning/consolidation/issues?category=ALL",tokenB).get("total").asInt());
    }

    @Test void independentCopyingAfterRealErrorsPreservesReasonsAndFamiliarityEvidence() throws Exception {
        int index=1;
        for(String mode:List.of("DICTATION","LISTEN_DICTATION","RECOGNITION")) {
            joinPlan(wordIds.get(index)); long batch=startDay().get("batchId").asLong(); studyMode(batch,mode);
            var q=trainingQuestion(batch,null); train(batch,q,mode.equals("RECOGNITION")?null:"wrong",mode.equals("RECOGNITION")?3:null);
            index++; advanceDays(1);
        }
        var errors=consolidationIssues("?category=ALL"); var weak=weakWords("DAILY"); String memory=stateSnapshot();
        long batch=followCreate().get("batchId").asLong(); finishFollow(batch);
        assertEquals(errors,consolidationIssues("?category=ALL")); assertEquals(weak,weakWords("DAILY")); assertEquals(memory,stateSnapshot());
        long focus=focusCreate(Map.of("requestId",requestId(),"reason","SPELLING_ERROR","mode","FOLLOW_TYPING")).get("batchId").asLong();
        var q=focusNext(focus); String word=q.get("word").asText(); focusAnswer(focus,q,"wrong",null); focusAnswer(focus,focusNext(focus),word,null);
        assertEquals(errors,consolidationIssues("?category=ALL"));
    }

    @Test void independentFollowingAcrossDaysDoesNotReplaceEarlierWeakWordAssessments() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); train(daily,trainingQuestion(daily,null),null,3);
        var weak=weakWords("DAILY"); long batch=followCreate().get("batchId").asLong(); finishFollow(batch);
        advanceDays(1); batch=followCreate().get("batchId").asLong(); finishFollow(batch);
        assertEquals(weak,weakWords("DAILY")); assertEquals(0,statisticsDaily(statisticsDate()).get("completedCount").asInt());
        assertEquals(1,consolidationIssues("?reason=UNRECOGNIZED").get("total").asInt());
        long next=startDay().get("batchId").asLong(); studyMode(next,"RECOGNITION"); train(next,trainingQuestion(next,null),null,1);
        assertEquals(0,weakWords("DAILY").get("total").asInt()); assertEquals(0,consolidationIssues("?category=ALL").get("total").asInt());
    }

    @Test void expansionRevealsUnknownAndAttemptLimitAreSeparateAndCorrectionsAreHonored() throws Exception {
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); var q=trainingQuestion(batch,null); train(batch,q,null,null);
        assertEquals(0,consolidationIssues("?reason=ANSWER_REVEALED").get("total").asInt());
        var answer=train(batch,trainingQuestion(batch,null),null,3);
        assertEquals(1,consolidationIssues("?reason=UNRECOGNIZED").get("total").asInt());
        correction(batch,q.get("itemId").asLong(),answer.get("reviewLogId").asLong(),1,requestId());
        assertEquals(0,consolidationIssues("?reason=UNRECOGNIZED").get("total").asInt());
        correction(batch,q.get("itemId").asLong(),answer.get("reviewLogId").asLong(),3,requestId());
        studyMode(batch,"DICTATION"); for(int index=0;index<5;index++) train(batch,trainingQuestion(batch,null),"wrong",null);
        assertEquals(1,consolidationIssues("?reason=ATTEMPT_LIMIT").get("total").asInt());
        assertEquals(5,consolidationIssues("?reason=SPELLING_ERROR").get("records").get(0).get("occurrenceCount").asInt());
    }

    @Test void expansionFocusedPracticeResolvesSpellingWithoutChangingDailyGoalOrSchedule() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); studyMode(daily,"DICTATION"); train(daily,trainingQuestion(daily,null),"wrong",null);
        String memory=stateSnapshot(),queue=batchSnapshot(daily); var oldStats=get("/api/learning/statistics/training",tokenA);
        String key=requestId(); var body=Map.of("requestId",key,"reason","SPELLING_ERROR","mode","DICTATION");
        var created=focusCreate(body); long batch=created.get("batchId").asLong(); assertEquals("PRACTICE",created.get("taskType").asText());
        assertEquals(batch,focusCreate(body).get("batchId").asLong()); assertEquals(0,created.get("dailyTarget").asInt());
        var q=focusNext(batch); assertFalse(q.hasNonNull("word")); var correct=focusAnswer(batch,q,spelling(1),null);
        assertEquals("RECALLED",correct.get("completionReason").asText()); assertFalse(correct.get("affectsSchedule").asBoolean());
        assertEquals(0,consolidationIssues("?reason=SPELLING_ERROR").get("total").asInt());
        assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(daily)); assertEquals(oldStats,get("/api/learning/statistics/training",tokenA));
        assertEquals(1,get("/api/learning/consolidation/batches?status=COMPLETED",tokenA).get("total").asInt());
        assertEquals(409,call("POST","/api/learning/consolidation/batches",Map.of("requestId",key,"mode","FOLLOW_TYPING"),tokenA).statusCode());
    }

    @Test void expansionFocusedPracticePersistsHintsAndRejectsCrossUserAndCrossFamilyRequests() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); studyMode(daily,"DICTATION"); train(daily,trainingQuestion(daily,null),"wrong",null);
        long batch=focusCreate(Map.of("requestId",requestId(),"wordIds",List.of(wordIds.get(1)),"mode","DICTATION")).get("batchId").asLong();
        var q=focusNext(batch); focusAnswer(batch,q,null,null);
        assertEquals(1,consolidationIssues("?reason=ANSWER_REVEALED").get("total").asInt());
        var resumed=focusNext(batch); assertTrue(resumed.get("answerRevealed").asBoolean());
        assertEquals(spelling(1),resumed.get("word").asText()); advanceDays(1); resumed=focusNext(batch);
        var answer=focusAnswer(batch,resumed,spelling(1),null); assertEquals("ASSISTED",answer.get("completionReason").asText());
        assertEquals(1,consolidationIssues("?reason=SPELLING_ERROR").get("total").asInt());
        assertEquals(404,call("GET",focusPath(batch),null,tokenB).statusCode());
        assertEquals(409,call("POST",dailyPath(batch)+"/training/next",null,tokenA).statusCode());
        assertEquals(409,call("POST",focusPath(daily)+"/next",null,tokenA).statusCode());
        assertEquals(409,call("POST","/api/learning/consolidation/batches",Map.of("requestId",requestId(),"wordIds",List.of(wordIds.get(2))),tokenA).statusCode());
    }

    @Test void expansionConsolidationCreationAndAnswersRollbackTogetherOnPersistenceFailure() throws Exception {
        joinPlan(wordIds.get(1)); long daily=startDay().get("batchId").asLong(); studyMode(daily,"DICTATION"); train(daily,trainingQuestion(daily,null),"wrong",null);
        long count=countBatches(); probe.failAfterMapper="com.wordtrail.learning.mapper.LearningBatchItemMapper";
        try { assertEquals(500,call("POST","/api/learning/consolidation/batches",Map.of("requestId",requestId()),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; } assertEquals(count,countBatches());
        long batch=focusCreate(Map.of("requestId",requestId())).get("batchId").asLong(); var q=focusNext(batch); String before=batchSnapshot(batch);
        probe.failAfterMapper="com.wordtrail.learning.mapper.WordTrainingAttemptMapper";
        try { assertEquals(500,call("PUT",focusPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",trainingBody(q,spelling(1),null,requestId()),tokenA).statusCode()); }
        finally { probe.failAfterMapper=null; } assertEquals(before,batchSnapshot(batch));
    }

    @Test void expansionAudioPreferencesAndListeningQuestionsHideSpellingAndMeaning() throws Exception {
        var preference=call("PUT","/api/learning/settings/pronunciation",Map.of("autoPlay",true,"accent","UK","playbackRate",0.75,"repeatCount",2),tokenA);
        assertEquals(200,preference.statusCode(),preference.body()); assertEquals("UK",data(preference).get("pronunciation").get("accent").asText());
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"LISTEN_DICTATION"); var q=trainingQuestion(batch,null);
        for(String field:List.of("word","wordId","phonetic","translation")) assertFalse(q.hasNonNull(field),field);
        assertFalse(q.get("hintUsed").asBoolean()); assertEquals(0.75,q.get("pronunciation").get("playbackRate").asDouble(),0.001);
        var sound=download(q.get("audioUrl").asText(),tokenA); assertEquals(200,sound.statusCode());
        assertEquals("audio/mpeg",sound.headers().firstValue("Content-Type").orElse("")); assertEquals(spelling(1),audioFixture.lastWord);
        assertEquals(com.wordtrail.learning.dto.PronunciationRequest.Accent.UK,audioFixture.lastAccent);
        assertEquals(404,download(q.get("audioUrl").asText(),tokenB).statusCode());
        var answer=train(batch,q,spelling(1),null); assertEquals("RECALLED",answer.get("completionReason").asText()); assertFalse(answer.get("hintUsed").asBoolean());
        assertEquals(409,download(q.get("audioUrl").asText(),tokenA).statusCode());
        assertEquals("US",get("/api/learning/settings",tokenB).get("pronunciation").get("accent").asText());
    }

    @Test void expansionListeningRevealRemainsAssistedAndInvalidPreferencesAreRejected() throws Exception {
        for(var body:List.of(Map.of("autoPlay",true,"accent","US","playbackRate",2,"repeatCount",1),Map.of("autoPlay",true,"accent","UK","playbackRate",1,"repeatCount",0)))
            assertEquals(400,call("PUT","/api/learning/settings/pronunciation",body,tokenA).statusCode());
        joinPlan(wordIds.get(1)); long batch=startDay().get("batchId").asLong(); studyMode(batch,"LISTEN_DICTATION"); train(batch,trainingQuestion(batch,null),null,null);
        var revealed=trainingQuestion(batch,null); assertEquals(spelling(1),revealed.get("word").asText());
        assertEquals("ASSISTED",train(batch,revealed,spelling(1),null).get("completionReason").asText());
        assertEquals(0,state(a,wordIds.get(1)).getReviewLevel());
        assertEquals(1,trainingModeStatistics("LISTEN_DICTATION").get("hintedAnswerCount").asInt());
    }

    @Test void expansionPersonalExportIncludesOwnLearningAndNotesWithChecksumsButNoCredentials() throws Exception {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource); long sentence=0;
        try {
            jdbc.update("INSERT INTO sentence_question(english_text,reference_translation,explanation,difficulty,source,status) VALUES(?,?,?,?,?,?)",prefix+" private export sentence","参考译文","解析",1,"fixture","ACTIVE");
            sentence=jdbc.queryForObject("SELECT id FROM sentence_question WHERE english_text=?",Long.class,prefix+" private export sentence");
            jdbc.update("INSERT INTO user_sentence_note(user_id,sentence_id,content,version,updated_at) VALUES(?,?,?,?,?)",a,sentence,"本人私有笔记",1,contextTime());
            jdbc.update("INSERT INTO user_sentence_note(user_id,sentence_id,content,version,updated_at) VALUES(?,?,?,?,?)",b,sentence,"其他用户秘密笔记",1,contextTime());
            jdbc.update("INSERT INTO user_sentence_favorite(user_id,sentence_id,created_at) VALUES(?,?,?)",a,sentence,contextTime());
            followPlan(wordIds.get(1)); long batch=followCreate().get("batchId").asLong(); followAnswer(batch,followNext(batch),spelling(1));
            String memory=stateSnapshot(),queue=batchSnapshot(batch);
            var response=download("/api/me/learning-data/export?userId="+b,tokenA); assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains(".zip"));
            var contents=new LinkedHashMap<String,byte[]>();
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.body()),StandardCharsets.UTF_8)) {
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) contents.put(entry.getName(),zip.readAllBytes());
            }
            var manifest=json.readTree(contents.get("manifest.json")); assertTrue(manifest.get("complete").asBoolean()); assertEquals(1,manifest.get("version").asInt());
            for(var entry:contents.entrySet()) {
                if(entry.getKey().equals("manifest.json")) continue;
                var expected=manifest.get("entries").get(entry.getKey());
                assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue())),expected.get("sha256").asText());
                assertEquals(json.readTree(entry.getValue()).size(),expected.get("rows").asInt());
                String text=new String(entry.getValue(),StandardCharsets.UTF_8); assertFalse(text.contains("password_hash")); assertFalse(text.contains("token_hash")); assertFalse(text.contains("auth_version"));
                assertFalse(text.contains("其他用户秘密笔记"));
            }
            assertEquals(a,json.readTree(contents.get("profile.json")).get(0).get("id").asLong());
            assertEquals("本人私有笔记",json.readTree(contents.get("sentence_notes.json")).get(0).get("content").asText());
            assertEquals(2,json.readTree(contents.get("word_plan.json")).size());
            assertEquals(memory,stateSnapshot()); assertEquals(queue,batchSnapshot(batch)); assertEquals(401,download("/api/me/learning-data/export",null).statusCode());
        } finally {
            if(sentence!=0) { jdbc.update("DELETE FROM user_sentence_note WHERE sentence_id=?",sentence); jdbc.update("DELETE FROM user_sentence_favorite WHERE sentence_id=?",sentence); jdbc.update("DELETE FROM sentence_question WHERE id=?",sentence); }
        }
    }
    private java.time.LocalDateTime contextTime() { return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")); }

    private String dictationPath(long batch) { return "/api/dictation/batches/"+batch; }
    private JsonNode createDictation(Object request) throws Exception {
        var response=call("POST","/api/dictation/batches",request,tokenA);
        assertEquals(201,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private JsonNode dictationNext(long batch) throws Exception {
        var response=call("POST",dictationPath(batch)+"/next",null,tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private Map<String,Object> dictationBody(JsonNode question,String input,String request) {
        var body=new LinkedHashMap<String,Object>(); body.put("action",input==null?"REVEAL":"ANSWER");
        if(input!=null) body.put("input",input);
        body.put("attemptNo",question.get("nextAttemptNo").asInt()); body.put("presentationId",question.get("presentationId").asText()); body.put("requestId",request); return body;
    }
    private JsonNode dictate(long batch,JsonNode question,String input,String key) throws Exception {
        var response=call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",dictationBody(question,input,key),tokenA);
        assertEquals(200,response.statusCode(),response.body()); return json.readTree(response.body()).get("data");
    }
    private JsonNode dictate(long batch,String input) throws Exception {return dictate(batch,dictationNext(batch),input,requestId());}

    @Test void dictationDefaultSelectionRequiresActiveStartedPlansAndKeepsWordIdentityAcrossBooks() throws Exception {
        joinPlan(wordIds.get(1));
        long batch=createDictation(Map.of("count",100)).get("batchId").asLong();
        assertEquals(List.of(wordIds.get(0)),batchDetails(batch).stream().map(LearningBatchItem::getWordId).toList());
        assertEquals("DICTATION",batches.selectOneById(batch).getPracticeMode()); assertNull(batchDetails(batch).get(0).getResult());
        assertEquals(409,call("POST","/api/dictation/batches",Map.of("wordIds",List.of(wordIds.get(1))),tokenA).statusCode());
        personalBookWords("POST",emptyBook,wordIds.get(0));
        long second=createDictation(Map.of("bookId",emptyBook,"wordIds",List.of(wordIds.get(0),wordIds.get(0)))).get("batchId").asLong();
        assertEquals(1,batchDetails(second).size());
        planManagementStatus("PAUSED",wordIds.get(0));
        assertEquals(409,call("POST","/api/dictation/batches",Map.of(),tokenA).statusCode());
        assertEquals("IN_PROGRESS",get(dictationPath(batch),tokenA).get("status").asText());
    }

    @Test void dictationQuestionMasksTargetAndDoesNotRevealEnglishOrPhoneticUntilSubmitting() throws Exception {
        long word=wordIds.get(0); dictionaryTranslation(word,"n. "+spelling(0)+"的用法及中文释义");
        long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch);
        assertFalse(question.toString().contains(spelling(0))); assertFalse(question.has("word")); assertFalse(question.has("phonetic"));
        assertTrue(question.get("prompt").asText().contains("[目标词]"));
        assertEquals(question,dictationNext(batch)); assertEquals(0,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
        var result=dictate(batch,question,spelling(0).toUpperCase(Locale.ROOT),requestId());
        assertEquals("CORRECT",result.get("verdict").asText()); assertEquals(spelling(0),result.get("expectedWord").asText());
        assertTrue(result.get("translationGroups").isArray()); assertFalse(result.get("affectsSchedule").asBoolean());
        assertEquals("COMPLETED",result.get("progress").get("status").asText());
        assertEquals(1,result.get("progress").get("passedCount").asInt());
    }

    @Test void dictationWrongAndRevealedAnswersEndTheQuestionAndCannotBeChanged() throws Exception {
        for(String input:new String[]{"misspelling",null}) {
            long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch);
            var saved=dictate(batch,question,input,requestId());
            assertEquals("DONE",saved.get("itemStatus").asText());
            assertEquals(input==null?"REVEALED":"INCORRECT",saved.get("verdict").asText());
            assertEquals("LIMIT_REACHED",saved.get("completionReason").asText());
            assertEquals("COMPLETED",saved.at("/progress/status").asText());
            assertEquals(1,saved.at("/progress/completedCount").asInt()); assertEquals(1,saved.at("/progress/attemptCount").asInt());
            assertEquals(0,saved.at("/progress/passedCount").asInt()); assertEquals(1,saved.at("/progress/needsPracticeCount").asInt());
            assertEquals(0.0,saved.at("/progress/accuracy").asDouble());
            String before=batchSnapshot(batch);
            var changed=call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",
                dictationBody(question,spelling(0),requestId()),tokenA);
            assertEquals(409,changed.statusCode()); assertEquals("DICTATION_ALREADY_ANSWERED",json.readTree(changed.body()).get("code").asText());
            assertEquals(409,call("POST",dictationPath(batch)+"/next",null,tokenA).statusCode());
            assertEquals(before,batchSnapshot(batch)); assertEquals(1,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
        }
        assertEquals(1,get("/api/dictation/mistakes",tokenA).get("total").asInt());
    }

    @Test void dictationUsesFixedOrderAndNeverRequeuesWrongOrRevealedQuestions() throws Exception {
        for(int i=1;i<5;i++) learn(a,wordIds.get(i),1);
        long batch=createDictation(Map.of("wordIds",wordIds.subList(0,5))).get("batchId").asLong(); var entries=batchDetails(batch);
        for(int i=0;i<5;i++) {
            var question=dictationNext(batch); assertEquals(entries.get(i).getId().longValue(),question.get("itemId").asLong());
            assertEquals(1,question.get("attemptLimit").asInt()); assertEquals(1,question.get("nextAttemptNo").asInt());
            assertEquals(question,dictationNext(batch));
            dictate(batch,question,i==0?null:i==1?"wrong":spelling(i),requestId());
        }
        var task=get(dictationPath(batch),tokenA); assertEquals("COMPLETED",task.get("status").asText());
        assertEquals(5,task.get("attemptCount").asInt()); assertEquals(3,task.get("passedCount").asInt());
        assertEquals(2,task.get("needsPracticeCount").asInt()); assertEquals(0.6,task.get("accuracy").asDouble());
        assertTrue(batchDetails(batch).stream().allMatch(i->i.getAnswerCount()==1 && i.getRelearnCount()==0 && "DONE".equals(i.getLearningStatus())));
    }

    @Test void dictationRequiresNewQuestionAndRejectsOldPageReplaysAndOutOfOrderItems() throws Exception {
        learn(a,wordIds.get(1),1);
        long batch=createDictation(Map.of("wordIds",wordIds.subList(0,2))).get("batchId").asLong();
        var question=dictationNext(batch); var body=dictationBody(question,null,requestId());
        long other=batchDetails(batch).get(1).getId();
        assertEquals(409,call("PUT",dictationPath(batch)+"/items/"+other+"/answer",body,tokenA).statusCode());
        dictate(batch,question,null,requestId());
        assertEquals(409,call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",dictationBody(question,spelling(0),requestId()),tokenA).statusCode());
        assertEquals(1,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
    }

    @Test void dictationMistakeRetestStartsAFreshSingleAttemptAndPreservesOriginalScore() throws Exception {
        long original=createDictation(Map.of()).get("batchId").asLong(); dictate(original,"wrong");
        var originalResult=get(dictationPath(original)+"/result/words",tokenA);
        String snapshot=batchSnapshot(original); assertEquals(1,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        planManagementStatus("REMOVED",wordIds.get(0)); String memory=stateSnapshot();
        long retry=createDictation(Map.of("source","MISTAKES")).get("batchId").asLong(); assertNotEquals(original,retry);
        assertFalse(batchDetails(retry).getFirst().getDictationHadFailure());
        assertEquals(memory,stateSnapshot()); var passed=dictate(retry,spelling(0));
        assertEquals("COMPLETED",passed.at("/progress/status").asText()); assertEquals(1,passed.at("/progress/passedCount").asInt());
        assertEquals(1,passed.at("/progress/attemptCount").asInt()); assertEquals(1.0,passed.at("/progress/accuracy").asDouble());
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(snapshot,batchSnapshot(original)); assertEquals(originalResult,get(dictationPath(original)+"/result/words",tokenA));
        assertEquals(0,get(dictationPath(original),tokenA).get("passedCount").asInt());
        assertEquals("REMOVED",state(a,wordIds.get(0)).getPlanStatus()); assertEquals(memory,stateSnapshot());
    }

    @Test void dictationSubmissionRetryAndConcurrentRequestsOnlyRecordOneAttempt() throws Exception {
        long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch); var key=requestId();
        var body=dictationBody(question,"bad",key); var gate=new CountDownLatch(1);
        String path=dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer";
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return call("PUT",path,body,tokenA);});
            var two=pool.submit(()->{gate.await();return call("PUT",path,body,tokenA);}); gate.countDown();
            var first=one.get(15,TimeUnit.SECONDS); var second=two.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),first.body()); assertEquals(200,second.statusCode(),second.body());
            assertEquals(json.readTree(first.body()).get("data"),json.readTree(second.body()).get("data"));
        }
        assertEquals(1,get(dictationPath(batch),tokenA).get("attemptCount").asInt());
        assertEquals(409,call("PUT",path,dictationBody(question,spelling(0),key),tokenA).statusCode());
        assertEquals(1,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
    }

    @Test void dictationUsesSnapshotsAndContinuesAcrossDaysWithoutMovingDailySchedules() throws Exception {
        long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch); String original=spelling(0),memory=stateSnapshot();
        var word=words.selectOneById(wordIds.get(0)); word.setWord(prefix+"changed"); word.setTranslation("修改后的释义"); words.update(word);
        advanceDays(2); var answer=dictate(batch,question,original,requestId());
        assertEquals("CORRECT",answer.get("verdict").asText()); assertEquals(original,answer.get("expectedWord").asText());
        assertEquals("测试",answer.get("translation").asText()); assertEquals(memory,stateSnapshot());
        var history=get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("records").get(0);
        assertEquals(statisticsDate().toString(),history.get("learningDate").asText());
    }

    @Test void dictationIsExcludedFromRecognitionListsWeakWordsHistoriesAndAllOldStatistics() throws Exception {
        var practiceBefore=insightSummary("PRACTICE"); var dailyBefore=insightSummary("DAILY");
        var weakBefore=weakWords("PRACTICE"); var historyBefore=get("/api/learning-plan/words/"+wordIds.get(0)+"/history",tokenA);
        String memory=stateSnapshot(); long recognitionEvents=reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a)));
        long batch=createDictation(Map.of()).get("batchId").asLong(); dictate(batch,null);
        assertEquals(404,call("GET","/api/learning-batches",null,tokenA).statusCode());
        assertEquals(practiceBefore,insightSummary("PRACTICE")); assertEquals(dailyBefore,insightSummary("DAILY"));
        assertEquals(weakBefore,weakWords("PRACTICE")); assertEquals(historyBefore,get("/api/learning-plan/words/"+wordIds.get(0)+"/history",tokenA));
        assertEquals(memory,stateSnapshot());
        assertEquals(recognitionEvents,reviewLogs.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))));
        for(String suffix:List.of("","/items/"+itemId(batch),"/result","/result/words")) {
            assertEquals(404,call("GET",batchPath(batch)+suffix,null,tokenA).statusCode());
        }
    }

    @Test void dictationRejectsOtherUsersAndWrongPracticeTypesAndUnqualifiedManualSelections() throws Exception {
        long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch);
        for(String suffix:List.of("","/result/words")) assertEquals(404,call("GET",dictationPath(batch)+suffix,null,tokenB).statusCode());
        assertEquals(404,call("POST",dictationPath(batch)+"/next",null,tokenB).statusCode());
        assertEquals(404,call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",dictationBody(question,null,requestId()),tokenB).statusCode());
        assertEquals(401,call("POST","/api/dictation/batches",Map.of(),null).statusCode());
        assertEquals(0,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenB).get("total").asInt());
        long practice=historicalPractice(wordIds.get(1)); assertEquals(409,call("POST",dictationPath(practice)+"/next",null,tokenA).statusCode());
        assertEquals(404,call("POST","/api/dictation/batches",Map.of("bookId",otherBook),tokenA).statusCode());
        assertEquals(409,call("POST","/api/dictation/batches",Map.of("wordIds",List.of(wordIds.get(0),wordIds.get(1))),tokenA).statusCode());
    }

    @Test void dictationValidatesClientInputAndDoesNotAcceptForgedVerdictsOrTargets() throws Exception {
        for(Object request:List.of(Map.of("count",0),Map.of("count",101),Map.of("source","ALL"),Map.of("user_id",b),Map.of("wordIds",List.of(0)),Map.of("wordIds",List.of())))
            assertTrue(Set.of(400,409).contains(call("POST","/api/dictation/batches",request,tokenA).statusCode()));
        long batch=createDictation(Map.of()).get("batchId").asLong(); var q=dictationNext(batch);
        for(var field:Map.of("result",1,"expectedWord","fake","user_id",b,"attemptNo",2).entrySet()) {
            var body=new LinkedHashMap<>(dictationBody(q,spelling(0),requestId())); body.put(field.getKey(),field.getValue());
            assertEquals(400,call("PUT",dictationPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",body,tokenA).statusCode());
        }
        assertEquals(0,get(dictationPath(batch),tokenA).get("attemptCount").asInt());
    }

    @Test void dictationTransactionsRollBackCreationQueueAndEventsTogether() throws Exception {
        long before=countBatches(); probe.failAfterMapper="com.wordtrail.learning.mapper.LearningBatchItemMapper";
        try {assertEquals(500,call("POST","/api/dictation/batches",Map.of(),tokenA).statusCode());}
        finally {probe.failAfterMapper=null;} assertEquals(before,countBatches());
        long batch=createDictation(Map.of()).get("batchId").asLong(); var q=dictationNext(batch); String state=batchSnapshot(batch);
        for(String mapper:List.of("LearningBatchItemMapper","DictationMapper","LearningBatchMapper")) {
            probe.failAfterMapper="com.wordtrail.learning.mapper."+mapper;
            try {assertEquals(500,call("PUT",dictationPath(batch)+"/items/"+q.get("itemId").asLong()+"/answer",dictationBody(q,null,requestId()),tokenA).statusCode());}
            finally {probe.failAfterMapper=null;}
            assertEquals(state,batchSnapshot(batch)); assertEquals(0,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
        }
    }

    @Test void dictationHistoryMistakesAndResultsKeepEachTestsSingleAttempt() throws Exception {
        assertEquals(0,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        long failed=createDictation(Map.of()).get("batchId").asLong(); dictate(failed,null);
        assertEquals(1,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        long passed=createDictation(Map.of("source","MISTAKES")).get("batchId").asLong(); dictate(passed,spelling(0));
        var result=get(dictationPath(passed)+"/result/words",tokenA);
        assertEquals(1,result.get("total").asInt()); assertEquals(1,result.get("records").get(0).get("attemptCount").asInt());
        var history=get("/api/dictation/words/"+wordIds.get(0)+"/history?size=1",tokenA);
        assertEquals(2,history.get("total").asInt()); assertEquals(1,history.get("records").get(0).get("attemptNo").asInt());
        assertEquals(passed,history.get("records").get(0).get("batchId").asLong());
        assertEquals("DICTATION",history.get("records").get(0).get("practiceMode").asText());
        assertFalse(history.get("records").get(0).get("affectsSchedule").asBoolean());
        var older=get("/api/dictation/words/"+wordIds.get(0)+"/history?size=1&page=2",tokenA).get("records").get(0);
        assertEquals(failed,older.get("batchId").asLong()); assertEquals("REVEALED",older.get("verdict").asText());
        assertEquals(2,get("/api/dictation/batches?status=COMPLETED",tokenA).get("total").asInt());
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(0,get("/api/dictation/words/"+wordIds.get(0)+"/history?page=2147483647",tokenA).get("records").size());
    }

    @Test void dictationDefersAnswersScoresAndHistoryUntilTheWholeTestEnds() throws Exception {
        learn(a,wordIds.get(1),1); learn(a,wordIds.get(2),1);
        long batch=createDictation(Map.of("wordIds",wordIds.subList(0,3))).get("batchId").asLong();
        var question=dictationNext(batch); String key=requestId(); var body=dictationBody(question,spelling(0),key);
        var first=dictate(batch,question,spelling(0),key);
        assertEquals("DONE",first.get("itemStatus").asText()); assertEquals(1,first.at("/progress/completedCount").asInt());
        for(String field:List.of("expectedWord","translation","verdict","correctStreak","completionReason")) assertFalse(first.has(field),field);
        assertTrue(first.get("translationGroups").isEmpty());
        var task=get(dictationPath(batch),tokenA); assertTrue(task.get("singleAttempt").asBoolean()); assertTrue(task.get("canContinue").asBoolean());
        for(String field:List.of("passedCount","needsPracticeCount","accuracy")) assertTrue(task.get(field).isNull(),field);
        assertEquals(0,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(409,call("GET",dictationPath(batch)+"/result/words",null,tokenA).statusCode());
        assertEquals(first,data(call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",body,tokenA)));
        var wrong=dictate(batch,"wrong"); assertFalse(wrong.has("verdict")); assertFalse(wrong.has("expectedWord"));
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        var done=dictate(batch,null); assertEquals("COMPLETED",done.at("/progress/status").asText());
        assertEquals(1,done.at("/progress/passedCount").asInt()); assertEquals(2,done.at("/progress/needsPracticeCount").asInt());
        assertEquals(1.0/3,done.at("/progress/accuracy").asDouble(),0.000001);
        assertFalse(done.at("/progress/canContinue").asBoolean());
        var results=get(dictationPath(batch)+"/result/words",tokenA).get("records");
        assertEquals(List.of("CORRECT","INCORRECT","REVEALED"),java.util.stream.StreamSupport.stream(results.spliterator(),false).map(i->i.get("lastVerdict").asText()).toList());
        assertEquals(2,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(1,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        var replay=data(call("PUT",dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer",body,tokenA));
        assertEquals(spelling(0),replay.get("expectedWord").asText()); assertEquals("CORRECT",replay.get("verdict").asText());
        assertEquals(3,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
    }

    @Test void unfinishedRetestsDoNotClearOrRevealCompletedMistakes() throws Exception {
        learn(a,wordIds.get(1),1);
        long first=createDictation(Map.of("wordIds",List.of(wordIds.get(0)))).get("batchId").asLong(); dictate(first,"wrong");
        long second=createDictation(Map.of("wordIds",List.of(wordIds.get(1)))).get("batchId").asLong(); dictate(second,"wrong");
        var original=get("/api/dictation/mistakes",tokenA); assertEquals(2,original.get("total").asInt());
        var history=get("/api/dictation/words/"+wordIds.get(1)+"/history",tokenA);
        long retry=createDictation(Map.of("source","MISTAKES")).get("batchId").asLong();
        assertEquals(wordIds.get(1),batchDetails(retry).getFirst().getWordId());
        dictate(retry,spelling(1));
        assertEquals(original,get("/api/dictation/mistakes",tokenA));
        assertEquals(history,get("/api/dictation/words/"+wordIds.get(1)+"/history",tokenA));
        dictate(retry,spelling(0)); assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals("INCORRECT",get(dictationPath(first)+"/result/words",tokenA).get("records").get(0).get("lastVerdict").asText());
        assertEquals("INCORRECT",get(dictationPath(second)+"/result/words",tokenA).get("records").get(0).get("lastVerdict").asText());
    }

    @Test void conflictingConcurrentTestSubmissionsOnlyAcceptOneImmutableAnswer() throws Exception {
        long batch=createDictation(Map.of()).get("batchId").asLong(); var question=dictationNext(batch);
        String path=dictationPath(batch)+"/items/"+question.get("itemId").asLong()+"/answer";
        var correct=dictationBody(question,spelling(0),requestId()); var wrong=dictationBody(question,"wrong",requestId());
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{gate.await();return call("PUT",path,correct,tokenA);});
            var second=pool.submit(()->{gate.await();return call("PUT",path,wrong,tokenA);}); gate.countDown();
            var one=first.get(15,TimeUnit.SECONDS); var two=second.get(15,TimeUnit.SECONDS);
            assertEquals(Set.of(200,409),Set.of(one.statusCode(),two.statusCode()));
            var accepted=one.statusCode()==200?one:two; var request=one.statusCode()==200?correct:wrong;
            assertEquals(data(accepted),data(call("PUT",path,request,tokenA)));
        }
        assertEquals(1,dictationLogs.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batch))));
        assertEquals(1,get(dictationPath(batch),tokenA).get("completedCount").asInt());
    }

    @Test void revealConsumesTheQuestionButOnlyDisclosesTheRequestedAnswer() throws Exception {
        learn(a,wordIds.get(1),1);
        long batch=createDictation(Map.of("wordIds",wordIds.subList(0,2))).get("batchId").asLong();
        var reveal=dictate(batch,null); assertEquals("DONE",reveal.get("itemStatus").asText());
        assertEquals(spelling(0),reveal.get("expectedWord").asText()); assertEquals("REVEALED",reveal.get("verdict").asText());
        assertTrue(reveal.at("/progress/passedCount").isNull()); assertTrue(reveal.at("/progress/accuracy").isNull());
        assertFalse(reveal.toString().contains(spelling(1)));
        assertEquals(409,call("GET",dictationPath(batch)+"/result/words",null,tokenA).statusCode());
        assertEquals(0,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        dictate(batch,spelling(1)); assertEquals(0.5,get(dictationPath(batch),tokenA).get("accuracy").asDouble());
    }

    private long legacyDictation(boolean completed) throws Exception {
        long batchId=createDictation(Map.of()).get("batchId").asLong();
        var batch=batches.selectOneById(batchId); batch.setStudyMode("RECOGNITION");
        var now=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0);
        if(completed) { batch.setStatus("COMPLETED"); batch.setCompletedAt(now); }
        batches.update(batch);
        var item=batchDetails(batchId).getFirst(); item.setAnswerCount(completed?3:2); item.setRelearnCount(completed?2:1);
        item.setDictationHadFailure(true); item.setDictationCorrectStreak(completed?2:1); item.setAnsweredAt(now);
        item.setLearningStatus(completed?"DONE":"RELEARNING"); item.setCompletionReason(completed?"RECALLED":null);
        item.setCompletedAt(completed?now:null); details.updateByQuery(item,false,QueryWrapper.create().where(column("id").eq(item.getId())));
        for(int attempt=1;attempt<=item.getAnswerCount();attempt++) {
            var log=new DictationReviewLog(); log.setUserId(a); log.setWordId(item.getWordId()); log.setBatchId(batchId);
            log.setBatchItemId(item.getId()); log.setRequestId(requestId()); log.setPresentationId(requestId());
            log.setEventType("ANSWER"); log.setInputText(attempt==1?"wrong":spelling(0)); log.setVerdict(attempt==1?"INCORRECT":"CORRECT");
            log.setAttemptNo(attempt); log.setLearningDate(now.toLocalDate()); log.setAnsweredAt(now);
            log.setCorrectStreakBefore(Math.max(0,attempt-2)); log.setCorrectStreakAfter(attempt-1); log.setHadFailureAfter(true);
            log.setItemStatusAfter(attempt==3?"DONE":"RELEARNING"); log.setCompletionReasonAfter(attempt==3?"RECALLED":null);
            log.setAffectsSchedule(false); dictationLogs.insertSelective(log);
        }
        return batchId;
    }

    @Test void unfinishedLegacyDictationIsReadOnlyAndCanSeedANewSingleAttemptTest() throws Exception {
        long legacy=legacyDictation(false); String snapshot=batchSnapshot(legacy),memory=stateSnapshot();
        int writes=probe.writes.get(); var task=get(dictationPath(legacy),tokenA);
        assertFalse(task.get("singleAttempt").asBoolean()); assertFalse(task.get("canContinue").asBoolean());
        assertTrue(task.get("nextItemId").isNull()); assertEquals(6,task.get("attemptLimit").asInt()); assertEquals(2,task.get("attemptCount").asInt());
        assertTrue(task.get("accuracy").isNull());
        var next=call("POST",dictationPath(legacy)+"/next",null,tokenA);
        assertEquals(409,next.statusCode()); assertEquals("DICTATION_LEGACY_READ_ONLY",json.readTree(next.body()).get("code").asText());
        var body=Map.of("action","ANSWER","input",spelling(0),"attemptNo",1,"presentationId",requestId(),"requestId",requestId());
        assertEquals(409,call("PUT",dictationPath(legacy)+"/items/"+itemId(legacy)+"/answer",body,tokenA).statusCode());
        var result=get(dictationPath(legacy)+"/result/words",tokenA).get("records").get(0);
        assertEquals(2,result.get("attemptCount").asInt()); assertEquals("RELEARNING",result.get("learningStatus").asText());
        assertEquals(2,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        assertEquals(1,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(writes,probe.writes.get()); assertEquals(snapshot,batchSnapshot(legacy)); assertEquals(memory,stateSnapshot());
        long retry=createDictation(Map.of("source","MISTAKES")).get("batchId").asLong(); dictate(retry,spelling(0));
        assertEquals(1,get(dictationPath(retry),tokenA).get("attemptCount").asInt());
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(snapshot,batchSnapshot(legacy)); assertEquals(memory,stateSnapshot());
    }

    @Test void completedLegacyDictationKeepsOriginalAttemptsAndNeverClaimsSingleAttemptAccuracy() throws Exception {
        long legacy=legacyDictation(true); String snapshot=batchSnapshot(legacy); int writes=probe.writes.get();
        var task=get(dictationPath(legacy),tokenA); assertEquals("COMPLETED",task.get("status").asText());
        assertFalse(task.get("singleAttempt").asBoolean()); assertFalse(task.get("canContinue").asBoolean());
        assertEquals(3,task.get("attemptCount").asInt()); assertEquals(1,task.get("passedCount").asInt()); assertTrue(task.get("accuracy").isNull());
        assertEquals(3,get(dictationPath(legacy)+"/result/words",tokenA).get("records").get(0).get("attemptCount").asInt());
        assertEquals(3,get("/api/dictation/words/"+wordIds.get(0)+"/history",tokenA).get("total").asInt());
        assertFalse(get("/api/dictation/batches?status=COMPLETED",tokenA).get("records").get(0).get("singleAttempt").asBoolean());
        assertEquals(0,get("/api/dictation/mistakes",tokenA).get("total").asInt());
        assertEquals(writes,probe.writes.get()); assertEquals(snapshot,batchSnapshot(legacy));
    }

}
