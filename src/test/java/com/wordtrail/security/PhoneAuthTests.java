package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.user.service.UserService;
import io.jsonwebtoken.Jwts;
import java.util.Base64;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.mybatis.spring.SqlSessionTemplate;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({com.wordtrail.H2TestSchema.class, PhoneAuthTests.FakeSms.class})
class PhoneAuthTests {
    private static final String SECRET=Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.jwt.secret",()->SECRET); }
    @TestConfiguration static class FakeSms {
        @Bean @Primary SmsGateway fakeGateway() { return new SmsGateway() {
            public void send(String phone,String purpose) {}
            public boolean verify(String phone,String code) { return code.equals("123456"); }
        }; }
    }
    @Autowired SmsVerificationService sms;
    @Autowired UserService users;
    @Autowired CurrentUser current;
    @Autowired JdbcTemplate db;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SqlSessionTemplate sqlSession;
    @BeforeEach void clear() throws Exception { com.wordtrail.IsolatedDatabaseGuard.check(db.getDataSource()); db.update("DELETE FROM sms_challenge"); db.update("DELETE FROM sms_send_log"); db.update("DELETE FROM auth_password_attempt"); }
    @AfterEach void unbind() { current.clear(); }

    @Test void verificationIsBoundToPurposeAndConsumedOnce() {
        sms.send("13800000001","REGISTER",null,null,"127.0.0.1");
        assertThrows(BusinessException.class,()->sms.verify("13800000001","LOGIN",null,null,"123456"));
        assertThrows(BusinessException.class,()->sms.verify("13800000001","REGISTER",null,null,"000000"));
        sms.verify("13800000001","REGISTER",null,null,"123456");
        assertThrows(BusinessException.class,()->sms.verify("13800000001","REGISTER",null,null,"123456"));
        assertThrows(BusinessException.class,()->sms.send("13800000001","REGISTER",null,null,"127.0.0.1"));
    }
    @Test void accountCanRegisterAndUseEitherLogin() {
        String phone="13800000002";
        sms.send(phone,"REGISTER",null,null,"127.0.0.1");
        sms.verify(phone,"REGISTER",null,null,"123456");
        var registered=users.registerPhone(phone,"password123",null);
        assertEquals(phone,registered.getUser().phone());
        assertEquals(registered.getUser().id(),users.passwordPhone(phone,"password123",null).getUser().id());
        assertThrows(BusinessException.class,()->users.passwordPhone(phone,"wrongpass",null));
        assertEquals(registered.getUser().id(),users.loginPhone(phone,null).getUser().id());
        assertThrows(BusinessException.class,()->users.registerPhone(phone,"password123",null));
    }
    @Test void protectedCodesAreBoundToUserAndSession() {
        sms.send("13800000003","PASSWORD",12L,"session-a","127.0.0.1");
        assertThrows(BusinessException.class,()->sms.verify("13800000003","PASSWORD",12L,"session-b","123456"));
        sms.verify("13800000003","PASSWORD",12L,"session-a","123456");
    }
    @Test void codeAndPasswordAttemptsAreLimited() {
        sms.send("13800000006","LOGIN",null,null,"127.0.0.1");
        for(int i=0;i<5;i++) assertThrows(BusinessException.class,()->sms.verify("13800000006","LOGIN",null,null,"000000"));
        assertThrows(BusinessException.class,()->sms.verify("13800000006","LOGIN",null,null,"123456"));
        for(int i=0;i<10;i++) sms.limitPassword("13800000006","127.0.0.1");
        assertThrows(BusinessException.class,()->sms.limitPassword("13800000006","127.0.0.1"));
    }
    @Test void missingConfigurationDoesNotConsumeSmsQuota() {
        var gateway=new AliyunSmsGateway("test-id","test-secret","",json);
        var service=new SmsVerificationService(sqlSession,gateway,clock,transactions,10);
        assertThrows(BusinessException.class,()->service.send("13800000007","LOGIN",null,null,"127.0.0.1"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sms_send_log",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sms_challenge",Integer.class));
    }
    @Test void failedSendsDoNotConsumeQuotaOrLeaveAnActiveChallenge() {
        String phone="13800000009";
        SmsGateway failing=new SmsGateway() {
            public void send(String p,String purpose) { throw new IllegalStateException("provider failed"); }
            public boolean verify(String p,String code) { return true; }
        };
        var service=new SmsVerificationService(sqlSession,failing,clock,transactions,10);
        for(int i=0;i<12;i++)
            assertThrows(IllegalStateException.class,()->service.send(phone,"LOGIN",null,null,"127.0.0.1"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sms_send_log",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sms_challenge",Integer.class));
        sms.send(phone,"LOGIN",null,null,"127.0.0.1");
        sms.verify(phone,"LOGIN",null,null,"123456");
        assertThrows(BusinessException.class,()->sms.send(phone,"LOGIN",null,null,"127.0.0.1"));
    }
    @Test void concurrentSmsSendAndVerificationAllowOnlyOneSuccess() throws Exception {
        String phone="13800000010";
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> send=()->{
                ready.await();
                try { sms.send(phone,"LOGIN",null,null,"127.0.0.1"); return true; }
                catch(BusinessException ex) { assertEquals("SMS_RATE_LIMIT",ex.code()); return false; }
            };
            var first=pool.submit(send);var second=pool.submit(send);ready.countDown();
            assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            var verifyReady=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> verify=()->{
                verifyReady.await();
                try { sms.verify(phone,"LOGIN",null,null,"123456"); return true; }
                catch(BusinessException ex) { assertEquals("INVALID_SMS_CODE",ex.code()); return false; }
            };
            var a=pool.submit(verify);var b=pool.submit(verify);verifyReady.countDown();
            assertNotEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void unregisteredPhoneCreatesAccountAfterCodeVerification() throws Exception {
        String phone="13800000008";
        assertEquals(200,post("/send","{\"phone\":\""+phone+"\",\"purpose\":\"LOGIN\"}",null).statusCode());
        assertEquals(400,post("/code-login","{\"phone\":\""+phone+"\",\"code\":\"000000\"}",null).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_account WHERE phone=?",Integer.class,phone));
        var login=post("/code-login","{\"phone\":\""+phone+"\",\"code\":\"123456\"}",null);
        assertEquals(200,login.statusCode());
        var pair=json.readTree(login.body()).path("data");
        long userId=pair.path("user").path("id").asLong();
        assertTrue(userId>0);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_account WHERE phone=?",Integer.class,phone));
        assertEquals(400,post("/code-login","{\"phone\":\""+phone+"\",\"code\":\"123456\"}",null).statusCode());
        assertEquals(401,post("/password-login","{\"phone\":\""+phone+"\",\"password\":\"password123\"}",null).statusCode());
        db.update("DELETE FROM sms_send_log");
        String token=pair.path("accessToken").asText();
        assertEquals(200,post("/send-auth","{\"phone\":\""+phone+"\",\"purpose\":\"PASSWORD\"}",token).statusCode());
        assertEquals(200,request("PUT","/password","{\"code\":\"123456\",\"newPassword\":\"password123\"}",token).statusCode());
        var passwordLogin=post("/password-login","{\"phone\":\""+phone+"\",\"password\":\"password123\"}",null);
        assertEquals(200,passwordLogin.statusCode());
        assertEquals(userId,json.readTree(passwordLogin.body()).path("data").path("user").path("id").asLong());
    }
    @Test void phoneRegistrationAndSecurityFlowThroughHttp() throws Exception {
        String old="13800000004", next="13800000005";
        assertEquals(200,post("/send", "{\"phone\":\""+old+"\",\"purpose\":\"REGISTER\"}",null).statusCode());
        var response=post("/register","{\"phone\":\""+old+"\",\"code\":\"123456\",\"password\":\"password123\"}",null);
        assertEquals(200,response.statusCode());
        String token=json.readTree(response.body()).path("data").path("accessToken").asText();
        assertFalse(token.isBlank());
        assertEquals(429,post("/send-auth","{\"phone\":\""+old+"\",\"purpose\":\"CHANGE_OLD\"}",token).statusCode());
        db.update("DELETE FROM sms_send_log");
        assertEquals(200,post("/send","{\"phone\":\""+old+"\",\"purpose\":\"LOGIN\"}",null).statusCode());
        assertEquals(200,post("/code-login","{\"phone\":\""+old+"\",\"code\":\"123456\"}",null).statusCode());
        assertEquals(400,post("/code-login","{\"phone\":\""+old+"\",\"code\":\"123456\"}",null).statusCode());
        db.update("DELETE FROM sms_send_log");
        assertEquals(200,post("/send-auth","{\"phone\":\""+old+"\",\"purpose\":\"CHANGE_OLD\"}",token).statusCode());
        assertEquals(200,post("/send-auth","{\"phone\":\""+next+"\",\"purpose\":\"CHANGE_NEW\"}",token).statusCode());
        var changed=request("PUT","/binding","{\"oldCode\":\"123456\",\"newPhone\":\""+next+"\",\"newCode\":\"123456\"}",token);
        assertEquals(200,changed.statusCode());
        assertEquals(401,post("/send-auth","{\"phone\":\""+next+"\",\"purpose\":\"PASSWORD\"}",token).statusCode());
        var login=post("/password-login","{\"phone\":\""+next+"\",\"password\":\"password123\"}",null);
        assertEquals(200,login.statusCode());
        String newToken=json.readTree(login.body()).path("data").path("accessToken").asText();
        assertEquals(401,post("/password-login","{\"phone\":\""+old+"\",\"password\":\"password123\"}",null).statusCode());
        db.update("DELETE FROM sms_send_log");
        assertEquals(200,post("/send-auth","{\"phone\":\""+next+"\",\"purpose\":\"PASSWORD\"}",newToken).statusCode());
        assertEquals(200,request("PUT","/password","{\"code\":\"123456\",\"newPassword\":\"newpassword123\"}",newToken).statusCode());
        assertEquals(401,post("/send-auth","{\"phone\":\""+next+"\",\"purpose\":\"PASSWORD\"}",newToken).statusCode());
        assertEquals(200,post("/password-login","{\"phone\":\""+next+"\",\"password\":\"newpassword123\"}",null).statusCode());
    }
    private HttpResponse<String> post(String path,String body,String token) throws Exception { return request("POST",path,body,token); }
    private HttpResponse<String> request(String method,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+"/api/auth/phone"+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
