package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("sentence_import_item")
public class SentenceImportItem {
    @Id private Long batchId;
    @Id @Column("csv_row_number") private Integer rowNumber;
    private Long sentenceId;
}
