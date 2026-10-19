package com.wordtrail;

import org.mybatis.spring.annotation.MapperScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({"com.wordtrail.user.mapper", "com.wordtrail.book.mapper",
        "com.wordtrail.word.mapper", "com.wordtrail.learning.mapper"})
@SpringBootApplication
public class WordTrailApplication {
	public static void main(String[] args) {
		SpringApplication.run(WordTrailApplication.class, args);
	}
}
