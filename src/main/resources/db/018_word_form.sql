-- MySQL 8.0.16+. Additive schema migration; back up and verify target first.
-- Populate with ECDICT/migrate_word_forms.py; do not drop word.exchange yet.
CREATE TABLE word_form (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 word_id BIGINT UNSIGNED NOT NULL,
 form VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 form_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source VARCHAR(32) NOT NULL DEFAULT 'ECDICT',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_word_form (word_id,form,form_type),
 KEY idx_word_form_form (form),
 CONSTRAINT fk_word_form_word FOREIGN KEY (word_id) REFERENCES word(id),
 CONSTRAINT chk_word_form_type CHECK (form_type IN
  ('PAST','PAST_PARTICIPLE','PRESENT_PARTICIPLE','THIRD_PERSON_SINGULAR','PLURAL','COMPARATIVE','SUPERLATIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
