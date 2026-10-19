package com.wordtrail.book.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("word_book_item")
public class WordBookItem {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long bookId;
    private Long wordId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
