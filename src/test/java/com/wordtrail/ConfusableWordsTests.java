package com.wordtrail;

import com.wordtrail.word.controller.WordController;
import com.wordtrail.word.service.WordService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfusableWordsTests {
    @Test void findsEitherSideAndHidesInactivePairs() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var db = new JdbcTemplate(database);
            db.execute("CREATE TABLE word (id BIGINT PRIMARY KEY, word VARCHAR(255), phonetic VARCHAR(255), translation VARCHAR(255))");
            db.execute("CREATE TABLE word_confusable_pair (word_id_a BIGINT, word_id_b BIGINT, similarity_score DECIMAL(10,8), status VARCHAR(16))");
            db.update("INSERT INTO word VALUES (1,'castle',NULL,'城堡'),(2,'cattle',NULL,'牛'),(3,'hidden',NULL,'隐藏')");
            db.update("INSERT INTO word_confusable_pair VALUES (1,2,0.9,'ACTIVE'),(1,3,0.8,'INACTIVE')");
            var controller = new WordController(mock(WordService.class), db);
            assertEquals("cattle", controller.confusables(1).data().getFirst().word());
            assertEquals(1, controller.confusables(1).data().size());
            assertEquals("castle", controller.confusables(2).data().getFirst().word());
            assertTrue(controller.confusables(3).data().isEmpty());
        } finally { database.shutdown(); }
    }
}
