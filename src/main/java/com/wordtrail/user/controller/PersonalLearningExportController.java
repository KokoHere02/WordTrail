package com.wordtrail.user.controller;

import com.wordtrail.security.CurrentUser;
import com.wordtrail.user.service.PersonalLearningExportService;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @RequiredArgsConstructor
public class PersonalLearningExportController {
    private final CurrentUser currentUser;
    private final PersonalLearningExportService export;
    private final Clock clock;
    @GetMapping("/api/me/learning-data/export")
    public ResponseEntity<StreamingResponseBody> export() {
        long userId=currentUser.id();
        String filename="wordtrail-learning-"+LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"))+".zip";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString()).body(output->export.write(userId,output));
    }
}
