package com.wordtrail;

import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

final class IsolatedDatabaseGuard {
    private IsolatedDatabaseGuard() {}

    static void check(DataSource dataSource) throws Exception {
        try (var c=dataSource.getConnection()) {
            if(c.getMetaData().getURL().startsWith("jdbc:h2:mem:")) return;
            String expected=System.getenv("WORDTRAIL_MYSQL_TEST_SCHEMA");
            assertNotNull(expected,"MySQL integration tests require WORDTRAIL_MYSQL_TEST_SCHEMA; refusing fixture writes");
            assertTrue(expected.matches("wordtrail_daily_verify_[a-f0-9]{32}"),"Use a separate generated verification database");
            assertEquals(expected,c.getCatalog(),"Refusing fixture writes outside the specified verification database");
        }
    }
}
