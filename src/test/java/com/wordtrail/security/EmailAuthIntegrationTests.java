package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.user.service.UserService;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.mybatis.spring.SqlSessionTemplate;
import java.time.Clock;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(com.wordtrail.H2TestSchema.class)
@ActiveProfiles({"email-test","sms-test"})
class EmailAuthIntegrationTests {
    private static final String SECRET=Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.jwt.secret",()->SECRET); }
    @Autowired JdbcTemplate db;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired EmailVerificationService email;
    @Autowired UserService users;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SqlSessionTemplate sqlSession;
    @BeforeEach void clear() throws Exception {
        com.wordtrail.IsolatedDatabaseGuard.check(db.getDataSource());
        db.update("DELETE FROM email_challenge");db.update("DELETE FROM email_send_log");db.update("DELETE FROM email_password_attempt");
        db.update("DELETE FROM sms_challenge");db.update("DELETE FROM sms_send_log");
    }
    @Test void registerAndBothLoginMethodsRequireVerifiedCode() throws Exception {
        String addr="register-"+java.util.UUID.randomUUID()+"@example.com";
        assertEquals(400,post("/register",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),null).statusCode());
        assertEquals(200,post("/send",body(addr,"\"purpose\":\"REGISTER\""),null).statusCode());
        assertEquals(400,post("/register",body(addr,"\"code\":\"000000\",\"password\":\"password123\""),null).statusCode());
        var registered=post("/register",body(addr.toUpperCase(),"\"code\":\"123456\",\"password\":\"password123\""),null);
        assertEquals(200,registered.statusCode(),registered.body());
        long id=json.readTree(registered.body()).path("data").path("user").path("id").asLong();
        assertEquals(addr,json.readTree(registered.body()).path("data").path("user").path("email").asText());
        assertEquals(400,post("/register",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),null).statusCode());
        assertEquals(401,post("/password-login",body(addr,"\"password\":\"wrongpassword\""),null).statusCode());
        var password=post("/password-login",body(addr,"\"password\":\"password123\""),null);
        assertEquals(200,password.statusCode(),password.body());
        assertEquals(id,json.readTree(password.body()).path("data").path("user").path("id").asLong());
        db.update("DELETE FROM email_send_log");
        assertEquals(200,post("/send",body(addr,"\"purpose\":\"LOGIN\""),null).statusCode());
        var code=post("/code-login",body(addr,"\"code\":\"123456\""),null);
        assertEquals(200,code.statusCode(),code.body());
        assertEquals(id,json.readTree(code.body()).path("data").path("user").path("id").asLong());
        String token=json.readTree(code.body()).path("data").path("accessToken").asText();
        db.update("DELETE FROM email_send_log");
        assertEquals(200,post("/send-password","{}",token).statusCode());
        assertEquals(200,request("PUT","/password","{\"code\":\"123456\",\"newPassword\":\"newpassword123\"}",token).statusCode());
        assertEquals(401,post("/password-login",body(addr,"\"password\":\"password123\""),null).statusCode());
        assertEquals(200,post("/password-login",body(addr,"\"password\":\"newpassword123\""),null).statusCode());
    }
    @Test void bindingRequiresBothEmailAndIdentityAndIsSessionBound() throws Exception {
        String addr="bind-"+java.util.UUID.randomUUID()+"@example.com";
        var first=users.registerPhone("139"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000)),"password123",null);
        String token=first.getAccessToken();
        var second=users.passwordPhone(first.getUser().phone(),"password123",null).getAccessToken();
        assertEquals(200,post("/send-bind",body(addr,""),token).statusCode());
        assertEquals(400,request("PUT","/binding",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),second).statusCode());
        assertEquals(200,request("PUT","/binding",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),token).statusCode());
        var signed=post("/password-login",body(addr,"\"password\":\"password123\""),null);
        assertEquals(200,signed.statusCode(),signed.body());
        assertEquals(first.getUser().id(),json.readTree(signed.body()).path("data").path("user").path("id").asLong());
    }
    @Test void codeAttemptsAndPurposeAreLimited() {
        String addr="limits@example.com";
        email.send(addr,"LOGIN",null,null,"127.0.0.1");
        assertThrows(BusinessException.class,()->email.verify(addr,"REGISTER",null,null,"123456"));
        for(int i=0;i<5;i++) assertThrows(BusinessException.class,()->email.verify(addr,"LOGIN",null,null,"000000"));
        assertThrows(BusinessException.class,()->email.verify(addr,"LOGIN",null,null,"123456"));
        assertThrows(BusinessException.class,()->email.send(addr,"LOGIN",null,null,"127.0.0.1"));
    }
    @Test void testModeCannotRunInProduction() {
        var env=new MockEnvironment();env.setActiveProfiles("email-test","prod");
        assertThrows(IllegalStateException.class,()->new TestEmailGateway(env));
    }
    @Test void failedSendDoesNotConsumeQuota() {
        EmailGateway failing=new EmailGateway() {
            public void validateConfiguration() {}
            public void send(String address,String code) { throw new IllegalStateException("failed"); }
        };
        var service=new EmailVerificationService(sqlSession,failing,clock,transactions,SECRET,100,new MockEnvironment());
        for(int i=0;i<12;i++) assertThrows(IllegalStateException.class,()->service.send("fail@example.com","LOGIN",null,null,"127.0.0.1"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM email_send_log",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM email_challenge",Integer.class));
        email.send("fail@example.com","LOGIN",null,null,"127.0.0.1");
        email.verify("fail@example.com","LOGIN",null,null,"123456");
    }
    @Test void unverifiedLegacyEmailCannotLogIn() throws Exception {
        String address="legacy-"+java.util.UUID.randomUUID()+"@example.com";
        db.update("INSERT INTO user_account(username,email,password_hash,auth_version,email_verified) VALUES (?,?,?,?,?)","legacy_"+java.util.UUID.randomUUID().toString().replace("-",""),address,"dummy",0,false);
        assertEquals(200,post("/send",body(address,"\"purpose\":\"LOGIN\""),null).statusCode());
        assertEquals(401,post("/code-login",body(address,"\"code\":\"123456\""),null).statusCode());
    }
    @Test void emailAlreadyOwnedCannotBeBoundToAnotherAccount() throws Exception {
        String addr="owned-"+java.util.UUID.randomUUID()+"@example.com";
        assertEquals(200,post("/send",body(addr,"\"purpose\":\"REGISTER\""),null).statusCode());
        var original=post("/register",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),null);
        assertEquals(200,original.statusCode());
        long originalId=json.readTree(original.body()).path("data").path("user").path("id").asLong();
        var other=users.registerPhone("137"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000)),"password123",null);
        db.update("DELETE FROM email_send_log");
        assertEquals(200,post("/send-bind",body(addr,""),other.getAccessToken()).statusCode());
        assertEquals(409,request("PUT","/binding",body(addr,"\"code\":\"123456\",\"password\":\"password123\""),other.getAccessToken()).statusCode());
        assertEquals(originalId,json.readTree(post("/password-login",body(addr,"\"password\":\"password123\""),null).body()).path("data").path("user").path("id").asLong());
        assertNull(users.me(other.getUser().id()).email());
    }
    @Test void bindingCanUsePhoneVerificationInsteadOfPassword() throws Exception {
        String addr="smsbind-"+java.util.UUID.randomUUID()+"@example.com";
        String phone="136"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000));
        var account=users.registerPhone(phone,"password123",null);
        String token=account.getAccessToken();
        assertEquals(200,post("/send-bind",body(addr,""),token).statusCode());
        assertEquals(200,requestTo("POST","/api/auth/phone/send-auth","{\"phone\":\""+phone+"\",\"purpose\":\"EMAIL_BIND\"}",token).statusCode());
        assertEquals(200,request("PUT","/binding",body(addr,"\"code\":\"123456\",\"phoneCode\":\"123456\""),token).statusCode());
        assertEquals(addr,users.me(account.getUser().id()).email());
    }
    @Test void changingEmailRequiresBothCodesFromSameSessionAndRevokesSessions() throws Exception {
        String oldEmail="old-"+java.util.UUID.randomUUID()+"@example.com",newEmail="new-"+java.util.UUID.randomUUID()+"@example.com";
        assertEquals(200,post("/send",body(oldEmail,"\"purpose\":\"REGISTER\""),null).statusCode());
        var registered=post("/register",body(oldEmail,"\"code\":\"123456\",\"password\":\"password123\""),null);
        String token=json.readTree(registered.body()).path("data").path("accessToken").asText();
        var other=post("/password-login",body(oldEmail,"\"password\":\"password123\""),null);
        String otherToken=json.readTree(other.body()).path("data").path("accessToken").asText();
        db.update("DELETE FROM email_send_log");
        assertEquals(409,post("/send-change","{\"purpose\":\"CHANGE_NEW\",\"newEmail\":\""+oldEmail.toUpperCase()+"\"}",token).statusCode());
        assertEquals(200,post("/send-change","{\"purpose\":\"CHANGE_OLD\"}",token).statusCode());
        assertEquals(200,post("/send-change","{\"purpose\":\"CHANGE_NEW\",\"newEmail\":\""+newEmail+"\"}",token).statusCode());
        String change="{\"oldCode\":\"123456\",\"newEmail\":\""+newEmail+"\",\"newCode\":\"123456\"}";
        assertEquals(400,request("PUT","/change",change,otherToken).statusCode());
        assertEquals(400,request("PUT","/change",change.replace("\"newCode\":\"123456\"","\"newCode\":\"000000\""),token).statusCode());
        assertEquals(200,request("PUT","/change",change,token).statusCode());
        assertEquals(401,post("/send-password","{}",token).statusCode());
        assertEquals(401,post("/send-password","{}",otherToken).statusCode());
        assertEquals(401,post("/password-login",body(oldEmail,"\"password\":\"password123\""),null).statusCode());
        assertEquals(200,post("/password-login",body(newEmail,"\"password\":\"password123\""),null).statusCode());
    }
    @Test void changeToExistingEmailRollsBackCodesAndKeepsAccount() throws Exception {
        String original="change-source-"+java.util.UUID.randomUUID()+"@example.com",used="change-used-"+java.util.UUID.randomUUID()+"@example.com";
        for(String address:java.util.List.of(original,used)) {
            assertEquals(200,post("/send",body(address,"\"purpose\":\"REGISTER\""),null).statusCode());
            assertEquals(200,post("/register",body(address,"\"code\":\"123456\",\"password\":\"password123\""),null).statusCode());
        }
        String token=json.readTree(post("/password-login",body(original,"\"password\":\"password123\""),null).body()).path("data").path("accessToken").asText();
        db.update("DELETE FROM email_send_log");
        assertEquals(200,post("/send-change","{\"purpose\":\"CHANGE_OLD\"}",token).statusCode());
        assertEquals(200,post("/send-change","{\"purpose\":\"CHANGE_NEW\",\"newEmail\":\""+used+"\"}",token).statusCode());
        var conflict=request("PUT","/change","{\"oldCode\":\"123456\",\"newEmail\":\""+used+"\",\"newCode\":\"123456\"}",token);
        assertEquals(409,conflict.statusCode());
        assertEquals("EMAIL_EXISTS",json.readTree(conflict.body()).path("code").asText(),conflict.body());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM email_challenge WHERE consumed=?",Integer.class,true),()->db.queryForList("SELECT email,purpose,consumed FROM email_challenge").toString());
        assertEquals(200,post("/password-login",body(original,"\"password\":\"password123\""),null).statusCode());
        assertEquals(200,post("/password-login",body(used,"\"password\":\"password123\""),null).statusCode());
    }
    @Test void concurrentSendAndVerificationAllowOnlyOneSuccess() throws Exception {
        String address="race-"+java.util.UUID.randomUUID()+"@example.com";
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> send=()->{
                ready.await();
                try { email.send(address,"LOGIN",null,null,"127.0.0.1"); return true; }
                catch(BusinessException ex) { assertEquals("EMAIL_RATE_LIMIT",ex.code()); return false; }
            };
            var first=pool.submit(send);var second=pool.submit(send);ready.countDown();
            assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM email_send_log WHERE email=?",Integer.class,address));
            var verifyReady=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> verify=()->{
                verifyReady.await();
                try { email.verify(address,"LOGIN",null,null,"123456"); return true; }
                catch(BusinessException ex) { assertEquals("INVALID_EMAIL_CODE",ex.code()); return false; }
            };
            var a=pool.submit(verify);var b=pool.submit(verify);verifyReady.countDown();
            assertNotEquals(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private String body(String email,String rest) { return "{\"email\":\""+email+"\""+(rest.isEmpty()?"":","+rest)+"}"; }
    private HttpResponse<String> post(String path,String body,String token) throws Exception { return request("POST",path,body,token); }
    private HttpResponse<String> request(String method,String path,String body,String token) throws Exception {
        return requestTo(method,"/api/auth/email"+path,body,token);
    }
    private HttpResponse<String> requestTo(String method,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+path)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
