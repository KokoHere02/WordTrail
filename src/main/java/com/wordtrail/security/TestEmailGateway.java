package com.wordtrail.security;

import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("email-test")
public class TestEmailGateway implements EmailGateway {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TestEmailGateway.class);
    public TestEmailGateway(Environment env) {
        if(Arrays.stream(env.getActiveProfiles()).anyMatch(p->p.equalsIgnoreCase("prod")||p.equalsIgnoreCase("production")))
            throw new IllegalStateException("email-test cannot be used with a production profile");
        log.warn("邮箱测试模式已启用：使用固定测试验证码，不会发送真实邮件；禁止用于生产环境。");
    }
    @Override public void validateConfiguration() {}
    @Override public void send(String email,String code) {}
}
