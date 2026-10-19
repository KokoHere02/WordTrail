package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

// Relearning accepts no selection or identity fields. An absent body or {} is sufficient.
public class RelearnRequest {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("重新学习不接受请求字段");
    }
}
