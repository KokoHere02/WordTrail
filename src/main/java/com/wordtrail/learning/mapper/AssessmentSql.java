package com.wordtrail.learning.mapper;

/** Copying feedback is scheduling evidence, not an assessment of familiarity. */
public final class AssessmentSql {
    private AssessmentSql() {}

    // Keep raw queue feedback intact for scheduling and history completeness checks.
    // A copied completion must retain any earlier real assessment on the same item.
    public static final String BEFORE_COPY = """
        (SELECT COALESCE((SELECT c.result FROM word_review_log c
          WHERE c.user_id=a.user_id AND c.batch_id=a.batch_id AND c.batch_item_id=a.batch_item_id
            AND c.corrects_log_id=a.id AND c.event_type='CORRECTION'
          ORDER BY c.id DESC LIMIT 1),a.result)
         FROM word_review_log a
         WHERE a.user_id=#{userId} AND a.batch_id=i.batch_id AND a.batch_item_id=i.id
           AND a.word_id=i.word_id AND a.event_type='ANSWER' AND a.feedback_mode!='FOLLOW_TYPING'
         ORDER BY a.answered_at DESC,a.id DESC LIMIT 1)
        """;
}
