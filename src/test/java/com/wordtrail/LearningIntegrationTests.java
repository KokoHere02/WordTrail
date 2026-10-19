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

        @Override public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (invocation.getMethod().getName().equals("query")
                    && (statement.startsWith("com.wordtrail.book.mapper.")
                        || statement.startsWith("com.wordtrail.learning.mapper."))) {
                reads.incrementAndGet();
            }
            if (invocation.getMethod().getName().equals("update")) { writes.incrementAndGet(); }
            Object result = invocation.proceed();
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
    }

    static class StudyClock extends Clock {
        private volatile Instant now = Instant.now();
        void reset() { now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
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
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> userIds = new ArrayList<>(), bookIds = new ArrayList<>(), wordIds = new ArrayList<>();
    private long a, b, systemBook, ownBook, otherBook, emptyBook;
    private String tokenA, tokenB, prefix;

    @Autowired LearningIntegrationTests(Environment environment, ObjectMapper json, JwtTokenService jwt,
            UserMapper users, WordMapper words, WordBookMapper books, WordBookItemMapper bookItems,
            LearningBatchMapper batches, LearningBatchItemMapper details, UserWordStateMapper states,
            DatabaseProbe probe, StudyClock clock, PasswordService passwords, RefreshTokenMapper refreshTokens) {
        this.environment = environment; this.json = json; this.jwt = jwt;
        this.users = users; this.words = words; this.books = books; this.bookItems = bookItems;
        this.batches = batches; this.details = details; this.states = states; this.probe = probe;
        this.clock = clock;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
    }

    @BeforeEach void seed() {
        probe.failAfterMapper = null;
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
        if (!userIds.isEmpty()) {
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
    private HttpResponse<String> create(Object body) throws Exception {
        return call("POST", "/api/learning-batches", body, tokenA);
    }
    private JsonNode created(Object body) throws Exception {
        var response = create(body);
        assertEquals(201, response.statusCode(), response.body());
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
    private void assertBatch(JsonNode result, String mode, List<Long> expected) {
        long batchId = result.get("batchId").asLong();
        assertEquals(systemBook, result.get("bookId").asLong());
        assertEquals(mode, result.get("selectionType").asText());
        assertEquals("IN_PROGRESS", result.get("status").asText());
        assertEquals(expected.size(), result.get("wordCount").asInt());
        var batch = batches.selectOneById(batchId);
        assertEquals(a, batch.getUserId());
        assertNull(batch.getCompletedAt());
        var entries = batchDetails(batchId);
        assertEquals(expected, entries.stream().map(LearningBatchItem::getWordId).toList());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i + 1, entries.get(i).getSortOrder());
            assertNull(entries.get(i).getResult());
            assertNull(entries.get(i).getAnsweredAt());
        }
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
        for (long bookId : List.of(Long.MAX_VALUE, otherBook)) {
            var response = create(Map.of("bookId", bookId, "selectionType", "BOOK"));
            assertEquals(404, response.statusCode());
            assertEquals(missing.body(), response.body());
        }
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
        assertEquals(6, records.get(0).size());
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

    @Test void invalidQueryAndBodyParametersReturn400() throws Exception {
        for (String query : List.of("page=0", "page=-1", "page=abc", "size=0", "size=101", "size=", "page=")) {
            assertEquals(400, call("GET", "/api/word-books?" + query, null, tokenA).statusCode());
            assertEquals(400, call("GET", wordsPath(systemBook) + "?" + query, null, tokenA).statusCode());
        }
        for (String status : List.of("INVALID", "learned", "")) {
            assertEquals(400, call("GET", wordsPath(systemBook) + "?learningStatus=" + status, null, tokenA).statusCode());
        }
        assertEquals(400, call("GET", wordsPath(-1), null, tokenA).statusCode());
        for (Object body : List.of(Map.of(), Map.of("bookId", -1, "selectionType", "BOOK"),
                Map.of("bookId", systemBook, "selectionType", "INVALID"),
                Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 0),
                Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 101),
                Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 1.5),
                Map.of("bookId", systemBook, "selectionType", 0),
                Map.of("bookId", systemBook, "selectionType", "MANUAL", "wordIds", List.of(1.5)),
                Map.of("bookId", systemBook, "selectionType", "BOOK", "user_id", b))) {
            assertEquals(400, create(body).statusCode());
        }
    }

    @Test void bookSelectionUsesUnlearnedOrderAndNeverChangesState() throws Exception {
        String before = stateSnapshot();
        var first = created(Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 2));
        assertBatch(first, "BOOK", List.of(wordIds.get(1), wordIds.get(2)));
        var second = created(Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 2));
        assertNotEquals(first.get("batchId").asLong(), second.get("batchId").asLong());
        assertBatch(second, "BOOK", List.of(wordIds.get(1), wordIds.get(2)));
        assertEquals(before, stateSnapshot());
    }

    @Test void bookSelectionUsesCurrentUsersOwnState() throws Exception {
        var response = call("POST", "/api/learning-batches",
            Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 2), tokenB);
        assertEquals(201, response.statusCode());
        long batchId = json.readTree(response.body()).at("/data/batchId").asLong();
        assertEquals(b, batches.selectOneById(batchId).getUserId());
        assertEquals(List.of(wordIds.get(2), wordIds.get(0)),
            batchDetails(batchId).stream().map(LearningBatchItem::getWordId).toList());
    }

    @Test void bookSelectionHandlesShortageDefaultAndNoUnlearnedWords() throws Exception {
        assertBatch(created(Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 100)), "BOOK",
            List.of(wordIds.get(1), wordIds.get(2), wordIds.get(3), wordIds.get(4), wordIds.get(5), wordIds.get(6)));
        for (int i = 0; i < 25; i++) { associate(systemBook, word(prefix + "extra" + i), 70 + i); }
        assertEquals(20, created(Map.of("bookId", systemBook, "selectionType", "BOOK")).get("wordCount").asInt());
        long before = countBatches();
        var empty = create(Map.of("bookId", emptyBook, "selectionType", "BOOK"));
        assertEquals(409, empty.statusCode());
        assertEquals("NO_UNLEARNED_WORDS", json.readTree(empty.body()).get("code").asText());
        learn(a, wordIds.get(2), 2); // ownBook contains only this word.
        var allLearned = create(Map.of("bookId", ownBook, "selectionType", "BOOK"));
        assertEquals(409, allLearned.statusCode());
        assertEquals(before + 1, countBatches()); // Only the explicit historical fixture batch was added.
    }

    @Test void manualSelectionDeduplicatesIncludesLearnedWordsAndUsesBookOrder() throws Exception {
        String before = stateSnapshot();
        var result = created(Map.of("bookId", systemBook, "selectionType", "MANUAL",
            "wordIds", List.of(wordIds.get(4), wordIds.get(0), wordIds.get(2), wordIds.get(4))));
        assertBatch(result, "MANUAL", List.of(wordIds.get(2), wordIds.get(0), wordIds.get(4)));
        assertEquals(before, stateSnapshot());
        var repeated = created(Map.of("bookId", systemBook, "selectionType", "MANUAL",
            "wordIds", Collections.nCopies(101, wordIds.get(1))));
        assertBatch(repeated, "MANUAL", List.of(wordIds.get(1)));
    }

    @Test void manualSelectionRejectsWholeRequestForAnyInvalidIdOrCount() throws Exception {
        long before = countBatches();
        var bodies = new ArrayList<Object>();
        bodies.add(Map.of("bookId", systemBook, "selectionType", "MANUAL"));
        for (List<?> ids : List.of(List.of(), List.of(wordIds.get(1), wordIds.get(7)),
                List.of(wordIds.get(1), Long.MAX_VALUE), Arrays.asList(wordIds.get(1), null),
                List.of(-1L), java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList())) {
            bodies.add(Map.of("bookId", systemBook, "selectionType", "MANUAL", "wordIds", ids));
        }
        bodies.add(Map.of("bookId", systemBook, "selectionType", "MANUAL",
            "wordIds", List.of(wordIds.get(1)), "count", 20));
        bodies.add(Map.of("bookId", systemBook, "selectionType", "BOOK", "wordIds", List.of(wordIds.get(1))));
        for (Object body : bodies) { assertEquals(400, create(body).statusCode()); }
        assertEquals(before, countBatches());
    }

    @Test void batchWriteFailureRollsBackWholeTransaction() throws Exception {
        rollbackAfterWrite("com.wordtrail.learning.mapper.LearningBatchMapper");
    }

    @Test void detailWriteFailureRollsBackBatchAndAllDetails() throws Exception {
        rollbackAfterWrite("com.wordtrail.learning.mapper.LearningBatchItemMapper");
    }


    private long manualBatch(Long... selected) throws Exception {
        return created(Map.of("bookId", systemBook, "selectionType", "MANUAL", "wordIds", List.of(selected)))
            .get("batchId").asLong();
    }
    private String batchPath(long batchId) { return "/api/learning-batches/" + batchId; }
    private String itemPath(long batchId, long itemId) { return batchPath(batchId) + "/items/" + itemId; }
    private JsonNode answer(long batchId, long itemId, int result) throws Exception {
        var response = call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", result), tokenA);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }
    private UserWordState state(long userId, long wordId) {
        return states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").eq(wordId)));
    }
    private long itemId(long batchId) { return batchDetails(batchId).getFirst().getId(); }

    @Test void studyRoundHidesTranslationsUntilAnsweredAndCompletesOnLastWord() throws Exception {
        long batchId = manualBatch(wordIds.get(3), wordIds.get(1), wordIds.get(2));
        var entries = batchDetails(batchId);
        var progress = get(batchPath(batchId), tokenA);
        assertEquals(3, progress.get("wordCount").asInt());
        assertEquals(0, progress.get("answeredCount").asInt());
        assertEquals(entries.getFirst().getId(), progress.get("nextItemId").asLong());
        assertEquals("IN_PROGRESS", progress.get("status").asText());

        for (int i = 0; i < entries.size(); i++) {
            var before = get(itemPath(batchId, entries.get(i).getId()), tokenA);
            assertFalse(before.has("translation"));
            assertFalse(before.get("answered").asBoolean());
            assertTrue(before.get("result").isNull());
            assertEquals(i + 1, before.get("sortOrder").asInt());
            clock.advanceSeconds(2);
            var saved = answer(batchId, entries.get(i).getId(), i + 1);
            assertEquals("测试", saved.get("translation").asText());
            assertEquals(i + 1, saved.get("result").asInt());
            assertEquals(i + 1, saved.get("answeredCount").asInt());
            assertEquals(3, saved.get("wordCount").asInt());
            var revealed = get(itemPath(batchId, entries.get(i).getId()), tokenA);
            assertTrue(revealed.get("answered").asBoolean());
            assertEquals(before.get("word").asText(), revealed.get("word").asText());
            assertEquals("/test/", revealed.get("phonetic").asText());
            assertEquals("测试", revealed.get("translation").asText());
            if (i + 1 < entries.size()) {
                assertEquals("IN_PROGRESS", saved.get("status").asText());
                assertEquals(entries.get(i + 1).getId(), saved.get("nextItemId").asLong());
            } else {
                assertEquals("COMPLETED", saved.get("status").asText());
                assertTrue(saved.get("nextItemId").isNull());
            }
        }
        var done = get(batchPath(batchId), tokenA);
        assertEquals(3, done.get("answeredCount").asInt());
        assertTrue(done.get("nextItemId").isNull());
        assertNotNull(batches.selectOneById(batchId).getCompletedAt());
    }

    @Test void resumeAlwaysLocatesFirstUnansweredAndAnsweredItemsRestoreTranslation() throws Exception {
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2));
        var entries = batchDetails(batchId);
        answer(batchId, entries.getFirst().getId(), 2);
        for (int attempt = 0; attempt < 2; attempt++) {
            var progress = get(batchPath(batchId), tokenA);
            assertEquals(entries.get(1).getId(), progress.get("nextItemId").asLong());
            var next = get(itemPath(batchId, progress.get("nextItemId").asLong()), tokenA);
            assertFalse(next.has("translation"));
            assertEquals("测试", get(itemPath(batchId, entries.getFirst().getId()), tokenA)
                .get("translation").asText());
        }
    }

    @Test void rememberedWrongCorrectsCompletedAnswerWithoutIncreasingCount() throws Exception {
        long batchId = manualBatch(wordIds.get(2));
        long itemId = itemId(batchId);
        var first = answer(batchId, itemId, 1);
        var originalBatch = batches.selectOneById(batchId);
        var originalState = state(a, wordIds.get(2));
        clock.advanceSeconds(10);
        var corrected = answer(batchId, itemId, 3);
        assertEquals(3, corrected.get("result").asInt());
        assertEquals("测试", corrected.get("translation").asText());
        assertEquals("COMPLETED", corrected.get("status").asText());
        assertEquals(1, corrected.get("answeredCount").asInt());
        assertTrue(corrected.get("nextItemId").isNull());
        assertEquals(1, batchDetails(batchId).size());
        assertEquals(originalBatch.getCompletedAt(), batches.selectOneById(batchId).getCompletedAt());
        var current = state(a, wordIds.get(2));
        assertEquals(originalState.getFirstLearnedAt(), current.getFirstLearnedAt());
        assertEquals(3, current.getLastResult());
        assertEquals(itemId, current.getLastBatchItemId());
        assertTrue(current.getLastLearnedAt().isAfter(originalState.getLastLearnedAt()));
        assertNotEquals(first.get("answeredAt").asText(), corrected.get("answeredAt").asText());
    }

    @Test void identicalRetryDoesNotChangeAnyAnswerStateOrCompletionTime() throws Exception {
        long batchId = manualBatch(wordIds.get(2));
        long itemId = itemId(batchId);
        var first = answer(batchId, itemId, 1);
        String state = stateSnapshot();
        var original = details.selectOneById(itemId);
        var completed = batches.selectOneById(batchId).getCompletedAt();
        clock.advanceSeconds(60);
        var repeated = answer(batchId, itemId, 1);
        assertEquals(first, repeated);
        assertEquals(original.getAnsweredAt(), details.selectOneById(itemId).getAnsweredAt());
        assertEquals(state, stateSnapshot());
        assertEquals(completed, batches.selectOneById(batchId).getCompletedAt());
    }

    @Test void cannotSkipFirstUnansweredButMayCorrectEarlierAnsweredItem() throws Exception {
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2), wordIds.get(3));
        var entries = batchDetails(batchId);
        String state = stateSnapshot();
        var skipped = call("PUT", itemPath(batchId, entries.get(1).getId()) + "/answer", Map.of("result", 1), tokenA);
        assertEquals(409, skipped.statusCode());
        assertEquals("ANSWER_OUT_OF_ORDER", json.readTree(skipped.body()).get("code").asText());
        assertNull(details.selectOneById(entries.get(1).getId()).getResult());
        assertEquals(state, stateSnapshot());
        answer(batchId, entries.getFirst().getId(), 1);
        answer(batchId, entries.get(1).getId(), 2);
        clock.advanceSeconds(5);
        var corrected = answer(batchId, entries.getFirst().getId(), 3);
        assertEquals(2, corrected.get("answeredCount").asInt());
        assertEquals(entries.get(2).getId(), corrected.get("nextItemId").asLong());
    }

    @Test void studyEndpointsRejectOtherUsersAndMixedBatchItems() throws Exception {
        long own = manualBatch(wordIds.get(2));
        long ownItem = itemId(own);
        long another = manualBatch(wordIds.get(3));
        long anotherItem = itemId(another);
        long foreign = batches.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(b))).getId();
        long foreignItem = itemId(foreign);
        for (String path : List.of(batchPath(own), itemPath(own, ownItem))) {
            assertEquals(401, call("GET", path, null, null).statusCode());
        }
        assertEquals(401, call("PUT", itemPath(own, ownItem) + "/answer", Map.of("result", 1), null).statusCode());
        var missing = call("GET", batchPath(Long.MAX_VALUE), null, tokenA);
        var forbidden = call("GET", batchPath(foreign), null, tokenA);
        assertEquals(404, missing.statusCode()); assertEquals(missing.body(), forbidden.body());
        assertEquals(404, call("GET", itemPath(foreign, foreignItem), null, tokenA).statusCode());
        assertEquals(404, call("PUT", itemPath(foreign, foreignItem) + "/answer", Map.of("result", 1), tokenA).statusCode());
        for (long mixed : List.of(foreignItem, anotherItem, Long.MAX_VALUE)) {
            assertEquals(404, call("GET", itemPath(own, mixed), null, tokenA).statusCode());
            assertEquals(404, call("PUT", itemPath(own, mixed) + "/answer", Map.of("result", 1), tokenA).statusCode());
        }
    }

    @Test void answerValidationRejectsMissingNullInvalidAndClientIdentityFields() throws Exception {
        long batchId = manualBatch(wordIds.get(2));
        long itemId = itemId(batchId);
        var nullResult = new HashMap<String, Object>(); nullResult.put("result", null);
        for (Object body : List.of(Map.of(), nullResult, Map.of("result", 0), Map.of("result", 4),
                Map.of("result", -1), Map.of("result", 1.5), Map.of("result", 1, "word_id", wordIds.get(0)),
                Map.of("result", 1, "user_id", b))) {
            assertEquals(400, call("PUT", itemPath(batchId, itemId) + "/answer", body, tokenA).statusCode());
        }
        assertEquals(400, call("GET", batchPath(-1), null, tokenA).statusCode());
        assertEquals(400, call("GET", itemPath(batchId, 0), null, tokenA).statusCode());
        assertEquals(400, call("PUT", itemPath(batchId, -1) + "/answer", Map.of("result", 1), tokenA).statusCode());
        assertNull(details.selectOneById(itemId).getResult());
    }

    @Test void abandonedBatchAllowsProgressButRejectsReadingAndChangingItems() throws Exception {
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2));
        long itemId = itemId(batchId);
        answer(batchId, itemId, 1);
        var abandoned = new LearningBatch(); abandoned.setId(batchId); abandoned.setStatus("ABANDONED");
        batches.update(abandoned);
        String state = stateSnapshot();
        assertEquals("ABANDONED", get(batchPath(batchId), tokenA).get("status").asText());
        assertEquals(409, call("GET", itemPath(batchId, itemId), null, tokenA).statusCode());
        var response = call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", 3), tokenA);
        assertEquals(409, response.statusCode());
        assertEquals("BATCH_ABANDONED", json.readTree(response.body()).get("code").asText());
        assertEquals(1, details.selectOneById(itemId).getResult());
        assertEquals(state, stateSnapshot());
    }

    @Test void historicalCorrectionDoesNotOverwriteNewerBatchStateAndFirstTimeIsPreserved() throws Exception {
        long first = manualBatch(wordIds.get(2)), firstItem = itemId(first);
        answer(first, firstItem, 1);
        var initial = state(a, wordIds.get(2));
        clock.advanceSeconds(10);
        long second = manualBatch(wordIds.get(2)), secondItem = itemId(second);
        answer(second, secondItem, 2);
        var newer = state(a, wordIds.get(2));
        assertEquals(initial.getFirstLearnedAt(), newer.getFirstLearnedAt());
        assertEquals(secondItem, newer.getLastBatchItemId());
        String snapshot = stateSnapshot();
        clock.advanceSeconds(10);
        answer(first, firstItem, 3);
        assertEquals(3, details.selectOneById(firstItem).getResult());
        assertEquals(snapshot, stateSnapshot());
        clock.advanceSeconds(10);
        answer(second, secondItem, 1);
        var latest = state(a, wordIds.get(2));
        assertEquals(initial.getFirstLearnedAt(), latest.getFirstLearnedAt());
        assertEquals(1, latest.getLastResult());
        assertEquals(secondItem, latest.getLastBatchItemId());
        assertTrue(latest.getLastLearnedAt().isAfter(newer.getLastLearnedAt()));
    }

    @Test void firstAnswerToOlderCreatedBatchCanBecomeLatestLearningEvent() throws Exception {
        long older = manualBatch(wordIds.get(2)), olderItem = itemId(older);
        long newer = manualBatch(wordIds.get(2)), newerItem = itemId(newer);
        answer(newer, newerItem, 1);
        var firstTime = state(a, wordIds.get(2)).getFirstLearnedAt();
        clock.advanceSeconds(10);
        answer(older, olderItem, 2);
        assertEquals(olderItem, state(a, wordIds.get(2)).getLastBatchItemId());
        assertEquals(firstTime, state(a, wordIds.get(2)).getFirstLearnedAt());
        String snapshot = stateSnapshot();
        clock.advanceSeconds(10);
        answer(newer, newerItem, 3);
        assertEquals(snapshot, stateSnapshot());
    }

    @Test void answeringSameWordForDifferentUsersDoesNotMixTheirStates() throws Exception {
        long batchA = manualBatch(wordIds.get(2)), itemA = itemId(batchA);
        var made = call("POST", "/api/learning-batches",
            Map.of("bookId", systemBook, "selectionType", "MANUAL", "wordIds", List.of(wordIds.get(2))), tokenB);
        assertEquals(201, made.statusCode());
        long batchB = json.readTree(made.body()).at("/data/batchId").asLong(), itemB = itemId(batchB);
        answer(batchA, itemA, 1);
        assertEquals(200, call("PUT", itemPath(batchB, itemB) + "/answer", Map.of("result", 3), tokenB).statusCode());
        assertEquals(1, state(a, wordIds.get(2)).getLastResult());
        assertEquals(3, state(b, wordIds.get(2)).getLastResult());
        assertEquals(itemA, state(a, wordIds.get(2)).getLastBatchItemId());
        assertEquals(itemB, state(b, wordIds.get(2)).getLastBatchItemId());
    }

    @Test void concurrentIdenticalAnswersCreateOneStateAndOneCompletedBatch() throws Exception {
        long batchId = manualBatch(wordIds.get(2)), itemId = itemId(batchId);
        var responses = concurrentAnswers(batchId, itemId, 1, batchId, itemId, 1);
        assertEquals(200, responses.get(0).statusCode()); assertEquals(200, responses.get(1).statusCode());
        assertEquals(json.readTree(responses.get(0).body()), json.readTree(responses.get(1).body()));
        assertEquals(1, states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))
            .and(column("word_id").eq(wordIds.get(2)))));
        assertEquals(1, batchDetails(batchId).size());
        assertEquals("COMPLETED", batches.selectOneById(batchId).getStatus());
        assertEquals(1, get(batchPath(batchId), tokenA).get("answeredCount").asInt());
    }

    @Test void concurrentFirstAnswersAcrossBatchesKeepOneConsistentLatestState() throws Exception {
        long first = manualBatch(wordIds.get(2)), firstItem = itemId(first);
        long second = manualBatch(wordIds.get(2)), secondItem = itemId(second);
        var responses = concurrentAnswers(first, firstItem, 1, second, secondItem, 3);
        assertEquals(200, responses.get(0).statusCode()); assertEquals(200, responses.get(1).statusCode());
        assertEquals(1, states.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(a))
            .and(column("word_id").eq(wordIds.get(2)))));
        var state = state(a, wordIds.get(2));
        assertTrue(Set.of(firstItem, secondItem).contains(state.getLastBatchItemId()));
        assertEquals(details.selectOneById(state.getLastBatchItemId()).getResult(), state.getLastResult());
        assertEquals("COMPLETED", batches.selectOneById(first).getStatus());
        assertEquals("COMPLETED", batches.selectOneById(second).getStatus());
    }

    private List<HttpResponse<String>> concurrentAnswers(long batchA, long itemA, int resultA,
                                                          long batchB, long itemB, int resultB) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return call("PUT", itemPath(batchA, itemA) + "/answer",
                Map.of("result", resultA), tokenA); });
            var second = pool.submit(() -> { gate.await(); return call("PUT", itemPath(batchB, itemB) + "/answer",
                Map.of("result", resultB), tokenA); });
            gate.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    @Test void answerDetailWriteFailureRollsBackAllLearningChanges() throws Exception {
        rollbackAnswer("com.wordtrail.learning.mapper.LearningBatchItemMapper");
    }

    @Test void answerStateWriteFailureRollsBackDetailAndCompletion() throws Exception {
        rollbackAnswer("com.wordtrail.learning.mapper.UserWordStateMapper");
    }

    @Test void answerCompletionWriteFailureRollsBackBothAnswerAndInsertedState() throws Exception {
        rollbackAnswer("com.wordtrail.learning.mapper.LearningBatchMapper");
    }

    private void rollbackAnswer(String mapper) throws Exception {
        long batchId = manualBatch(wordIds.get(2)), itemId = itemId(batchId);
        String snapshot = stateSnapshot();
        probe.failAfterMapper = mapper;
        try {
            assertEquals(500, call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", 1), tokenA).statusCode());
        } finally { probe.failAfterMapper = null; }
        assertNull(details.selectOneById(itemId).getResult());
        assertNull(details.selectOneById(itemId).getAnsweredAt());
        assertEquals("IN_PROGRESS", batches.selectOneById(batchId).getStatus());
        assertNull(batches.selectOneById(batchId).getCompletedAt());
        assertEquals(snapshot, stateSnapshot());
        assertEquals(0, get(batchPath(batchId), tokenA).get("answeredCount").asInt());
        assertEquals("COMPLETED", answer(batchId, itemId, 1).get("status").asText());
    }

    @Test void correctionStateWriteFailureRestoresOldAnswerAndLatestState() throws Exception {
        long batchId = manualBatch(wordIds.get(2)), itemId = itemId(batchId);
        answer(batchId, itemId, 1);
        String snapshot = stateSnapshot();
        var before = details.selectOneById(itemId);
        var completed = batches.selectOneById(batchId).getCompletedAt();
        clock.advanceSeconds(10);
        probe.failAfterMapper = "com.wordtrail.learning.mapper.UserWordStateMapper";
        try {
            assertEquals(500, call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", 3), tokenA).statusCode());
        } finally { probe.failAfterMapper = null; }
        assertEquals(1, details.selectOneById(itemId).getResult());
        assertEquals(before.getAnsweredAt(), details.selectOneById(itemId).getAnsweredAt());
        assertEquals(snapshot, stateSnapshot());
        assertEquals(completed, batches.selectOneById(batchId).getCompletedAt());
    }

    @Test void completedBatchRejectsPreviouslyUnansweredLegacyItem() throws Exception {
        long batchId = manualBatch(wordIds.get(2)), itemId = itemId(batchId);
        var completed = new LearningBatch(); completed.setId(batchId); completed.setStatus("COMPLETED");
        completed.setCompletedAt(LocalDateTime.now()); batches.update(completed);
        var response = call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", 1), tokenA);
        assertEquals(409, response.statusCode());
        assertEquals("BATCH_ALREADY_COMPLETED", json.readTree(response.body()).get("code").asText());
        assertNull(details.selectOneById(itemId).getResult());
    }

    @Test void configuredWebOriginCanPreflightAnswerPutRequests() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"
            + environment.getRequiredProperty("local.server.port") + "/api/learning-batches/1/items/1/answer"))
            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("access-control-allow-methods").orElseThrow().contains("PUT"));
    }


    private JsonNode listedBatch(JsonNode page, long batchId) {
        for (var record : page.get("records")) {
            if (record.get("batchId").asLong() == batchId) { return record; }
        }
        throw new AssertionError("Expected batch was not present in the list");
    }

    @Test void batchListDefaultsToOwnInProgressAndIncludesAggregateResumeFields() throws Exception {
        long first = manualBatch(wordIds.get(1), wordIds.get(2), wordIds.get(3));
        long second = manualBatch(wordIds.get(4), wordIds.get(5));
        var entries = batchDetails(first);
        answer(first, entries.getFirst().getId(), 2);
        var foreign = call("POST", "/api/learning-batches",
            Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 2), tokenB);
        assertEquals(201, foreign.statusCode());
        long foreignId = json.readTree(foreign.body()).at("/data/batchId").asLong();
        var page = get("/api/learning-batches", tokenA);
        assertEquals(1, page.get("page").asInt()); assertEquals(20, page.get("size").asInt());
        assertEquals(2, page.get("total").asLong());
        var row = listedBatch(page, first);
        assertEquals(10, row.size());
        assertEquals(systemBook, row.get("bookId").asLong());
        assertEquals(prefix + "_system", row.get("bookName").asText());
        assertEquals("MANUAL", row.get("selectionType").asText());
        assertEquals("IN_PROGRESS", row.get("status").asText());
        assertEquals(3, row.get("totalCount").asLong());
        assertEquals(1, row.get("answeredCount").asLong());
        assertEquals(entries.get(1).getId(), row.get("nextItemId").asLong());
        assertFalse(row.get("createdAt").isNull()); assertFalse(row.get("updatedAt").isNull());
        assertEquals(2, listedBatch(page, second).get("totalCount").asLong());
        var foreignPage = get("/api/learning-batches?user_id=" + a, tokenB);
        assertEquals(1, foreignPage.get("total").asLong());
        assertEquals(foreignId, foreignPage.get("records").get(0).get("batchId").asLong());
        assertEquals(404, call("GET", batchPath(first), null, tokenB).statusCode());
    }

    @Test void batchListFiltersStatusesAndOrdersByCreationThenDescendingId() throws Exception {
        long first = manualBatch(wordIds.get(2)), second = manualBatch(wordIds.get(3));
        long third = manualBatch(wordIds.get(4));
        LocalDateTime recent = LocalDateTime.of(2026, 10, 19, 10, 0);
        for (long id : List.of(first, second, third)) {
            var patch = new LearningBatch(); patch.setId(id);
            patch.setCreatedAt(id == second ? recent.minusDays(1) : recent);
            patch.setUpdatedAt(recent.minusDays(2)); batches.update(patch);
        }
        var page = get("/api/learning-batches?size=2", tokenA);
        assertEquals(3, page.get("total").asLong());
        assertEquals(List.of(third, first), java.util.stream.StreamSupport
            .stream(page.get("records").spliterator(), false).map(row -> row.get("batchId").asLong()).toList());
        var nextPage = get("/api/learning-batches?page=2&size=2", tokenA);
        assertEquals(second, nextPage.get("records").get(0).get("batchId").asLong());
        assertTrue(get("/api/learning-batches?page=2147483647", tokenA).get("records").isEmpty());
        answer(first, itemId(first), 1);
        var abandoned = new LearningBatch(); abandoned.setId(third); abandoned.setStatus("ABANDONED");
        batches.update(abandoned);
        var inProgress = get("/api/learning-batches", tokenA);
        assertEquals(1, inProgress.get("total").asLong());
        assertEquals(second, inProgress.get("records").get(0).get("batchId").asLong());
        var completed = listedBatch(get("/api/learning-batches?status=COMPLETED", tokenA), first);
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals(1, completed.get("answeredCount").asLong()); assertTrue(completed.get("nextItemId").isNull());
        var stopped = get("/api/learning-batches?status=ABANDONED", tokenA);
        assertEquals(1, stopped.get("total").asLong());
        assertEquals(third, stopped.get("records").get(0).get("batchId").asLong());
    }

    @Test void batchListAndDetailsNeverWriteOrTouchUpdatedAt() throws Exception {
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2));
        var old = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        var patch = new LearningBatch(); patch.setId(batchId); patch.setUpdatedAt(old); batches.update(patch);
        var createdAt = batches.selectOneById(batchId).getCreatedAt();
        String state = stateSnapshot();
        clock.advanceSeconds(60);
        probe.writes.set(0);
        for (int i = 0; i < 2; i++) {
            var page = get("/api/learning-batches", tokenA);
            assertEquals(old.toString(), listedBatch(page, batchId).get("updatedAt").asText());
            get(batchPath(batchId), tokenA);
            get(itemPath(batchId, itemId(batchId)), tokenA);
        }
        assertEquals(0, probe.writes.get());
        assertEquals(old, batches.selectOneById(batchId).getUpdatedAt());
        assertEquals(createdAt, batches.selectOneById(batchId).getCreatedAt());
        assertEquals(state, stateSnapshot());
    }

    @Test void batchListResumesEachIndependentBatchBySortOrderRatherThanItemId() throws Exception {
        long first = manualBatch(wordIds.get(1), wordIds.get(2), wordIds.get(3));
        long second = manualBatch(wordIds.get(4), wordIds.get(5));
        var firstEntries = batchDetails(first);
        var temporary = new LearningBatchItem(); temporary.setId(firstEntries.get(0).getId()); temporary.setSortOrder(99);
        details.update(temporary);
        var earlier = new LearningBatchItem(); earlier.setId(firstEntries.get(1).getId()); earlier.setSortOrder(1);
        details.update(earlier);
        temporary.setSortOrder(2); details.update(temporary);
        var page = get("/api/learning-batches", tokenA);
        assertEquals(firstEntries.get(1).getId(), listedBatch(page, first).get("nextItemId").asLong());
        answer(first, firstEntries.get(1).getId(), 1);
        var secondEntries = batchDetails(second);
        answer(second, secondEntries.getFirst().getId(), 3);
        var resumed = get("/api/learning-batches", tokenA);
        assertEquals(2, resumed.get("total").asLong());
        assertEquals(firstEntries.get(0).getId(), listedBatch(resumed, first).get("nextItemId").asLong());
        assertEquals(secondEntries.get(1).getId(), listedBatch(resumed, second).get("nextItemId").asLong());
        assertEquals(1, listedBatch(resumed, first).get("answeredCount").asLong());
        assertEquals(1, listedBatch(resumed, second).get("answeredCount").asLong());
        assertEquals(firstEntries.get(0).getId(), get(batchPath(first), tokenA).get("nextItemId").asLong());
        assertEquals(secondEntries.get(1).getId(), get(batchPath(second), tokenA).get("nextItemId").asLong());
    }

    @Test void repeatedAndCorrectedAnswersKeepListCountsAndCompletedBatchLeavesInProgress() throws Exception {
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2));
        var items = batchDetails(batchId);
        var first = answer(batchId, items.getFirst().getId(), 1);
        String snapshot = stateSnapshot();
        clock.advanceSeconds(60);
        assertEquals(first, answer(batchId, items.getFirst().getId(), 1));
        assertEquals(snapshot, stateSnapshot());
        assertEquals(1, listedBatch(get("/api/learning-batches", tokenA), batchId).get("answeredCount").asLong());
        answer(batchId, items.getFirst().getId(), 3);
        assertEquals(1, listedBatch(get("/api/learning-batches", tokenA), batchId).get("answeredCount").asLong());
        answer(batchId, items.get(1).getId(), 2);
        assertEquals(0, get("/api/learning-batches", tokenA).get("total").asLong());
        assertTrue(get("/api/learning-batches", tokenA).get("records").isEmpty());
        var complete = listedBatch(get("/api/learning-batches?status=COMPLETED", tokenA), batchId);
        assertEquals(2, complete.get("totalCount").asLong());
        assertEquals(2, complete.get("answeredCount").asLong());
        assertTrue(complete.get("nextItemId").isNull());
        assertTrue(get(batchPath(batchId), tokenA).get("nextItemId").isNull());
    }

    @Test void failedSaveLeavesListAndDatabaseResumeStateConsistent() throws Exception {
        long batchId = manualBatch(wordIds.get(2));
        long itemId = itemId(batchId);
        var before = listedBatch(get("/api/learning-batches", tokenA), batchId).deepCopy();
        String snapshot = stateSnapshot();
        probe.failAfterMapper = "com.wordtrail.learning.mapper.LearningBatchMapper";
        try {
            assertEquals(500, call("PUT", itemPath(batchId, itemId) + "/answer", Map.of("result", 1), tokenA).statusCode());
        } finally { probe.failAfterMapper = null; }
        assertEquals(before, listedBatch(get("/api/learning-batches", tokenA), batchId));
        assertEquals(0, get(batchPath(batchId), tokenA).get("answeredCount").asLong());
        assertEquals(itemId, get(batchPath(batchId), tokenA).get("nextItemId").asLong());
        assertNull(details.selectOneById(itemId).getResult());
        assertEquals(snapshot, stateSnapshot());
    }

    @Test void batchListAggregationDoesNotAddQueriesPerBatch() throws Exception {
        for (int i = 0; i < 6; i++) { manualBatch(wordIds.get(2)); }
        probe.reads.set(0);
        get("/api/learning-batches?size=1", tokenA);
        int one = probe.reads.get();
        probe.reads.set(0);
        var page = get("/api/learning-batches?size=20", tokenA);
        int many = probe.reads.get();
        assertEquals(6, page.get("total").asLong());
        assertTrue(one > 0);
        assertTrue(many <= one + 1, "Query count must not grow with the number of batches");
    }

    @Test void batchListRequiresLoginAndValidatesStatusAndPagination() throws Exception {
        assertEquals(401, call("GET", "/api/learning-batches", null, null).statusCode());
        for (String query : List.of("page=0", "page=-1", "page=x", "page=", "size=0", "size=101",
                "size=", "status=INVALID", "status=in_progress", "status=0", "status=")) {
            assertEquals(400, call("GET", "/api/learning-batches?" + query, null, tokenA).statusCode());
        }
    }

    @Test void logoutRefreshAndReloginPreserveDatabaseLearningProgress() throws Exception {
        var account = users.selectOneById(a);
        var patch = new UserAccount(); patch.setId(a); patch.setPasswordHash(passwords.hash("password123"));
        users.update(patch);
        var credentials = Map.of("username", account.getUsername(), "password", "password123");
        var login = call("POST", "/api/auth/login", credentials, null);
        assertEquals(200, login.statusCode());
        var pair = json.readTree(login.body()).get("data");
        tokenA = pair.get("accessToken").asText();
        long batchId = manualBatch(wordIds.get(1), wordIds.get(2));
        var items = batchDetails(batchId);
        answer(batchId, items.getFirst().getId(), 1);
        var before = get(batchPath(batchId), tokenA).deepCopy();
        String state = stateSnapshot();
        var refreshed = call("POST", "/api/auth/refresh",
            Map.of("refreshToken", pair.get("refreshToken").asText()), null);
        assertEquals(200, refreshed.statusCode());
        var rotated = json.readTree(refreshed.body()).get("data");
        tokenA = rotated.get("accessToken").asText();
        assertEquals(before, get(batchPath(batchId), tokenA));
        var logout = call("POST", "/api/auth/logout",
            Map.of("refreshToken", rotated.get("refreshToken").asText()), null);
        assertEquals(200, logout.statusCode());
        assertEquals(401, call("POST", "/api/auth/refresh",
            Map.of("refreshToken", rotated.get("refreshToken").asText()), null).statusCode());
        var relogin = call("POST", "/api/auth/login", credentials, null);
        assertEquals(200, relogin.statusCode());
        tokenA = json.readTree(relogin.body()).at("/data/accessToken").asText();
        assertEquals(before, get(batchPath(batchId), tokenA));
        var resumed = listedBatch(get("/api/learning-batches", tokenA), batchId);
        assertEquals(1, resumed.get("answeredCount").asLong());
        assertEquals(items.get(1).getId(), resumed.get("nextItemId").asLong());
        assertFalse(get(itemPath(batchId, items.get(1).getId()), tokenA).has("translation"));
        assertEquals(state, stateSnapshot());
    }

    private long completedBatch(int... results) throws Exception {
        long batchId = manualBatch(wordIds.subList(0, results.length).toArray(Long[]::new));
        var entries = batchDetails(batchId);
        for (int i = 0; i < results.length; i++) { answer(batchId, entries.get(i).getId(), results[i]); }
        return batchId;
    }

    private String batchSnapshot(long batchId) throws Exception {
        return json.writeValueAsString(List.of(batches.selectOneById(batchId), batchDetails(batchId)));
    }

    private JsonNode relearned(long sourceId, Object body) throws Exception {
        var response = call("POST", batchPath(sourceId) + "/relearn", body, tokenA);
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }

    @Test void roundResultsUseCurrentDetailsRatherThanLatestWordState() throws Exception {
        long source = completedBatch(1, 2, 3, 2);
        var entries = batchDetails(source);
        var summary = get(batchPath(source) + "/result", tokenA);
        assertEquals(9, summary.size());
        assertEquals(source, summary.get("batchId").asLong());
        assertEquals(systemBook, summary.get("bookId").asLong());
        assertEquals(prefix + "_system", summary.get("bookName").asText());
        assertEquals(4, summary.get("totalCount").asLong());
        assertEquals(1, summary.get("knownCount").asLong());
        assertEquals(2, summary.get("uncertainCount").asLong());
        assertEquals(1, summary.get("unknownCount").asLong());
        assertEquals(3, summary.get("relearnCount").asLong());
        assertFalse(summary.get("completedAt").isNull());
        long newer = manualBatch(entries.getFirst().getWordId());
        answer(newer, itemId(newer), 3);
        assertEquals(summary, get(batchPath(source) + "/result", tokenA));
        var words = get(batchPath(source) + "/result/words", tokenA);
        assertEquals(1, words.get("records").get(0).get("result").asInt());
        String latest = stateSnapshot();
        clock.advanceSeconds(60);
        answer(source, entries.getFirst().getId(), 3);
        assertEquals(latest, stateSnapshot());
        var corrected = get(batchPath(source) + "/result", tokenA);
        assertEquals(0, corrected.get("knownCount").asLong());
        assertEquals(2, corrected.get("unknownCount").asLong());
        assertEquals(corrected.get("totalCount").asLong(), corrected.get("knownCount").asLong()
            + corrected.get("uncertainCount").asLong() + corrected.get("unknownCount").asLong());
        assertEquals(summary.get("completedAt"), corrected.get("completedAt"));
        assertEquals(3, get(batchPath(source) + "/result/words", tokenA)
            .get("records").get(0).get("result").asInt());
    }

    @Test void resultsAndRelearnRequireCompletedOwnedBatchAndLogin() throws Exception {
        long source = completedBatch(2), pending = manualBatch(wordIds.get(3));
        long abandoned = manualBatch(wordIds.get(4));
        var patch = new LearningBatch(); patch.setId(abandoned); patch.setStatus("ABANDONED");
        batches.update(patch);
        long before = countBatches();
        for (String suffix : List.of("/result", "/result/words", "/relearn")) {
            String method = suffix.equals("/relearn") ? "POST" : "GET";
            assertEquals(401, call(method, batchPath(source) + suffix, null, null).statusCode());
            for (long id : List.of(source, pending, abandoned, Long.MAX_VALUE)) {
                var response = call(method, batchPath(id) + suffix, null, tokenB);
                assertEquals(404, response.statusCode(), response.body());
                assertEquals("BATCH_NOT_FOUND", json.readTree(response.body()).get("code").asText());
            }
            var incomplete = call(method, batchPath(pending) + suffix, null, tokenA);
            assertEquals(409, incomplete.statusCode());
            assertEquals("BATCH_NOT_COMPLETED", json.readTree(incomplete.body()).get("code").asText());
            var stopped = call(method, batchPath(abandoned) + suffix, null, tokenA);
            assertEquals(409, stopped.statusCode());
            assertEquals("BATCH_ABANDONED", json.readTree(stopped.body()).get("code").asText());
            assertEquals(404, call(method, batchPath(Long.MAX_VALUE) + suffix, null, tokenA).statusCode());
            assertEquals(400, call(method, batchPath(0) + suffix, null, tokenA).statusCode());
        }
        assertEquals(before, countBatches());
    }

    @Test void resultWordsPageAndFilterInOriginalBatchOrder() throws Exception {
        long source = completedBatch(1, 2, 3, 2, 1);
        var entries = batchDetails(source);
        // Source order differs from both item IDs and the current book order.
        var reordered = new LearningBatchItem(); reordered.setId(entries.getFirst().getId());
        reordered.setSortOrder(10); details.update(reordered);
        entries = batchDetails(source);
        var all = get(batchPath(source) + "/result/words", tokenA);
        assertEquals(1, all.get("page").asInt()); assertEquals(20, all.get("size").asInt());
        assertEquals(5, all.get("total").asLong());
        for (int i = 0; i < entries.size(); i++) {
            var row = all.get("records").get(i);
            assertEquals(7, row.size());
            assertEquals(entries.get(i).getId(), row.get("itemId").asLong());
            assertEquals(entries.get(i).getWordId(), row.get("wordId").asLong());
            assertEquals(words.selectOneById(entries.get(i).getWordId()).getWord(), row.get("word").asText());
            assertEquals("/test/", row.get("phonetic").asText());
            assertEquals("测试", row.get("translation").asText());
            assertEquals(entries.get(i).getResult(), row.get("result").asInt());
            assertEquals(entries.get(i).getAnsweredAt(), LocalDateTime.parse(row.get("answeredAt").asText()));
        }
        var page = get(batchPath(source) + "/result/words?page=2&size=2", tokenA);
        assertEquals(5, page.get("total").asLong()); assertEquals(2, page.get("records").size());
        assertEquals(entries.get(2).getId(), page.get("records").get(0).get("itemId").asLong());
        for (int result = 1; result <= 3; result++) {
            final int value = result;
            var expected = entries.stream().filter(item -> item.getResult() == value)
                .map(LearningBatchItem::getId).toList();
            var filtered = get(batchPath(source) + "/result/words?result=" + result + "&size=1&page=2", tokenA);
            assertEquals(expected.size(), filtered.get("total").asLong());
            if (expected.size() > 1) {
                assertEquals(expected.get(1), filtered.get("records").get(0).get("itemId").asLong());
            } else { assertTrue(filtered.get("records").isEmpty()); }
        }
        assertTrue(get(batchPath(source) + "/result/words?page=2147483647", tokenA).get("records").isEmpty());
        long known = completedBatch(1);
        var empty = get(batchPath(known) + "/result/words?result=3", tokenA);
        assertEquals(0, empty.get("total").asLong()); assertTrue(empty.get("records").isEmpty());
    }

    @Test void resultReadsAreReadOnlyAndWordJoinsDoNotCauseNPlusOne() throws Exception {
        long source = completedBatch(1, 2, 3, 2, 3);
        String original = batchSnapshot(source), state = stateSnapshot();
        probe.reads.set(0); probe.writes.set(0);
        get(batchPath(source) + "/result/words?size=1", tokenA);
        int one = probe.reads.get(); probe.reads.set(0);
        get(batchPath(source) + "/result/words?size=100", tokenA);
        assertEquals(one, probe.reads.get()); assertTrue(one <= 3);
        get(batchPath(source) + "/result", tokenA);
        assertEquals(0, probe.writes.get());
        assertEquals(original, batchSnapshot(source)); assertEquals(state, stateSnapshot());
    }

    @Test void resultWordsValidatePaginationAndSelfAssessmentFilter() throws Exception {
        long source = completedBatch(2);
        for (String query : List.of("page=0", "page=-1", "page=", "page=1.5", "page=2147483648",
                "size=0", "size=101", "size=", "result=0", "result=4", "result=-1", "result=2.5",
                "result=KNOWN", "result=1%20OR%201=1")) {
            var response = call("GET", batchPath(source) + "/result/words?" + query, null, tokenA);
            assertEquals(400, response.statusCode(), query + ": " + response.body());
        }
    }

    @Test void relearnCopiesOnlyWeakWordsInSourceOrderWithoutChangingSourceOrState() throws Exception {
        long source = completedBatch(1, 2, 3, 2);
        var entries = batchDetails(source);
        int[] order = {40, 30, 10, 20};
        for (int i = 0; i < entries.size(); i++) {
            var patch = new LearningBatchItem(); patch.setId(entries.get(i).getId());
            patch.setSortOrder(order[i]); details.update(patch);
        }
        var expected = List.of(entries.get(2).getWordId(), entries.get(3).getWordId(), entries.get(1).getWordId());
        // The source snapshot is authoritative, even when current book membership changes.
        bookItems.deleteByQuery(QueryWrapper.create().where(column("book_id").eq(systemBook))
            .and(column("word_id").eq(expected.getFirst())));
        String original = batchSnapshot(source), state = stateSnapshot();
        long before = countBatches();
        var first = relearned(source, null);
        assertBatch(first, "MANUAL", expected);
        assertEquals(source, get(batchPath(source) + "/result", tokenA).get("batchId").asLong());
        var second = relearned(source, Map.of());
        assertBatch(second, "MANUAL", expected);
        assertNotEquals(first.get("batchId"), second.get("batchId"));
        assertEquals(before + 2, countBatches());
        assertEquals(original, batchSnapshot(source)); assertEquals(state, stateSnapshot());
    }

    @Test void relearnedBatchUsesExistingStudyProgressAndResultEndpoints() throws Exception {
        long source = completedBatch(1, 2, 3);
        long target = relearned(source, null).get("batchId").asLong();
        var entries = batchDetails(target);
        var initial = get(batchPath(target), tokenA);
        assertEquals(0, initial.get("answeredCount").asLong());
        assertEquals(entries.getFirst().getId(), initial.get("nextItemId").asLong());
        assertFalse(get(itemPath(target, entries.getFirst().getId()), tokenA).has("translation"));
        answer(target, entries.getFirst().getId(), 1);
        var saved = get(batchPath(target), tokenA);
        assertEquals(1, saved.get("answeredCount").asLong());
        assertEquals(entries.get(1).getId(), saved.get("nextItemId").asLong());
        answer(target, entries.get(1).getId(), 1);
        var completed = get(batchPath(target), tokenA);
        assertEquals("COMPLETED", completed.get("status").asText()); assertTrue(completed.get("nextItemId").isNull());
        var result = get(batchPath(target) + "/result", tokenA);
        assertEquals(2, result.get("totalCount").asLong()); assertEquals(2, result.get("knownCount").asLong());
        assertEquals(0, result.get("relearnCount").asLong());
        assertEquals(1, get(batchPath(source) + "/result", tokenA).get("uncertainCount").asLong());
    }

    @Test void noWeakWordsDoesNotCreateAnEmptyRelearnBatch() throws Exception {
        long source = completedBatch(1, 1);
        long before = countBatches(); String original = batchSnapshot(source), state = stateSnapshot();
        var response = call("POST", batchPath(source) + "/relearn", null, tokenA);
        assertEquals(409, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("NO_RELEARN_WORDS", body.get("code").asText());
        assertEquals("本轮没有需要重新学习的单词", body.get("message").asText());
        assertEquals(before, countBatches());
        assertEquals(original, batchSnapshot(source)); assertEquals(state, stateSnapshot());
    }

    @Test void relearnRejectsClientSelectedWordsAndUserIdentity() throws Exception {
        long source = completedBatch(2);
        long before = countBatches();
        for (Object body : List.of(Map.of("wordIds", List.of(wordIds.get(7))), Map.of("user_id", b),
                Map.of("userId", b), Map.of("bookId", otherBook), Map.of("count", 1))) {
            assertEquals(400, call("POST", batchPath(source) + "/relearn", body, tokenA).statusCode());
        }
        assertEquals(before, countBatches());
    }

    @Test void relearnRechecksSourceBookAccessAndHandlesMissingBook() throws Exception {
        long source = completedBatch(2);
        long before = countBatches();
        var patch = new WordBook(); patch.setId(systemBook); patch.setOwnerId(b); books.update(patch);
        var forbidden = call("POST", batchPath(source) + "/relearn", null, tokenA);
        assertEquals(404, forbidden.statusCode());
        assertEquals("BOOK_NOT_FOUND", json.readTree(forbidden.body()).get("code").asText());
        // Historical results remain readable by the batch owner, independent of current book access.
        assertEquals(1, get(batchPath(source) + "/result", tokenA).get("totalCount").asLong());
        var detached = new LearningBatch(); detached.setUserId(a); detached.setSelectionType("MANUAL");
        detached.setStatus("COMPLETED"); detached.setCompletedAt(LocalDateTime.now());
        batches.insertSelective(detached);
        var missing = call("POST", batchPath(detached.getId()) + "/relearn", null, tokenA);
        assertEquals(404, missing.statusCode());
        assertEquals("BOOK_NOT_FOUND", json.readTree(missing.body()).get("code").asText());
        assertEquals(before + 1, countBatches());
    }

    @Test void sourceCorrectionsAffectNextRelearnButNeverAnExistingCopy() throws Exception {
        long source = completedBatch(1, 2, 3);
        var entries = batchDetails(source);
        long first = relearned(source, null).get("batchId").asLong();
        String copy = batchSnapshot(first);
        answer(source, entries.getFirst().getId(), 3);
        answer(source, entries.get(1).getId(), 1);
        assertEquals(copy, batchSnapshot(first));
        var second = relearned(source, null);
        assertBatch(second, "MANUAL", List.of(entries.getFirst().getWordId(), entries.get(2).getWordId()));
        assertEquals(List.of(entries.get(1).getWordId(), entries.get(2).getWordId()),
            batchDetails(first).stream().map(LearningBatchItem::getWordId).toList());
    }

    @Test void relearnBatchInsertFailureRollsBackEverything() throws Exception {
        relearnRollbackAfterWrite("com.wordtrail.learning.mapper.LearningBatchMapper");
    }

    @Test void relearnDetailInsertFailureRollsBackEverything() throws Exception {
        relearnRollbackAfterWrite("com.wordtrail.learning.mapper.LearningBatchItemMapper");
    }

    private void relearnRollbackAfterWrite(String mapper) throws Exception {
        long source = completedBatch(1, 2, 3);
        var before = batches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
            .stream().map(LearningBatch::getId).sorted().toList();
        long detailCount = details.selectCountByQuery(QueryWrapper.create().where(column("batch_id").in(before)));
        String original = batchSnapshot(source), state = stateSnapshot();
        probe.failAfterMapper = mapper;
        try { assertEquals(500, call("POST", batchPath(source) + "/relearn", null, tokenA).statusCode()); }
        finally { probe.failAfterMapper = null; }
        var after = batches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
            .stream().map(LearningBatch::getId).sorted().toList();
        assertEquals(before, after);
        assertEquals(detailCount, details.selectCountByQuery(QueryWrapper.create().where(column("batch_id").in(after))));
        assertEquals(original, batchSnapshot(source)); assertEquals(state, stateSnapshot());
        assertEquals(2, relearned(source, null).get("wordCount").asInt());
    }

    private void rollbackAfterWrite(String mapper) throws Exception {
        long before = countBatches();
        var existingIds = batches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
            .stream().map(LearningBatch::getId).toList();
        long detailCount = details.selectCountByQuery(QueryWrapper.create().where(column("batch_id").in(existingIds)));
        String state = stateSnapshot();
        probe.failAfterMapper = mapper;
        try {
            assertEquals(500, create(Map.of("bookId", systemBook, "selectionType", "BOOK", "count", 3)).statusCode());
        } finally { probe.failAfterMapper = null; }
        assertEquals(before, countBatches());
        var remainingIds = batches.selectListByQuery(QueryWrapper.create().where(column("user_id").in(userIds)))
            .stream().map(LearningBatch::getId).toList();
        assertEquals(existingIds, remainingIds);
        assertEquals(detailCount, details.selectCountByQuery(QueryWrapper.create().where(column("batch_id").in(remainingIds))));
        assertEquals(state, stateSnapshot());
    }
}
