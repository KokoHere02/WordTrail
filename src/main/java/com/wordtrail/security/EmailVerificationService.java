package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailVerificationService {
    private record Challenge(String purpose,Long owner,String session,String token,String hash,Instant expires,int attempts,boolean consumed) {}
    private final SqlSessionTemplate sqlSession;
    private final EmailGateway gateway;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final byte[] key;
    private final int dailyLimit;
    private final boolean testMode;
    private final SecureRandom random=new SecureRandom();
    public EmailVerificationService(SqlSessionTemplate sqlSession,EmailGateway gateway,Clock clock,PlatformTransactionManager manager,
            @Value("${app.jwt.secret:}") String secret,@Value("${app.mail.daily-limit:100}") int dailyLimit,Environment env) {
        this.sqlSession=sqlSession; this.gateway=gateway; this.clock=clock; this.key=secret.getBytes(StandardCharsets.UTF_8); this.dailyLimit=dailyLimit;
        if(key.length==0 || dailyLimit<1) throw new IllegalStateException("Email verification requires a secret and positive limit");
        testMode=java.util.Arrays.asList(env.getActiveProfiles()).contains("email-test");
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public static String normalize(String email) { return email.strip().toLowerCase(java.util.Locale.ROOT); }
    public void send(String email,String purpose,Long owner,String session,String ip) {
        if(!Set.of("REGISTER","LOGIN","BIND","PASSWORD","CHANGE_OLD","CHANGE_NEW").contains(purpose)) throw invalid();
        gateway.validateConfiguration();
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        String id=UUID.randomUUID().toString(), code=testMode?"123456":String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1000000));
        String client=digest(ip), codeHash=sign(id+":"+code);
        tx.executeWithoutResult(s->{
            var db=transactionDb();
            db.queryForObject("SELECT id FROM email_send_guard WHERE id=1 FOR UPDATE",Integer.class);
            Timestamp day=Timestamp.from(now.minusSeconds(86400));
            db.update("DELETE FROM email_send_log WHERE created_at<?",day);
            db.update("DELETE FROM email_challenge WHERE expires_at<?",Timestamp.from(now));
            if(count(db,"SELECT COUNT(*) FROM email_send_log WHERE created_at>?",day)>=dailyLimit ||
               count(db,"SELECT COUNT(*) FROM email_send_log WHERE email=? AND created_at>?",email,day)>=10 ||
               count(db,"SELECT COUNT(*) FROM email_send_log WHERE client_ip=? AND created_at>?",client,day)>=50 ||
               count(db,"SELECT COUNT(*) FROM email_send_log WHERE email=? AND created_at>?",email,Timestamp.from(now.minusSeconds(60)))>0)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"EMAIL_RATE_LIMIT","发送过于频繁，请稍后再试");
            db.update("INSERT INTO email_send_log(id,email,client_ip,created_at) VALUES (?,?,?,?)",id,email,client,Timestamp.from(now));
            db.update("DELETE FROM email_challenge WHERE email=?",email);
            db.update("INSERT INTO email_challenge(email,purpose,owner_id,session_id,token_id,code_hash,expires_at,attempts,consumed) VALUES (?,?,?,?,?,?,?,0,?)",email,purpose,owner,session,id,codeHash,Timestamp.from(now.plusSeconds(300)),true);
        });
        try { gateway.send(email,code); }
        catch(RuntimeException ex) {
            try { tx.executeWithoutResult(s->{var db=transactionDb();db.update("DELETE FROM email_send_log WHERE id=?",id);db.update("DELETE FROM email_challenge WHERE token_id=?",id);}); }
            catch(RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
        tx.executeWithoutResult(s->transactionDb().update("UPDATE email_challenge SET consumed=? WHERE token_id=?",false,id));
    }
    public void verify(String email,String purpose,Long owner,String session,String code) {
        Boolean ok=tx.execute(s->{
            var db=transactionDb();
            var rows=db.query("SELECT purpose,owner_id,session_id,token_id,code_hash,expires_at,attempts,consumed FROM email_challenge WHERE email=? FOR UPDATE",(rs,n)->{
                long id=rs.getLong("owner_id"); Long user=rs.wasNull()?null:id;
                return new Challenge(rs.getString("purpose"),user,rs.getString("session_id"),rs.getString("token_id"),rs.getString("code_hash"),rs.getTimestamp("expires_at").toInstant(),rs.getInt("attempts"),rs.getBoolean("consumed"));
            },email);
            if(rows.isEmpty()) return false;
            var row=rows.get(0);
            if(!purpose.equals(row.purpose()) || !Objects.equals(owner,row.owner()) || !Objects.equals(session,row.session()) || row.consumed() || row.attempts()>=5 || !row.expires().isAfter(clock.instant())) return false;
            db.update("UPDATE email_challenge SET attempts=attempts+1 WHERE token_id=?",row.token());
            boolean passed=MessageDigest.isEqual(row.hash().getBytes(StandardCharsets.US_ASCII),sign(row.token()+":"+code).getBytes(StandardCharsets.US_ASCII));
            if(passed) db.update("UPDATE email_challenge SET consumed=? WHERE token_id=?",true,row.token());
            return passed;
        });
        if(!Boolean.TRUE.equals(ok)) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_EMAIL_CODE","验证码错误或已失效，请重新获取");
    }
    /** Verify both codes and apply the account change in the same transaction. */
    public void verifyChangeAndApply(String oldEmail,String newEmail,Long owner,String session,String oldCode,String newCode,Runnable apply) {
        if(oldEmail.equals(newEmail)) throw invalid();
        Boolean ok=tx.execute(s->{
            var db=transactionDb();
            // Lock in a stable order so concurrent changes cannot deadlock each other.
            Challenge first=lockChallenge(db,oldEmail.compareTo(newEmail)<0?oldEmail:newEmail);
            Challenge second=lockChallenge(db,oldEmail.compareTo(newEmail)<0?newEmail:oldEmail);
            Challenge oldRow=oldEmail.compareTo(newEmail)<0?first:second;
            Challenge newRow=oldEmail.compareTo(newEmail)<0?second:first;
            boolean oldEligible=eligible(oldRow,"CHANGE_OLD",owner,session);
            boolean newEligible=eligible(newRow,"CHANGE_NEW",owner,session);
            boolean oldPassed=oldEligible && matches(oldRow,oldCode);
            boolean newPassed=newEligible && matches(newRow,newCode);
            if(!oldPassed || !newPassed) {
                if(oldEligible && !oldPassed) db.update("UPDATE email_challenge SET attempts=attempts+1 WHERE token_id=?",oldRow.token());
                if(newEligible && !newPassed) db.update("UPDATE email_challenge SET attempts=attempts+1 WHERE token_id=?",newRow.token());
                return false;
            }
            db.update("UPDATE email_challenge SET attempts=attempts+1,consumed=? WHERE token_id=?",true,oldRow.token());
            db.update("UPDATE email_challenge SET attempts=attempts+1,consumed=? WHERE token_id=?",true,newRow.token());
            apply.run();
            return true;
        });
        if(!Boolean.TRUE.equals(ok)) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_EMAIL_CODE","邮箱验证码错误或已失效，请检查后重试");
    }
    private Challenge lockChallenge(JdbcTemplate db,String address) {
        var rows=db.query("SELECT purpose,owner_id,session_id,token_id,code_hash,expires_at,attempts,consumed FROM email_challenge WHERE email=? FOR UPDATE",(rs,n)->{
            long id=rs.getLong("owner_id"); Long user=rs.wasNull()?null:id;
            return new Challenge(rs.getString("purpose"),user,rs.getString("session_id"),rs.getString("token_id"),rs.getString("code_hash"),rs.getTimestamp("expires_at").toInstant(),rs.getInt("attempts"),rs.getBoolean("consumed"));
        },address);
        return rows.isEmpty()?null:rows.get(0);
    }
    private boolean eligible(Challenge row,String purpose,Long owner,String session) {
        return row!=null && purpose.equals(row.purpose()) && Objects.equals(owner,row.owner()) && Objects.equals(session,row.session()) && !row.consumed() && row.attempts()<5 && row.expires().isAfter(clock.instant());
    }
    private boolean matches(Challenge row,String code) {
        return MessageDigest.isEqual(row.hash().getBytes(StandardCharsets.US_ASCII),sign(row.token()+":"+code).getBytes(StandardCharsets.US_ASCII));
    }
    public void limitPassword(String email,String ip) {
        Instant now=clock.instant(); String client=digest(ip);
        tx.executeWithoutResult(s->{
            var db=transactionDb();
            db.queryForObject("SELECT id FROM email_send_guard WHERE id=1 FOR UPDATE",Integer.class);
            Timestamp since=Timestamp.from(now.minusSeconds(900));
            db.update("DELETE FROM email_password_attempt WHERE created_at<?",since);
            if(count(db,"SELECT COUNT(*) FROM email_password_attempt WHERE email=? AND created_at>?",email,since)>=10 || count(db,"SELECT COUNT(*) FROM email_password_attempt WHERE client_ip=? AND created_at>?",client,since)>=100)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"LOGIN_RATE_LIMIT","登录尝试过于频繁，请稍后再试");
            db.update("INSERT INTO email_password_attempt(email,client_ip,created_at) VALUES (?,?,?)",email,client,Timestamp.from(now));
        });
    }
    // Flex owns the transaction connection; a plain JdbcTemplate would use another connection.
    private JdbcTemplate transactionDb() { return new JdbcTemplate(new SingleConnectionDataSource(sqlSession.getConnection(),true)); }
    private static long count(JdbcTemplate db,String sql,Object... args) { Long n=db.queryForObject(sql,Long.class,args); return n==null?0:n; }
    private String sign(String text) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static String digest(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static BusinessException invalid() { return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","请求参数不合法"); }
}
