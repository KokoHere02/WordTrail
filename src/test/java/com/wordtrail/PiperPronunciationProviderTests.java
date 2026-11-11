package com.wordtrail;

import com.sun.net.httpserver.HttpServer;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import com.wordtrail.learning.service.PiperPronunciationProvider;
import com.wordtrail.learning.controller.PronunciationController;
import com.wordtrail.learning.service.TrainingAudioService;
import com.wordtrail.learning.service.DictationService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiperPronunciationProviderTests {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void controllerLabelsWavAndDisablesBrowserCaching() {
        var audio = mock(TrainingAudioService.class);
        when(audio.wordTarget(1)).thenReturn("apple");
        when(audio.audio("apple", Accent.US)).thenReturn("RIFFxxxxWAVEdata".getBytes(StandardCharsets.UTF_8));
        var response = new PronunciationController(audio, mock(DictationService.class)).word(1, Accent.US);
        assertEquals("audio/wav", response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test void sendsEachRequestToPiperWithTheSelectedVoice() throws Exception {
        var count = new AtomicInteger();
        var payload = new AtomicReference<String>();
        var provider = start("{\"us\":{},\"uk\":{}}", "RIFFxxxxWAVEdata", count, payload);
        provider.audio("ice \"cream\"", Accent.US);
        provider.audio("ice \"cream\"", Accent.US);
        assertEquals(2, count.get());
        assertTrue(payload.get().contains("ice \\\"cream\\\""));
        assertTrue(payload.get().contains("\"voice\":\"us\""));
        provider.audio("apple", Accent.UK);
        assertTrue(payload.get().contains("\"voice\":\"uk\""));
    }

    @Test void rejectsMissingVoiceAndInvalidWav() throws Exception {
        var calls = new AtomicInteger();
        var provider = start("{\"us\":{}}", "RIFFxxxxWAVEdata", calls, new AtomicReference<>());
        assertThrows(BusinessException.class, () -> provider.audio("apple", Accent.UK));
        assertEquals(0, calls.get());
        server.stop(0);
        var invalidProvider = start("{\"us\":{}}", "<html>error</html>", calls, new AtomicReference<>());
        assertThrows(BusinessException.class, () -> invalidProvider.audio("apple", Accent.US));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WORDTRAIL_TEST_LOCAL_PIPER", matches = "true")
    void localPiperProducesBothAccents() {
        var provider = new PiperPronunciationProvider("http://localhost:5000", "en_US-lessac-medium", "en_GB-alan-medium");
        for (var accent : Accent.values()) {
            for (int i = 0; i < 2; i++) {
                long start = System.nanoTime();
                byte[] audio = provider.audio("beautiful", accent);
                System.out.printf("Piper %s request %d: %d ms, %d bytes%n", accent, i + 1,
                    (System.nanoTime() - start) / 1_000_000, audio.length);
                assertTrue(audio.length > 44);
            }
        }
    }

    private PiperPronunciationProvider start(String voices, String audio, AtomicInteger count,
            AtomicReference<String> payload) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/voices", exchange -> {
            byte[] bytes = voices.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/synthesize", exchange -> {
            count.incrementAndGet();
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = audio.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        return new PiperPronunciationProvider("http://127.0.0.1:" + server.getAddress().getPort(), "us", "uk");
    }
}
