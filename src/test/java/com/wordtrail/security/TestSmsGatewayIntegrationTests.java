package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(com.wordtrail.H2TestSchema.class)
@SpringBootTest
@ActiveProfiles("sms-test")
class TestSmsGatewayIntegrationTests {
    private static final String SECRET=Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.jwt.secret",()->SECRET); }
    @Autowired SmsGateway gateway;
    @Autowired SmsVerificationService sms;
    @Autowired JdbcTemplate db;

    @org.junit.jupiter.api.BeforeEach void checkDatabase() throws Exception { com.wordtrail.IsolatedDatabaseGuard.check(db.getDataSource()); }

    @Test void fixedCodeRequiresARequestAndCanOnlyBeUsedOnce() {
        String phone="13800000009";
        db.update("DELETE FROM sms_challenge WHERE phone=?",phone);
        db.update("DELETE FROM sms_send_log WHERE phone=?",phone);
        assertInstanceOf(TestSmsGateway.class,gateway);
        assertThrows(BusinessException.class,()->sms.verify(phone,"LOGIN",null,null,"123456"));
        sms.send(phone,"LOGIN",null,null,"127.0.0.1");
        assertThrows(BusinessException.class,()->sms.verify(phone,"REGISTER",null,null,"123456"));
        assertThrows(BusinessException.class,()->sms.verify(phone,"LOGIN",null,null,"654321"));
        sms.verify(phone,"LOGIN",null,null,"123456");
        assertThrows(BusinessException.class,()->sms.verify(phone,"LOGIN",null,null,"123456"));
    }
    @Test void refusesToStartAlongsideProductionProfile() {
        var env=new MockEnvironment(); env.setActiveProfiles("sms-test","prod");
        assertThrows(IllegalStateException.class,()->new TestSmsGateway(env));
    }
}
