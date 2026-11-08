package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!email-test")
public class SmtpEmailGateway implements EmailGateway {
    private static final Logger log=LoggerFactory.getLogger(SmtpEmailGateway.class);
    private final String host, username, password;
    private final int port;
    public SmtpEmailGateway(@Value("${app.mail.host:smtp.qq.com}") String host,
            @Value("${app.mail.port:465}") int port, @Value("${app.mail.username:}") String username,
            @Value("${app.mail.password:}") String password) {
        this.host=host; this.port=port; this.username=username; this.password=password;
    }
    @Override public void validateConfiguration() {
        if(host.isBlank() || username.isBlank() || password.isBlank() || port<1 || port>65535) {
            log.warn("邮件服务配置不完整或端口无效，请检查 MAIL_HOST、MAIL_PORT、MAIL_USERNAME 和 MAIL_PASSWORD");
            throw unavailable();
        }
    }
    @Override public void send(String email,String code) {
        validateConfiguration();
        Properties p=new Properties();
        p.put("mail.smtp.host",host); p.put("mail.smtp.port",Integer.toString(port));
        p.put("mail.smtp.auth","true"); p.put("mail.smtp.ssl.enable","true");
        p.put("mail.smtp.ssl.checkserveridentity","true");
        p.put("mail.smtp.connectiontimeout","5000"); p.put("mail.smtp.timeout","10000"); p.put("mail.smtp.writetimeout","10000");
        Session session=Session.getInstance(p,new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(username,password); }
        });
        try {
            MimeMessage msg=new MimeMessage(session);
            msg.setFrom(new InternetAddress(username));
            msg.setRecipient(Message.RecipientType.TO,new InternetAddress(email,true));
            msg.setSubject("【拾词】邮箱验证码","UTF-8");
            msg.setText("你的验证码是："+code+"。验证码 5 分钟内有效，请勿转发给他人。", "UTF-8");
            Transport.send(msg);
        } catch (MessagingException ex) {
            log.warn("邮件发送失败: {}",ex.getClass().getSimpleName());
            throw unavailable();
        }
    }
    private static BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_UNAVAILABLE","邮件服务暂时不可用，请稍后重试"); }
}
