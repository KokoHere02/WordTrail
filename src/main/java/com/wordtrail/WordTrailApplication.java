package com.wordtrail;

import org.mybatis.spring.annotation.MapperScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.wordtrail.user.mapper")
@SpringBootApplication
public class WordTrailApplication {
	public static void main(String[] args) {
		SpringApplication.run(WordTrailApplication.class, args);
	}
}
