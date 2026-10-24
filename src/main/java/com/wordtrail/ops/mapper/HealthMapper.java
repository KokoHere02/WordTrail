package com.wordtrail.ops.mapper;
import org.apache.ibatis.annotations.*;
public interface HealthMapper {
    @Select("""
        SELECT COUNT(*) FROM user_account u
        LEFT JOIN user_auth_session s ON s.user_id=u.id
        LEFT JOIN user_sentence_note n ON n.user_id=u.id
        WHERE u.id=-1 AND u.auth_version>=0
        """)
    @Options(timeout=2) long ready();
}
