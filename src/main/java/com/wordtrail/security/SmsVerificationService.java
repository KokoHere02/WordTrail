package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SmsVerificationService {
    private record Challenge(String purpose, Long ownerId, String sessionId, Instant expiresAt, int attempts, boolean consumed) {}
    private static final Set<String> PURPOSES=Set.of("REGISTER","LOGIN","PASSWORD","CHANGE_OLD","CHANGE_NEW","EMAIL_BIND");
    private final SqlSessionTemplate sqlSession; private final SmsGateway gateway; private final Clock clock;
    private final TransactionTemplate tx; private final int dailyLimit;
    public SmsVerificationService(SqlSessionTemplate sqlSession, SmsGateway gateway, Clock clock, PlatformTransactionManager manager,
            @Value("${app.sms.daily-limit:10}") int dailyLimit) {
        this.sqlSession=sqlSession; this.gateway=gateway; this.clock=clock; this.dailyLimit=dailyLimit;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if(dailyLimit<1) throw new IllegalStateException("SMS daily limit must be positive");
    }
    public void send(String phone,String purpose,Long owner,String session,String ip) {
        if(!PURPOSES.contains(purpose)) throw invalid();
        gateway.validateConfiguration();
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); String client=hash(ip);
        tx.executeWithoutResult(s -> {
            var db=transactionDb();
            db.queryForObject("SELECT id FROM sms_send_guard WHERE id=1 FOR UPDATE",Integer.class);
            Timestamp day=Timestamp.from(now.minusSeconds(86400));
            db.update("DELETE FROM sms_send_log WHERE created_at<?",day);
            db.update("DELETE FROM sms_challenge WHERE expires_at<?",Timestamp.from(now));
            if(count(db,"SELECT COUNT(*) FROM sms_send_log WHERE created_at>?",day)>=dailyLimit ||
               count(db,"SELECT COUNT(*) FROM sms_send_log WHERE phone=? AND created_at>?",phone,day)>=5 ||
               count(db,"SELECT COUNT(*) FROM sms_send_log WHERE client_ip=? AND created_at>?",client,day)>=20 ||
               count(db,"SELECT COUNT(*) FROM sms_send_log WHERE phone=? AND created_at>?",phone,Timestamp.from(now.minusSeconds(60)))>0)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"SMS_RATE_LIMIT","发送过于频繁，请稍后再试");
            db.update("INSERT INTO sms_send_log(phone,client_ip,created_at) VALUES (?,?,?)",phone,client,Timestamp.from(now));
            db.update("DELETE FROM sms_challenge WHERE phone=?",phone);
            db.update("INSERT INTO sms_challenge(phone,purpose,owner_id,session_id,expires_at,attempts,consumed) VALUES (?,?,?,?,?,0,?)",
                phone,purpose,owner,session,Timestamp.from(now.plusSeconds(300)),true);
        });
        try {
            gateway.send(phone,purpose);
        } catch (RuntimeException ex) {
            try {
                tx.executeWithoutResult(s -> {
                    var db=transactionDb();
                    db.update("DELETE FROM sms_send_log WHERE phone=? AND client_ip=? AND created_at=?",phone,client,Timestamp.from(now));
                    db.update("DELETE FROM sms_challenge WHERE phone=? AND purpose=? AND expires_at=? AND consumed=?",
                        phone,purpose,Timestamp.from(now.plusSeconds(300)),true);
                });
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
        // A concurrent send cannot make an earlier request active: the timestamp identifies this reservation.
        tx.executeWithoutResult(s -> transactionDb().update("UPDATE sms_challenge SET consumed=? WHERE phone=? AND purpose=? AND expires_at=?",
            false,phone,purpose,Timestamp.from(now.plusSeconds(300))));
    }
    public void verify(String phone,String purpose,Long owner,String session,String code) {
        Boolean ok=tx.execute(s -> {
            var db=transactionDb();
            var rows=db.query("SELECT purpose,owner_id,session_id,expires_at,attempts,consumed FROM sms_challenge WHERE phone=? FOR UPDATE",
                (rs, rowNumber) -> {
                    long ownerId=rs.getLong("owner_id");
                    Long nullableOwnerId=rs.wasNull()?null:ownerId;
                    return new Challenge(rs.getString("purpose"),nullableOwnerId,rs.getString("session_id"),
                        rs.getTimestamp("expires_at").toInstant(),rs.getInt("attempts"),rs.getBoolean("consumed"));
                },phone);
            if(rows.isEmpty()) return false;
            Challenge row=rows.get(0);
            if(!purpose.equals(row.purpose()) || !java.util.Objects.equals(owner,row.ownerId()) ||
                !java.util.Objects.equals(session,row.sessionId()) || row.consumed() ||
                row.attempts()>=5 || !row.expiresAt().isAfter(clock.instant())) return false;
            db.update("UPDATE sms_challenge SET attempts=attempts+1 WHERE phone=?",phone);
            boolean passed=gateway.verify(phone,code);
            if(passed) db.update("UPDATE sms_challenge SET consumed=? WHERE phone=?",true,phone);
            return passed;
        });
        if(!Boolean.TRUE.equals(ok)) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_SMS_CODE","验证码错误或已失效，请重新获取");
    }
    public void limitPassword(String phone,String ip) {
        Instant now=clock.instant(); String client=hash(ip);
        tx.executeWithoutResult(s -> {
            var db=transactionDb();
            db.queryForObject("SELECT id FROM sms_send_guard WHERE id=1 FOR UPDATE",Integer.class);
            Timestamp since=Timestamp.from(now.minusSeconds(900));
            db.update("DELETE FROM auth_password_attempt WHERE created_at<?",since);
            if(count(db,"SELECT COUNT(*) FROM auth_password_attempt WHERE phone=? AND created_at>?",phone,since)>=10 ||
               count(db,"SELECT COUNT(*) FROM auth_password_attempt WHERE client_ip=? AND created_at>?",client,since)>=100)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"LOGIN_RATE_LIMIT","登录尝试过于频繁，请稍后再试");
            db.update("INSERT INTO auth_password_attempt(phone,client_ip,created_at) VALUES (?,?,?)",phone,client,Timestamp.from(now));
        });
    }
    private JdbcTemplate transactionDb() { return new JdbcTemplate(new SingleConnectionDataSource(sqlSession.getConnection(),true)); }
    private static long count(JdbcTemplate db,String sql,Object... args) { Long n=db.queryForObject(sql,Long.class,args); return n==null?0:n; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    private static BusinessException invalid(){return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","请求参数不合法");}
}
