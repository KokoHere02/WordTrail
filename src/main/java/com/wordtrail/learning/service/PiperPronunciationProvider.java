package com.wordtrail.learning.service;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PiperPronunciationProvider implements PronunciationProvider {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final URI base;
    private final String usVoice;
    private final String ukVoice;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public PiperPronunciationProvider(@Value("${app.audio.piper-url:http://localhost:5000}") String base,
            @Value("${app.audio.piper-us-voice:en_US-lessac-medium}") String usVoice,
            @Value("${app.audio.piper-uk-voice:en_GB-alan-medium}") String ukVoice) {
        this.base = URI.create(base.replaceAll("/+$", "") + "/");
        this.usVoice = usVoice;
        this.ukVoice = ukVoice;
    }

    @Override public byte[] audio(String word, Accent accent) {
        String voice = accent == Accent.UK ? ukVoice : usVoice;
        try {
            // Piper silently falls back to the default voice when a requested voice is missing.
            var voices = client.send(HttpRequest.newBuilder(base.resolve("voices")).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = voices.body()) {
                if (voices.statusCode() != 200) throw unavailable();
                byte[] data = stream.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES || !json.readTree(data).has(voice)) throw unavailable();
            }
            String payload = json.writeValueAsString(Map.of("text", word, "voice", voice));
            var request = HttpRequest.newBuilder(base.resolve("synthesize")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw unavailable();
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length < 12 || bytes.length > MAX_BYTES || bytes[0] != 'R' || bytes[1] != 'I'
                    || bytes[2] != 'F' || bytes[3] != 'F' || bytes[8] != 'W' || bytes[9] != 'A'
                    || bytes[10] != 'V' || bytes[11] != 'E') throw unavailable();
                return bytes;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (IOException | RuntimeException ex) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUDIO_UNAVAILABLE", "发音暂不可用，请重试或切换学习方式");
    }
}
