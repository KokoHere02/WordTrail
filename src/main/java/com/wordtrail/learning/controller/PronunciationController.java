package com.wordtrail.learning.controller;

import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import com.wordtrail.learning.service.TrainingAudioService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class PronunciationController {
    private final TrainingAudioService audio;
    private ResponseEntity<byte[]> response(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=pronunciation.mp3").body(bytes);
    }
    @GetMapping("/api/words/{wordId}/audio") public ResponseEntity<byte[]> word(@PathVariable @Positive long wordId,@RequestParam(required=false) Accent accent) {
        return response(audio.audio(audio.wordTarget(wordId),accent));
    }
    @GetMapping("/api/learning-batches/{batchId}/training/items/{itemId}/audio")
    public ResponseEntity<byte[]> training(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,
            @RequestParam @Pattern(regexp="[a-f0-9-]{36}") String presentationId,@RequestParam @PositiveOrZero long modeVersion,
            @RequestParam(required=false) Accent accent) {
        return response(audio.audio(audio.trainingTarget(batchId,itemId,presentationId,modeVersion),accent));
    }
}
