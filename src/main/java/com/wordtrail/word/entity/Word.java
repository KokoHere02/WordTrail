package com.wordtrail.word.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("word")
public class Word {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String word;
    private String phonetic;
    private String translation;
    private String definition;
    private String pos;
    private String tag;
    private Long bnc;
    private Long frq;
    private String exchange;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
