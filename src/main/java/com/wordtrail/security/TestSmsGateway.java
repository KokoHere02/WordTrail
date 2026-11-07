package com.wordtrail.security;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicitly enabled for local development; never sends an SMS. */
@Component
@Primary
@Profile("sms-test")
public class TestSmsGateway implements SmsGateway {
    private static final Logger log=LoggerFactory.getLogger(TestSmsGateway.class);
    private static final String TEST_CODE="123456";

    public TestSmsGateway(Environment environment) {
        if(Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production")))
            throw new IllegalStateException("sms-test cannot be used with a production profile");
        log.warn("短信测试模式已启用：使用固定测试验证码，不会发送真实短信；禁止用于生产环境。");
    }

    @Override public void send(String phone,String purpose) { /* Deliberately no provider call. */ }
    @Override public boolean verify(String phone,String code) { return TEST_CODE.equals(code); }
}
