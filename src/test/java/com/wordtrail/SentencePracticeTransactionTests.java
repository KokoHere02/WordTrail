package com.wordtrail;

import com.wordtrail.sentence.controller.SentencePracticeController;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.context.annotation.Import(com.wordtrail.H2TestSchema.class)
@SpringBootTest
class SentencePracticeTransactionTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired SentencePracticeController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired TransactionTemplate transactions;

    @Test void sentenceLengthFilterRunsBeforeLimitAndIncludesBoundary() throws Exception {
        IsolatedDatabaseGuard.check(dataSource);
        var currentUser = mock(com.wordtrail.security.CurrentUser.class);
        when(currentUser.id()).thenReturn(1L);
        var endpoint = new SentencePracticeController(jdbc, null, currentUser, null, null, null);
        String marker = "length-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            String boundary = marker + "a".repeat(180 - marker.length());
            for (String text : new String[]{marker + " first", marker + " second", boundary, marker + "b".repeat(181 - marker.length())}) {
                jdbc.update("INSERT INTO sentence_question(english_text,reference_translation,explanation,difficulty,source,status) VALUES(?,?,?,?,?,?)", text, "译文", "解析", 1, "fixture", "ACTIVE");
            }
            var filtered = endpoint.list("bank", null, null, null, marker, 180, 0, 3).data();
            assertEquals(3, filtered.size());
            assertEquals(boundary, filtered.get(0).englishText());
            assertTrue(filtered.stream().allMatch(row -> row.englishText().length() <= 180));
            assertEquals(181, endpoint.list("bank", null, null, null, marker, null, 0, 3).data().get(0).englishText().length());
            assertThrows(com.wordtrail.common.exception.BusinessException.class, () -> endpoint.list("bank", null, null, null, marker, 0, 0, 3));
            status.setRollbackOnly();
        });
    }

    @Test void jdbcWritesUseTheMybatisTransactionAndRollBack() throws Exception {
        IsolatedDatabaseGuard.check(dataSource);
        String name = "rollback-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            JdbcTemplate transactionJdbc = ReflectionTestUtils.invokeMethod(controller, "transactionDb");
            assertNotNull(transactionJdbc);
            Boolean autoCommit = transactionJdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> connection.getAutoCommit());
            assertEquals(Boolean.FALSE, autoCommit);
            transactionJdbc.update("INSERT INTO sentence_tag(category,name) VALUES (?,?)", "test", name);
            status.setRollbackOnly();
        });
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM sentence_tag WHERE name=?", Long.class, name));
    }
}
