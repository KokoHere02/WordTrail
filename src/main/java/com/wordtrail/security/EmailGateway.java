package com.wordtrail.security;

public interface EmailGateway {
    void validateConfiguration();
    void send(String email, String code);
}
