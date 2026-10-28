package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

// Shared endpoints accept an absent body or {}, and reject client-supplied fields.
public class EmptyRequest {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("此接口不接受请求字段");
    }
}
