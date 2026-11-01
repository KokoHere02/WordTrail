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

@SpringBootTest
class SentencePracticeTransactionTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired SentencePracticeController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired TransactionTemplate transactions;

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
