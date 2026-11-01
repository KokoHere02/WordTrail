package com.wordtrail;

import com.wordtrail.ops.DatabaseSchemaVerifier;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseSchemaVerifierTests {
    private EmbeddedDatabase database;

    @AfterEach void cleanup() { if(database!=null) database.shutdown(); }

    private JdbcTemplate schema(boolean initialize) {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
            .setName("schema_guard;MODE=MySQL").build();
        if(initialize) new ResourceDatabasePopulator(new ClassPathResource("auth-schema.sql"),
            new ClassPathResource("sentence-schema.sql")).execute(database);
        return new JdbcTemplate(database);
    }
    private ApplicationContextRunner runner(DataSource source) {
        return new ApplicationContextRunner().withBean(DatabaseSchemaVerifier.class,()->new DatabaseSchemaVerifier(source));
    }
    @Test void completeSchemaStartsAndExistingDataIsNotChanged() {
        var jdbc=schema(true); jdbc.update("INSERT INTO word(word,translation) VALUES('schema_fixture','原值')");
        runner(database).run(context->assertNull(context.getStartupFailure()));
        assertEquals("原值",jdbc.queryForObject("SELECT translation FROM word WHERE word='schema_fixture'",String.class));
    }
    @Test void absentMigrationStopsContextAndNamesMissingTable() {
        var jdbc=schema(true); jdbc.execute("DROP TABLE word_training_attempt");
        runner(database).run(context->{
            assertNotNull(context.getStartupFailure()); String message=context.getStartupFailure().getMessage();
            assertTrue(message.contains("word_training_attempt")); assertTrue(message.contains("016"));
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='WORD_TRAINING_ATTEMPT'",Integer.class));
    }
    @Test void partiallyAppliedMigrationStopsContextAndNamesMissingColumn() {
        var jdbc=schema(true); jdbc.execute("ALTER TABLE learning_batch ALTER COLUMN study_mode RENAME TO old_study_mode");
        runner(database).run(context->{
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage().contains("learning_batch.study_mode"));
        });
    }
    @Test void emptyDatabaseIsNotAutomaticallyMigrated() {
        var jdbc=schema(false);
        runner(database).run(context->assertNotNull(context.getStartupFailure()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='PUBLIC'",Integer.class));
    }
    @Test void connectionFailureStopsContextWithoutExposingDriverMessage() throws Exception {
        var source=mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("password=private-fixture-secret","08001",1045));
        runner(source).run(context->{
            assertNotNull(context.getStartupFailure()); String message=context.getStartupFailure().getMessage();
            assertTrue(message.contains("08001")); assertTrue(message.contains("1045"));
            assertFalse(message.contains("private-fixture-secret")); assertNull(context.getStartupFailure().getCause());
        });
    }
}
