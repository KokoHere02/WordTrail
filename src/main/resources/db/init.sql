-- WordTrail complete empty-database schema, consolidated through migration 013.
-- MySQL 8.0.16+; generated and verified against MySQL 8.0 on 2026-10-29.
-- Select an EMPTY database before running; import manually before starting the app.
-- Do not run this initializer on an existing installation or run old migrations afterwards.
-- All 25 tables are created directly at their latest structure. No business data is copied.
-- Application test account: username=test, password=Test123456 (BCrypt cost 12).
-- No words, books, sentences, settings, tokens, sessions or learning history are seeded.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_question` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `english_text` varchar(2000) NOT NULL,
  `reference_translation` varchar(2000) NOT NULL,
  `explanation` varchar(4000) NOT NULL,
  `difficulty` tinyint unsigned NOT NULL DEFAULT '1',
  `source` varchar(255) NOT NULL,
  `source_reference` varchar(512) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `content_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (sha2(trim(`english_text`),256)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_content_hash` (`content_hash`),
  KEY `idx_sentence_status_difficulty` (`status`,`difficulty`,`id`),
  CONSTRAINT `chk_sentence_content` CHECK (((char_length(trim(`english_text`)) > 0) and (char_length(trim(`reference_translation`)) > 0) and (char_length(trim(`explanation`)) > 0) and (char_length(trim(`source`)) > 0))),
  CONSTRAINT `chk_sentence_difficulty` CHECK ((`difficulty` between 1 and 3)),
  CONSTRAINT `chk_sentence_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'INACTIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(64) NOT NULL COMMENT '登录账号',
  `password_hash` varchar(255) NOT NULL COMMENT '密码哈希，如BCrypt',
  `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `auth_version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE `word` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '单词ID',
  `word` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '单词或短语，区分大小写',
  `phonetic` varchar(255) DEFAULT NULL COMMENT '音标',
  `translation` text COMMENT '中文释义，保留原有词性标记',
  `definition` text COMMENT '英文释义',
  `pos` varchar(255) DEFAULT NULL COMMENT '原始词性信息，允许为空',
  `tag` varchar(255) DEFAULT NULL COMMENT '原始标签，如 cet4 cet6',
  `bnc` int unsigned DEFAULT NULL COMMENT 'BNC词频排名',
  `frq` int unsigned DEFAULT NULL COMMENT '当代语料库词频排名',
  `exchange` text COMMENT '原始词形变化，如 p:worked/d:worked/i:working',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单词基础表';

CREATE TABLE `word_book` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '词书ID',
  `name` varchar(128) NOT NULL COMMENT '词书名称',
  `description` text COMMENT '词书说明',
  `owner_id` bigint unsigned DEFAULT NULL COMMENT '所属用户ID，NULL表示系统词书',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='词书表';

CREATE TABLE `learning_batch` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '批次ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `book_id` bigint unsigned DEFAULT NULL COMMENT '来源词书ID，每日任务可跨词书，因此允许为空',
  `selection_type` varchar(20) NOT NULL COMMENT 'BOOK词书分配；MANUAL手动指定；DAILY每日计划生成',
  `status` varchar(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS：进行中；COMPLETED：已完成；ABANDONED：已放弃',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `task_type` varchar(20) NOT NULL DEFAULT 'PRACTICE' COMMENT '任务类型：DAILY每日学习；PRACTICE专项练习',
  `learning_date` date DEFAULT NULL COMMENT '每日任务所属日期，北京时间；专项练习为空',
  `daily_target` int unsigned DEFAULT NULL COMMENT '创建每日任务时采用的目标词数，专项练习为空',
  `practice_mode` varchar(20) NOT NULL DEFAULT 'RECOGNITION',
  `study_mode` varchar(20) NOT NULL DEFAULT 'RECOGNITION' COMMENT '每日任务当前展示方式，不改变批次词单',
  `mode_version` bigint unsigned NOT NULL DEFAULT '0' COMMENT '切换方式版本，拒绝旧页面作答',
  `new_word_target` int unsigned DEFAULT NULL COMMENT '建任务时新词配额快照；旧批次NULL',
  `review_limit` int unsigned DEFAULT NULL COMMENT '建任务时复习配额快照；旧批次NULL',
  `creation_request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '集中巩固创建幂等标识',
  `creation_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '集中巩固创建内容SHA256',
  `consolidation_reason` varchar(30) DEFAULT NULL COMMENT '创建集中巩固时的错因筛选',
  `follow_typing_target` smallint unsigned DEFAULT NULL COMMENT '独立跟拼申请词数10到20，实际词数可因配额或冷却不足',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_task_date` (`user_id`,`task_type`,`learning_date`),
  UNIQUE KEY `uk_batch_creation` (`user_id`,`creation_request_id`),
  KEY `idx_user_status_created` (`user_id`,`status`,`created_at`),
  KEY `idx_book_id` (`book_id`),
  KEY `idx_batch_follow_selection` (`user_id`,`practice_mode`,`created_at`),
  CONSTRAINT `fk_learning_batch_book` FOREIGN KEY (`book_id`) REFERENCES `word_book` (`id`),
  CONSTRAINT `fk_learning_batch_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_batch_allocation` CHECK ((((`new_word_target` is null) and (`review_limit` is null)) or ((`task_type` = _utf8mb4'DAILY') and (`new_word_target` is not null) and (`review_limit` is not null) and (`new_word_target` <= 200) and (`review_limit` <= 200) and ((`new_word_target` + `review_limit`) = `daily_target`)))),
  CONSTRAINT `chk_batch_consolidation` CHECK ((((`practice_mode` in (_utf8mb4'CONSOLIDATION',_utf8mb4'FOLLOW_TYPING')) and (`creation_request_id` is not null) and (`creation_fingerprint` is not null)) or ((`practice_mode` not in (_utf8mb4'CONSOLIDATION',_utf8mb4'FOLLOW_TYPING')) and (`creation_request_id` is null) and (`creation_fingerprint` is null) and (`consolidation_reason` is null)))),
  CONSTRAINT `chk_batch_consolidation_reason` CHECK (((`consolidation_reason` is null) or (`consolidation_reason` in (_utf8mb4'SPELLING_ERROR',_utf8mb4'ANSWER_REVEALED',_utf8mb4'UNRECOGNIZED',_utf8mb4'ATTEMPT_LIMIT',_utf8mb4'PENDING_VERIFICATION')))),
  CONSTRAINT `chk_batch_follow_typing` CHECK ((((`practice_mode` = _utf8mb4'FOLLOW_TYPING') and (`follow_typing_target` is not null) and (`follow_typing_target` between 10 and 20) and (`study_mode` = _utf8mb4'FOLLOW_TYPING') and (`mode_version` = 0) and (`book_id` is null) and (`consolidation_reason` is null)) or ((`practice_mode` <> _utf8mb4'FOLLOW_TYPING') and (`follow_typing_target` is null)))),
  CONSTRAINT `chk_batch_practice_mode` CHECK (((`practice_mode` = _utf8mb4'RECOGNITION') or ((`practice_mode` in (_utf8mb4'DICTATION',_utf8mb4'CONSOLIDATION',_utf8mb4'FOLLOW_TYPING')) and (`task_type` = _utf8mb4'PRACTICE') and (`selection_type` = _utf8mb4'MANUAL')))),
  CONSTRAINT `chk_batch_selection_type` CHECK ((`selection_type` in (_utf8mb4'BOOK',_utf8mb4'MANUAL',_utf8mb4'DAILY'))),
  CONSTRAINT `chk_batch_status` CHECK ((`status` in (_utf8mb4'IN_PROGRESS',_utf8mb4'COMPLETED',_utf8mb4'ABANDONED'))),
  CONSTRAINT `chk_batch_study_mode` CHECK ((`study_mode` in (_utf8mb4'RECOGNITION',_utf8mb4'DICTATION',_utf8mb4'FOLLOW_TYPING',_utf8mb4'LISTEN_DICTATION'))),
  CONSTRAINT `chk_batch_task_fields` CHECK ((((`task_type` = _utf8mb4'DAILY') and (`selection_type` = _utf8mb4'DAILY') and (`learning_date` is not null) and (`daily_target` is not null) and (`daily_target` > 0)) or ((`task_type` = _utf8mb4'PRACTICE') and (`selection_type` in (_utf8mb4'BOOK',_utf8mb4'MANUAL')) and (`learning_date` is null) and (`daily_target` is null)))),
  CONSTRAINT `chk_batch_task_type` CHECK ((`task_type` in (_utf8mb4'DAILY',_utf8mb4'PRACTICE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习批次表';

CREATE TABLE `sentence_import_batch` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `content_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `row_count` smallint unsigned NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_import_request` (`user_id`,`request_id`),
  CONSTRAINT `fk_sentence_import_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_sentence_import_count` CHECK ((`row_count` between 1 and 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_translation_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `selection_source` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'IN_PROGRESS',
  `created_at` datetime(3) NOT NULL,
  `completed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_session_user` (`id`,`user_id`),
  KEY `idx_sentence_session_list` (`user_id`,`status`,`created_at`,`id`),
  CONSTRAINT `fk_sentence_session_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_sentence_session_source` CHECK ((`selection_source` in (_utf8mb4'BANK',_utf8mb4'REVIEW',_utf8mb4'FAVORITES',_utf8mb4'DUE'))),
  CONSTRAINT `chk_sentence_session_status` CHECK ((((`status` = _utf8mb4'IN_PROGRESS') and (`completed_at` is null)) or ((`status` = _utf8mb4'COMPLETED') and (`completed_at` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_word` (
  `sentence_id` bigint unsigned NOT NULL,
  `word_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`sentence_id`,`word_id`),
  KEY `idx_sentence_word` (`word_id`,`sentence_id`),
  CONSTRAINT `fk_sentence_word_sentence` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`),
  CONSTRAINT `fk_sentence_word_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_auth_session` (
  `id` char(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `device_name` varchar(100) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_refreshed_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_session_owner` (`id`,`user_id`),
  KEY `idx_auth_session_user` (`user_id`,`revoked_at`,`expires_at`),
  CONSTRAINT `fk_auth_session_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_auth_session_expiry` CHECK (((`expires_at` > `created_at`) and (`last_refreshed_at` >= `created_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_learning_setting` (
  `user_id` bigint unsigned NOT NULL COMMENT '当前用户；每个用户至多一份学习设置',
  `daily_target` smallint unsigned NOT NULL DEFAULT '20' COMMENT '每日不同单词目标，含新学和复习，不含强化重现',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `preferred_study_mode` varchar(20) NOT NULL DEFAULT 'RECOGNITION' COMMENT '下次每日任务默认学习方式',
  `new_word_target` int unsigned DEFAULT NULL COMMENT '每日新词配额；NULL保留旧版选词策略',
  `review_limit` int unsigned DEFAULT NULL COMMENT '每日复习配额，和新词配额分别预留',
  `audio_auto_play` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '用户偏好自动播放，浏览器可能要求手势',
  `audio_accent` varchar(2) NOT NULL DEFAULT 'US' COMMENT 'US美音或UK英音',
  `audio_playback_rate` decimal(3,2) NOT NULL DEFAULT '1.00' COMMENT '客户端音频播放倍率0.50到1.50',
  `audio_repeat_count` int unsigned NOT NULL DEFAULT '1' COMMENT '客户端每题重复播放1到5次',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_learning_setting_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_learning_setting_daily_target` CHECK ((`daily_target` between 1 and 200)),
  CONSTRAINT `chk_setting_allocation` CHECK ((((`new_word_target` is null) and (`review_limit` is null)) or ((`new_word_target` is not null) and (`review_limit` is not null) and (`new_word_target` <= 200) and (`review_limit` <= 200) and ((`new_word_target` + `review_limit`) = `daily_target`) and (`daily_target` between 1 and 200)))),
  CONSTRAINT `chk_setting_audio` CHECK (((`audio_auto_play` in (0,1)) and (`audio_accent` in (_utf8mb4'US',_utf8mb4'UK')) and (`audio_playback_rate` between 0.50 and 1.50) and (`audio_repeat_count` between 1 and 5))),
  CONSTRAINT `chk_setting_study_mode` CHECK ((`preferred_study_mode` in (_utf8mb4'RECOGNITION',_utf8mb4'DICTATION',_utf8mb4'FOLLOW_TYPING',_utf8mb4'LISTEN_DICTATION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户轻量学习设置；无记录时应用默认每日20词';

CREATE TABLE `user_sentence_favorite` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_favorite` (`user_id`,`sentence_id`),
  KEY `fk_sentence_favorite_question` (`sentence_id`),
  CONSTRAINT `fk_sentence_favorite_question` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`),
  CONSTRAINT `fk_sentence_favorite_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `word_book_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '关联ID',
  `book_id` bigint unsigned NOT NULL COMMENT '词书ID',
  `word_id` bigint unsigned NOT NULL COMMENT '单词ID',
  `sort_order` int unsigned NOT NULL DEFAULT '0' COMMENT '学习顺序，越小越靠前',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_book_word` (`book_id`,`word_id`),
  KEY `idx_book_order` (`book_id`,`sort_order`,`id`),
  KEY `idx_word_id` (`word_id`),
  CONSTRAINT `fk_book_item_book` FOREIGN KEY (`book_id`) REFERENCES `word_book` (`id`),
  CONSTRAINT `fk_book_item_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='词书单词关联表';

CREATE TABLE `learning_batch_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `batch_id` bigint unsigned NOT NULL COMMENT '所属批次ID',
  `word_id` bigint unsigned NOT NULL COMMENT '单词ID',
  `sort_order` int unsigned NOT NULL COMMENT '展示顺序，从1开始',
  `result` tinyint unsigned DEFAULT NULL COMMENT '最近反馈：NULL未回答；1认识；2模糊；3不认识',
  `answered_at` datetime DEFAULT NULL COMMENT '最近一次提交答案的时间',
  `item_type` varchar(20) DEFAULT NULL COMMENT 'NEW新学；REVIEW复习；历史专项练习可为空',
  `learning_status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待学；RELEARNING强化中；DONE当天流程完成',
  `queue_order` bigint unsigned NOT NULL DEFAULT '0' COMMENT '当前队列位置，重现时调整；sort_order保留初始顺序',
  `answer_count` int unsigned NOT NULL DEFAULT '0' COMMENT '本明细正常作答次数，不包含更正事件',
  `relearn_count` int unsigned NOT NULL DEFAULT '0' COMMENT '当天额外强化作答次数，不包含首次作答及更正',
  `completed_at` datetime DEFAULT NULL COMMENT '结束本词当天学习流程的时间',
  `status_before_skip` varchar(20) DEFAULT NULL COMMENT '跳过前 PENDING/RELEARNING/DONE，用于当天未结束任务恢复',
  `skipped_at` datetime DEFAULT NULL COMMENT '本次跳过时间，北京时间',
  `reinforcement_remaining` tinyint unsigned DEFAULT NULL COMMENT '当天剩余认识验证次数；旧记录未记录时NULL',
  `completion_reason` varchar(20) DEFAULT NULL COMMENT 'RECALLED完成回忆；LIMIT_REACHED达到当天上限；旧记录NULL',
  `dictation_target` varchar(255) DEFAULT NULL,
  `dictation_translation` text,
  `dictation_had_failure` tinyint unsigned DEFAULT NULL,
  `dictation_correct_streak` tinyint unsigned DEFAULT NULL,
  `dictation_presentation_id` varchar(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `study_word` varchar(255) DEFAULT NULL COMMENT '统一每日训练英文快照，旧题首次展示时捕获',
  `study_translation` text COMMENT '统一每日训练释义快照',
  `study_phonetic` varchar(255) DEFAULT NULL COMMENT '统一每日训练音标快照',
  `study_hint_used` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '本任务该词曾展示目标拼写或参考答案',
  `study_attempt_count` int unsigned NOT NULL DEFAULT '0' COMMENT '统一训练事件序号，包含揭示和追加训练',
  `study_presentation_id` varchar(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前有效展示标识',
  `study_presentation_version` bigint unsigned DEFAULT NULL COMMENT '展示时的模式版本',
  `study_extra_training` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '当前展示是否为已完成词追加训练',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_word` (`batch_id`,`word_id`),
  UNIQUE KEY `uk_batch_order` (`batch_id`,`sort_order`),
  KEY `idx_word_id` (`word_id`),
  KEY `idx_batch_learning_queue` (`batch_id`,`learning_status`,`queue_order`,`id`),
  CONSTRAINT `fk_learning_batch_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `learning_batch` (`id`),
  CONSTRAINT `fk_learning_batch_item_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`),
  CONSTRAINT `chk_batch_item_order` CHECK ((`sort_order` >= 1)),
  CONSTRAINT `chk_batch_item_result` CHECK (((`result` is null) or (`result` in (1,2,3)))),
  CONSTRAINT `chk_item_answer_counts` CHECK ((`relearn_count` <= `answer_count`)),
  CONSTRAINT `chk_item_before_skip` CHECK (((`status_before_skip` is null) or (`status_before_skip` in (_utf8mb4'PENDING',_utf8mb4'RELEARNING',_utf8mb4'DONE')))),
  CONSTRAINT `chk_item_completion_reason` CHECK (((`completion_reason` is null) or (`completion_reason` in (_utf8mb4'RECALLED',_utf8mb4'LIMIT_REACHED',_utf8mb4'FOLLOW_TYPED',_utf8mb4'ASSISTED')))),
  CONSTRAINT `chk_item_dictation` CHECK ((((`dictation_target` is null) and (`dictation_translation` is null) and (`dictation_had_failure` is null) and (`dictation_correct_streak` is null) and (`dictation_presentation_id` is null)) or ((`dictation_target` is not null) and (`dictation_translation` is not null) and (`dictation_had_failure` is not null) and (`dictation_had_failure` in (0,1)) and (`dictation_correct_streak` is not null) and (`dictation_correct_streak` between 0 and 2) and (`result` is null)))),
  CONSTRAINT `chk_item_learning_status` CHECK ((`learning_status` in (_utf8mb4'PENDING',_utf8mb4'RELEARNING',_utf8mb4'DONE',_utf8mb4'SKIPPED'))),
  CONSTRAINT `chk_item_reinforcement_remaining` CHECK (((`reinforcement_remaining` is null) or (`reinforcement_remaining` between 0 and 3))),
  CONSTRAINT `chk_item_skip_fields` CHECK ((((`learning_status` = _utf8mb4'SKIPPED') and (`status_before_skip` is not null) and (`skipped_at` is not null)) or ((`learning_status` <> _utf8mb4'SKIPPED') and (`status_before_skip` is null) and (`skipped_at` is null)))),
  CONSTRAINT `chk_item_study_flags` CHECK (((`study_hint_used` in (0,1)) and (`study_extra_training` in (0,1)))),
  CONSTRAINT `chk_item_study_presentation` CHECK ((((`study_presentation_id` is null) and (`study_presentation_version` is null)) or ((`study_presentation_id` is not null) and (`study_presentation_version` is not null)))),
  CONSTRAINT `chk_item_type` CHECK (((`item_type` is null) or (`item_type` in (_utf8mb4'NEW',_utf8mb4'REVIEW'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习批次明细表';

CREATE TABLE `refresh_token` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '登录凭证ID',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID',
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Refresh Token的SHA-256哈希，不保存原始Token',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `revoked_at` datetime DEFAULT NULL COMMENT '撤销时间，NULL表示未撤销',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `session_id` char(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token_hash` (`token_hash`),
  KEY `idx_user_revoked` (`user_id`,`revoked_at`),
  KEY `idx_expires_at` (`expires_at`),
  KEY `idx_refresh_session` (`session_id`),
  KEY `fk_refresh_session` (`session_id`,`user_id`),
  CONSTRAINT `fk_refresh_session` FOREIGN KEY (`session_id`, `user_id`) REFERENCES `user_auth_session` (`id`, `user_id`),
  CONSTRAINT `fk_refresh_token_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Refresh Token登录凭证表';

CREATE TABLE `sentence_import_item` (
  `batch_id` bigint unsigned NOT NULL,
  `csv_row_number` int unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`batch_id`,`csv_row_number`),
  UNIQUE KEY `uk_sentence_import_question` (`sentence_id`),
  CONSTRAINT `fk_sentence_import_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `sentence_import_batch` (`id`),
  CONSTRAINT `fk_sentence_import_item_question` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_translation_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  `sort_order` int unsigned NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `english_text` varchar(2000) NOT NULL,
  `reference_translation` varchar(2000) NOT NULL,
  `explanation` varchar(4000) NOT NULL,
  `difficulty` tinyint unsigned NOT NULL,
  `source` varchar(255) NOT NULL,
  `source_reference` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_item` (`session_id`,`sentence_id`),
  UNIQUE KEY `uk_sentence_item_order` (`session_id`,`sort_order`),
  UNIQUE KEY `uk_sentence_item_identity` (`id`,`session_id`,`sentence_id`),
  KEY `fk_sentence_item_question` (`sentence_id`),
  CONSTRAINT `fk_sentence_item_question` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`),
  CONSTRAINT `fk_sentence_item_session` FOREIGN KEY (`session_id`) REFERENCES `sentence_translation_session` (`id`),
  CONSTRAINT `chk_sentence_item_content` CHECK (((char_length(trim(`english_text`)) > 0) and (char_length(trim(`reference_translation`)) > 0) and (char_length(trim(`explanation`)) > 0) and (char_length(trim(`source`)) > 0) and (`difficulty` between 1 and 3))),
  CONSTRAINT `chk_sentence_item_order` CHECK ((`sort_order` between 1 and 50)),
  CONSTRAINT `chk_sentence_item_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'AWAITING_REVIEW',_utf8mb4'DONE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `dictation_review_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `word_id` bigint unsigned NOT NULL,
  `batch_id` bigint unsigned NOT NULL,
  `batch_item_id` bigint unsigned NOT NULL,
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `presentation_id` varchar(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event_type` varchar(20) NOT NULL,
  `input_text` varchar(255) DEFAULT NULL,
  `verdict` varchar(20) NOT NULL,
  `attempt_no` int unsigned NOT NULL,
  `learning_date` date NOT NULL,
  `answered_at` datetime(3) NOT NULL,
  `correct_streak_before` tinyint unsigned NOT NULL,
  `correct_streak_after` tinyint unsigned NOT NULL,
  `had_failure_after` tinyint unsigned NOT NULL,
  `item_status_after` varchar(20) NOT NULL,
  `completion_reason_after` varchar(20) DEFAULT NULL,
  `affects_schedule` tinyint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dictation_request` (`user_id`,`request_id`),
  UNIQUE KEY `uk_dictation_attempt` (`batch_item_id`,`attempt_no`),
  UNIQUE KEY `uk_dictation_presentation` (`user_id`,`presentation_id`),
  KEY `idx_dictation_user_word_time` (`user_id`,`word_id`,`answered_at`,`id`),
  KEY `idx_dictation_user_date` (`user_id`,`learning_date`),
  KEY `idx_dictation_batch` (`batch_id`,`id`),
  KEY `fk_dictation_word` (`word_id`),
  CONSTRAINT `fk_dictation_batch` FOREIGN KEY (`batch_id`) REFERENCES `learning_batch` (`id`),
  CONSTRAINT `fk_dictation_item` FOREIGN KEY (`batch_item_id`) REFERENCES `learning_batch_item` (`id`),
  CONSTRAINT `fk_dictation_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_dictation_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`),
  CONSTRAINT `chk_dictation_event` CHECK ((((`event_type` = _utf8mb4'ANSWER') and (`input_text` is not null) and (`verdict` in (_utf8mb4'CORRECT',_utf8mb4'INCORRECT'))) or ((`event_type` = _utf8mb4'REVEAL') and (`input_text` is null) and (`verdict` = _utf8mb4'REVEALED')))),
  CONSTRAINT `chk_dictation_state` CHECK (((`attempt_no` between 1 and 6) and (`correct_streak_before` between 0 and 2) and (`correct_streak_after` between 0 and 2) and (`had_failure_after` in (0,1)) and (`item_status_after` in (_utf8mb4'RELEARNING',_utf8mb4'DONE')) and (`affects_schedule` = 0) and (((`item_status_after` = _utf8mb4'RELEARNING') and (`completion_reason_after` is null)) or ((`item_status_after` = _utf8mb4'DONE') and (`completion_reason_after` is not null) and (`completion_reason_after` in (_utf8mb4'RECALLED',_utf8mb4'LIMIT_REACHED'))))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_item_word` (
  `item_id` bigint unsigned NOT NULL,
  `word_id` bigint unsigned NOT NULL,
  `word` varchar(255) NOT NULL,
  PRIMARY KEY (`item_id`,`word_id`),
  CONSTRAINT `fk_sentence_item_word_item` FOREIGN KEY (`item_id`) REFERENCES `sentence_translation_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sentence_translation_attempt` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `session_id` bigint unsigned NOT NULL,
  `item_id` bigint unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  `submit_request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `action` varchar(20) NOT NULL,
  `translation` varchar(2000) DEFAULT NULL,
  `learning_date` date NOT NULL,
  `submitted_at` datetime(3) NOT NULL,
  `self_assessment` varchar(20) DEFAULT NULL,
  `review_request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `review_date` date DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_attempt_item` (`item_id`),
  UNIQUE KEY `uk_sentence_submit_request` (`user_id`,`submit_request_id`),
  UNIQUE KEY `uk_sentence_attempt_note_identity` (`id`,`user_id`,`sentence_id`),
  UNIQUE KEY `uk_sentence_review_request` (`user_id`,`review_request_id`),
  KEY `idx_sentence_attempt_history` (`user_id`,`sentence_id`,`submitted_at`,`id`),
  KEY `idx_sentence_attempt_latest` (`user_id`,`sentence_id`,`id`),
  KEY `idx_sentence_attempt_date` (`user_id`,`learning_date`),
  KEY `idx_sentence_attempt_session` (`session_id`,`id`),
  KEY `fk_sentence_attempt_user_session` (`session_id`,`user_id`),
  KEY `fk_sentence_attempt_item` (`item_id`,`session_id`,`sentence_id`),
  CONSTRAINT `fk_sentence_attempt_item` FOREIGN KEY (`item_id`, `session_id`, `sentence_id`) REFERENCES `sentence_translation_item` (`id`, `session_id`, `sentence_id`),
  CONSTRAINT `fk_sentence_attempt_user_session` FOREIGN KEY (`session_id`, `user_id`) REFERENCES `sentence_translation_session` (`id`, `user_id`),
  CONSTRAINT `chk_sentence_self_review` CHECK ((((`self_assessment` is null) and (`review_request_id` is null) and (`review_date` is null) and (`reviewed_at` is null)) or ((`self_assessment` is not null) and (`self_assessment` in (_utf8mb4'UNDERSTOOD',_utf8mb4'PARTIAL',_utf8mb4'NOT_UNDERSTOOD')) and (`review_request_id` is not null) and (`review_date` is not null) and (`reviewed_at` is not null) and (`reviewed_at` >= `submitted_at`)))),
  CONSTRAINT `chk_sentence_submission` CHECK ((((`action` = _utf8mb4'ANSWER') and (`translation` is not null) and (char_length(trim(`translation`)) > 0)) or ((`action` = _utf8mb4'REVEAL') and (`translation` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_word_state` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '状态ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `word_id` bigint unsigned NOT NULL COMMENT '单词ID',
  `last_result` tinyint unsigned DEFAULT NULL COMMENT '最近反馈：NULL未学习；1认识；2模糊；3不认识',
  `last_batch_item_id` bigint unsigned DEFAULT NULL COMMENT '最近作答明细ID，尚未学习时为空',
  `first_learned_at` datetime DEFAULT NULL COMMENT '首次学习时间，尚未学习时为空',
  `last_learned_at` datetime DEFAULT NULL COMMENT '最近学习时间，尚未学习时为空',
  `plan_status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '计划状态：ACTIVE有效；PAUSED暂停；REMOVED移出',
  `joined_at` datetime NOT NULL COMMENT '首次加入学习计划时间',
  `next_review_at` datetime DEFAULT NULL COMMENT '下次复习时间，尚未学习时为空',
  `review_level` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '复习间隔档位：0至6对应1、3、7、15、30、60、90天',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_word` (`user_id`,`word_id`),
  KEY `idx_user_result_time` (`user_id`,`last_result`,`last_learned_at`),
  KEY `idx_word_id` (`word_id`),
  KEY `idx_last_batch_item_id` (`last_batch_item_id`),
  KEY `idx_user_plan_due` (`user_id`,`plan_status`,`next_review_at`),
  KEY `idx_user_plan_first_learned` (`user_id`,`plan_status`,`first_learned_at`),
  CONSTRAINT `fk_user_word_state_item` FOREIGN KEY (`last_batch_item_id`) REFERENCES `learning_batch_item` (`id`),
  CONSTRAINT `fk_user_word_state_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_user_word_state_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`),
  CONSTRAINT `chk_user_word_last_result` CHECK ((`last_result` in (1,2,3))),
  CONSTRAINT `chk_word_state_plan_status` CHECK ((`plan_status` in (_utf8mb4'ACTIVE',_utf8mb4'PAUSED',_utf8mb4'REMOVED'))),
  CONSTRAINT `chk_word_state_review_level` CHECK ((`review_level` between 0 and 6))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户单词最新学习状态表';

CREATE TABLE `word_review_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `word_id` bigint unsigned NOT NULL COMMENT '单词ID',
  `batch_id` bigint unsigned NOT NULL COMMENT '批次ID',
  `batch_item_id` bigint unsigned NOT NULL COMMENT '批次明细ID',
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端请求幂等标识，同次请求重试必须复用',
  `event_type` varchar(20) NOT NULL DEFAULT 'ANSWER' COMMENT 'ANSWER正常作答；CORRECTION更正反馈',
  `corrects_log_id` bigint unsigned DEFAULT NULL COMMENT '更正的原始作答事件ID，正常作答为空',
  `learning_date` date NOT NULL COMMENT '学习日期，北京时间',
  `result` tinyint unsigned NOT NULL COMMENT '反馈：1认识；2模糊；3不认识',
  `attempt_no` int unsigned NOT NULL COMMENT '该用户该词当天第几次作答（含专项练习）；更正沿用原事件序号',
  `affects_schedule` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '是否影响长期调度：0否；1是',
  `schedule_before` json DEFAULT NULL COMMENT '调度前状态快照，用于核查及重新计算',
  `schedule_after` json DEFAULT NULL COMMENT '调度后状态快照',
  `answered_at` datetime(3) NOT NULL COMMENT '服务端确认本次反馈的时间',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件入库时间',
  `feedback_mode` varchar(20) NOT NULL DEFAULT 'RECOGNITION' COMMENT '该有效反馈的来源，输入判定不能伪装成认识自评',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_request` (`user_id`,`request_id`),
  KEY `idx_user_word_time` (`user_id`,`word_id`,`answered_at`,`id`),
  KEY `idx_user_word_date` (`user_id`,`word_id`,`learning_date`),
  KEY `idx_batch_id` (`batch_id`),
  KEY `idx_batch_item_id` (`batch_item_id`),
  KEY `idx_word_id` (`word_id`),
  KEY `idx_corrects_log_id` (`corrects_log_id`),
  CONSTRAINT `fk_review_log_batch` FOREIGN KEY (`batch_id`) REFERENCES `learning_batch` (`id`),
  CONSTRAINT `fk_review_log_correction` FOREIGN KEY (`corrects_log_id`) REFERENCES `word_review_log` (`id`),
  CONSTRAINT `fk_review_log_item` FOREIGN KEY (`batch_item_id`) REFERENCES `learning_batch_item` (`id`),
  CONSTRAINT `fk_review_log_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_review_log_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`),
  CONSTRAINT `chk_review_feedback_mode` CHECK ((`feedback_mode` in (_utf8mb4'RECOGNITION',_utf8mb4'DICTATION',_utf8mb4'FOLLOW_TYPING',_utf8mb4'LISTEN_DICTATION'))),
  CONSTRAINT `chk_review_log_affects_schedule` CHECK ((`affects_schedule` in (0,1))),
  CONSTRAINT `chk_review_log_attempt` CHECK ((`attempt_no` >= 1)),
  CONSTRAINT `chk_review_log_correction` CHECK ((((`event_type` = _utf8mb4'ANSWER') and (`corrects_log_id` is null)) or ((`event_type` = _utf8mb4'CORRECTION') and (`corrects_log_id` is not null)))),
  CONSTRAINT `chk_review_log_event_type` CHECK ((`event_type` in (_utf8mb4'ANSWER',_utf8mb4'CORRECTION'))),
  CONSTRAINT `chk_review_log_result` CHECK ((`result` in (1,2,3)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单词学习反馈事件表';

CREATE TABLE `user_sentence_note` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  `content` varchar(4000) NOT NULL,
  `attempt_id` bigint unsigned DEFAULT NULL,
  `version` bigint unsigned NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_note` (`user_id`,`sentence_id`),
  KEY `idx_sentence_note_user_updated` (`user_id`,`updated_at`,`id`),
  KEY `fk_sentence_note_question` (`sentence_id`),
  KEY `fk_sentence_note_attempt` (`attempt_id`,`user_id`,`sentence_id`),
  CONSTRAINT `fk_sentence_note_attempt` FOREIGN KEY (`attempt_id`, `user_id`, `sentence_id`) REFERENCES `sentence_translation_attempt` (`id`, `user_id`, `sentence_id`),
  CONSTRAINT `fk_sentence_note_question` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`),
  CONSTRAINT `fk_sentence_note_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_sentence_note_version` CHECK ((`version` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_sentence_review_state` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `sentence_id` bigint unsigned NOT NULL,
  `last_attempt_id` bigint unsigned NOT NULL,
  `review_level` tinyint unsigned NOT NULL,
  `last_assessment` varchar(20) NOT NULL,
  `reviewed_at` datetime(3) NOT NULL,
  `next_review_date` date NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentence_review_user` (`user_id`,`sentence_id`),
  KEY `idx_sentence_review_due` (`user_id`,`next_review_date`,`id`),
  KEY `fk_sentence_review_question` (`sentence_id`),
  KEY `fk_sentence_review_attempt` (`last_attempt_id`),
  CONSTRAINT `fk_sentence_review_attempt` FOREIGN KEY (`last_attempt_id`) REFERENCES `sentence_translation_attempt` (`id`),
  CONSTRAINT `fk_sentence_review_question` FOREIGN KEY (`sentence_id`) REFERENCES `sentence_question` (`id`),
  CONSTRAINT `fk_sentence_review_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `chk_sentence_review_assessment` CHECK ((`last_assessment` in (_utf8mb4'UNDERSTOOD',_utf8mb4'PARTIAL',_utf8mb4'NOT_UNDERSTOOD'))),
  CONSTRAINT `chk_sentence_review_date` CHECK ((`next_review_date` > cast(`reviewed_at` as date))),
  CONSTRAINT `chk_sentence_review_level` CHECK ((`review_level` between 0 and 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `word_training_attempt` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL COMMENT '训练者',
  `word_id` bigint unsigned NOT NULL COMMENT '原始单词ID',
  `batch_id` bigint unsigned NOT NULL COMMENT '共享的每日批次',
  `batch_item_id` bigint unsigned NOT NULL COMMENT '共享的词任务项',
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等标识',
  `presentation_id` varchar(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已消费的展示标识',
  `mode_version` bigint unsigned NOT NULL COMMENT '展示版本',
  `mode` varchar(20) NOT NULL COMMENT 'RECOGNITION/DICTATION/FOLLOW_TYPING',
  `action` varchar(20) NOT NULL COMMENT 'ANSWER作答或REVEAL看答案',
  `input_text` varchar(255) DEFAULT NULL COMMENT '输入模式用户原文',
  `self_assessment` tinyint unsigned DEFAULT NULL COMMENT '仅认识模式：1认识/2模糊/3不认识',
  `verdict` varchar(20) NOT NULL COMMENT 'SELF_ASSESSED/CORRECT/INCORRECT/REVEALED',
  `hint_used` tinyint unsigned NOT NULL COMMENT '该次是否已看过拼写/答案；由服务端记录',
  `extra_training` tinyint unsigned NOT NULL COMMENT '追加训练不重复抵扣目标且不改变调度',
  `attempt_no` int unsigned NOT NULL COMMENT '该词统一训练事件序号',
  `review_log_id` bigint unsigned DEFAULT NULL COMMENT '推进每日任务时生成的有效反馈；揭示及追加训练为空',
  `learning_date` date NOT NULL COMMENT '上海学习日期',
  `answered_at` datetime(3) NOT NULL COMMENT '上海作答时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_training_request` (`user_id`,`request_id`),
  UNIQUE KEY `uk_training_presentation` (`user_id`,`presentation_id`),
  UNIQUE KEY `uk_training_attempt` (`batch_item_id`,`attempt_no`),
  UNIQUE KEY `uk_training_feedback` (`review_log_id`),
  KEY `idx_training_user_date` (`user_id`,`learning_date`,`mode`),
  KEY `idx_training_batch` (`batch_id`,`id`),
  KEY `fk_training_word` (`word_id`),
  CONSTRAINT `fk_training_batch` FOREIGN KEY (`batch_id`) REFERENCES `learning_batch` (`id`),
  CONSTRAINT `fk_training_feedback` FOREIGN KEY (`review_log_id`) REFERENCES `word_review_log` (`id`),
  CONSTRAINT `fk_training_item` FOREIGN KEY (`batch_item_id`) REFERENCES `learning_batch_item` (`id`),
  CONSTRAINT `fk_training_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_training_word` FOREIGN KEY (`word_id`) REFERENCES `word` (`id`),
  CONSTRAINT `chk_training_answer` CHECK ((((`action` = _utf8mb4'REVEAL') and (`input_text` is null) and (`self_assessment` is null) and (`verdict` = _utf8mb4'REVEALED') and (`hint_used` = 1) and (`review_log_id` is null)) or ((`action` = _utf8mb4'ANSWER') and (((`mode` = _utf8mb4'RECOGNITION') and (`input_text` is null) and (`self_assessment` is not null) and (`self_assessment` in (1,2,3)) and (`verdict` = _utf8mb4'SELF_ASSESSED')) or ((`mode` in (_utf8mb4'DICTATION',_utf8mb4'FOLLOW_TYPING',_utf8mb4'LISTEN_DICTATION')) and (`input_text` is not null) and (`self_assessment` is null) and (`verdict` in (_utf8mb4'CORRECT',_utf8mb4'INCORRECT'))))))),
  CONSTRAINT `chk_training_extra` CHECK (((`extra_training` = 0) or (`review_log_id` is null))),
  CONSTRAINT `chk_training_values` CHECK (((`mode` in (_utf8mb4'RECOGNITION',_utf8mb4'DICTATION',_utf8mb4'FOLLOW_TYPING',_utf8mb4'LISTEN_DICTATION')) and (`hint_used` in (0,1)) and (`extra_training` in (0,1)) and (`attempt_no` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日三种学习方式的真实训练事件，与去重完成数分离';

CREATE TABLE `user_sentence_note_tag` (
  `note_id` bigint unsigned NOT NULL,
  `error_tag` varchar(20) NOT NULL,
  PRIMARY KEY (`note_id`,`error_tag`),
  KEY `idx_sentence_note_tag` (`error_tag`,`note_id`),
  CONSTRAINT `fk_sentence_note_tag_note` FOREIGN KEY (`note_id`) REFERENCES `user_sentence_note` (`id`),
  CONSTRAINT `chk_sentence_note_tag` CHECK ((`error_tag` in (_utf8mb4'VOCABULARY',_utf8mb4'TENSE',_utf8mb4'NEGATION',_utf8mb4'STRUCTURE',_utf8mb4'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

START TRANSACTION;
INSERT INTO user_account (username,password_hash,nickname) VALUES
('test','$2a$12$mBJTwHhZAIlXrO7EeylhCuek62W6n9AcoOvY4XKPSP0TJyaY6IHx2','Test account');
COMMIT;
