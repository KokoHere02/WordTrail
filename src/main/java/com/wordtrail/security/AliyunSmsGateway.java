package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AliyunSmsGateway implements SmsGateway {
    private static final Logger log=LoggerFactory.getLogger(AliyunSmsGateway.class);
    private final String key, secret, sign;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    public AliyunSmsGateway(@Value("${app.sms.access-key-id:}") String key,
            @Value("${app.sms.access-key-secret:}") String secret,
            @Value("${app.sms.sign-name:}") String sign, ObjectMapper json) {
        this.key=key; this.secret=secret; this.sign=sign; this.json=json;
    }
    @Override public void validateConfiguration() {
        if(key.isBlank() || secret.isBlank() || sign.isBlank()) {
            log.warn("短信认证配置不完整: accessKeyId={}, accessKeySecret={}, signName={}；请检查后端运行环境变量",
                key.isBlank()?"未配置":"已配置", secret.isBlank()?"未配置":"已配置", sign.isBlank()?"未配置":"已配置");
            throw unavailable();
        }
    }
    @Override public void send(String phone, String purpose) {
        String template = switch (purpose) {
            case "REGISTER", "LOGIN" -> "100001";
            case "PASSWORD", "EMAIL_BIND" -> "100003";
            case "CHANGE_OLD" -> "100005";
            case "CHANGE_NEW" -> "100004";
            default -> throw new IllegalArgumentException("Unsupported SMS purpose");
        };
        call("SendSmsVerifyCode", Map.of("PhoneNumber",phone,"SignName",sign,"TemplateCode",template,
            "TemplateParam","{\"code\":\"##code##\",\"min\":\"5\"}","CodeType","1","CodeLength","6",
            "ValidTime","300","Interval","60","DuplicatePolicy","1","ReturnVerifyCode","false"));
    }
    @Override public boolean verify(String phone, String code) {
        JsonNode result=call("CheckSmsVerifyCode",Map.of("PhoneNumber",phone,"VerifyCode",code));
        return "PASS".equals(result.path("Model").path("VerifyResult").asText());
    }
    private JsonNode call(String action, Map<String,String> args) {
        validateConfiguration();
        try {
            var p=new TreeMap<String,String>(args);
            p.put("Action",action); p.put("Version","2017-05-25"); p.put("Format","JSON");
            p.put("AccessKeyId",key); p.put("SignatureMethod","HMAC-SHA1"); p.put("SignatureVersion","1.0");
            p.put("SignatureNonce",UUID.randomUUID().toString()); p.put("Timestamp",Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            String canonical=encodePairs(p);
            Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec((secret+"&").getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
            p.put("Signature",Base64.getEncoder().encodeToString(mac.doFinal(("POST&%2F&"+enc(canonical)).getBytes(StandardCharsets.UTF_8))));
            var request=HttpRequest.newBuilder(URI.create("https://dypnsapi.aliyuncs.com/"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodePairs(p))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            var data=json.readTree(response.body());
            if(response.statusCode()!=200 || !"OK".equals(data.path("Code").asText()) || !data.path("Success").asBoolean()) {
                log.warn("阿里云短信认证调用失败: action={}, httpStatus={}, code={}, requestId={}",
                    action, response.statusCode(), safeCode(data.path("Code").asText()), safeCode(data.path("RequestId").asText()));
                throw unavailable();
            }
            return data;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); log.warn("阿里云短信认证请求被中断: action={}",action); throw unavailable(); }
          catch (BusinessException ex) { throw ex; }
          catch (Exception ex) { log.warn("阿里云短信认证请求异常: action={}, exception={}",action,ex.getClass().getSimpleName()); throw unavailable(); }
    }
    private static String encodePairs(Map<String,String> p) {
        return p.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~"); }
    private static String safeCode(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{1,80}") ? value : "unknown"; }
    private static BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"SMS_UNAVAILABLE","短信服务暂时不可用，请稍后重试"); }
}
