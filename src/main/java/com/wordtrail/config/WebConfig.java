package com.wordtrail.config;

import com.wordtrail.security.AuthInterceptor;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authentication;
    private final String[] origins;

    public WebConfig(AuthInterceptor authentication,
            @Value("${app.cors.allowed-origins:}") String configuredOrigins) {
        this.authentication = authentication;
        this.origins = Arrays.stream(configuredOrigins.split(",")).map(String::trim)
            .filter(value -> !value.isEmpty()).toArray(String[]::new);
        for (String origin : origins) {
            URI uri;
            try { uri = URI.create(origin); }
            catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid CORS origin"); }
            if (origin.contains("*") || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalStateException("CORS origins must be explicit HTTP(S) origins without paths or wildcards");
            }
        }
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authentication).addPathPatterns("/**");
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origins)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type").allowCredentials(false).maxAge(3600);
    }

    @Bean public static Clock authClock() { return Clock.systemUTC(); }
}
