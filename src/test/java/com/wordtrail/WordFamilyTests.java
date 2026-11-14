package com.wordtrail;

import com.wordtrail.word.controller.WordController;
import com.wordtrail.word.service.WordService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WordFamilyTests {
    @Test void returnsDistinctActiveMembersExcludingCurrentWordWithLimit() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var db = new JdbcTemplate(database);
            db.execute("CREATE TABLE word (id BIGINT PRIMARY KEY, word VARCHAR(255), phonetic VARCHAR(255), translation VARCHAR(255))");
            db.execute("CREATE TABLE word_family (id BIGINT PRIMARY KEY, status VARCHAR(16))");
            db.execute("CREATE TABLE word_family_word (family_id BIGINT, word_id BIGINT, status VARCHAR(16), sort_order INT)");
            db.update("INSERT INTO word VALUES (1,'act',NULL,'行动'),(2,'action',NULL,'行为'),(3,'active',NULL,'活跃'),(4,'actor',NULL,'演员'),(5,'activity',NULL,'活动'),(6,'activate',NULL,'激活'),(7,'inactive',NULL,'不活跃'),(8,'hidden',NULL,'隐藏')");
            db.update("INSERT INTO word_family VALUES (1,'ACTIVE'),(2,'ACTIVE'),(3,'DISABLED')");
            db.update("INSERT INTO word_family_word VALUES (1,1,'ACTIVE',0),(1,2,'ACTIVE',1),(1,3,'ACTIVE',2),(1,4,'ACTIVE',3),(1,5,'ACTIVE',4),(1,6,'ACTIVE',5),(1,7,'ACTIVE',6),(1,8,'DISABLED',7),(2,1,'ACTIVE',0),(2,2,'ACTIVE',1),(3,1,'ACTIVE',0),(3,8,'ACTIVE',1)");
            var controller = new WordController(mock(WordService.class), db);
            var words = controller.related(1).data();
            assertEquals(6, words.size());
            assertEquals("action", words.getFirst().word());
            assertEquals("行为", words.getFirst().translation());
            assertTrue(words.stream().noneMatch(word -> word.id() == 1 || word.id() == 8));
            assertTrue(controller.related(8).data().isEmpty());
            for (int index = 0; index < 60; index++) {
                db.update("INSERT INTO word VALUES (?,?,NULL,?)", 100 + index, "word" + index, "释义");
                db.update("INSERT INTO word_family_word VALUES (1,?,'ACTIVE',?)", 100 + index, 100 + index);
            }
            assertEquals(50, controller.related(1).data().size());
        } finally { database.shutdown(); }
    }
}
