package com.wordtrail.security;

public interface SmsGateway {
    default void validateConfiguration() {}
    void send(String phone, String purpose);
    boolean verify(String phone, String code);
}
