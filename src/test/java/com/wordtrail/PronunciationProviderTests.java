package com.wordtrail;

import com.sun.net.httpserver.HttpServer;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import com.wordtrail.learning.service.RemotePronunciationProvider;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PronunciationProviderTests {
    private HttpServer server;
    @AfterEach void stop() { if(server!=null) server.stop(0); }
    private String start(int status,byte[] bytes,AtomicReference<String> query,AtomicInteger calls) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/audio",exchange->{
            calls.incrementAndGet();query.set(exchange.getRequestURI().getRawQuery());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(status,bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();return "http://127.0.0.1:"+server.getAddress().getPort()+"/audio";
    }
    @Test void accentAndWordAreEncodedAndRepeatedAudioUsesPrivateCopyOfCache() throws Exception {
        var query=new AtomicReference<String>();var calls=new AtomicInteger();
        var provider=new RemotePronunciationProvider(start(200,"ID3-fixture-mp3".getBytes(StandardCharsets.UTF_8),query,calls));
        byte[] first=provider.audio("ice cream&secret=1",Accent.UK);first[0]='x';
        assertTrue(query.get().contains("audio=ice+cream%26secret%3D1"));assertTrue(query.get().endsWith("type=1"));
        assertEquals('I',provider.audio("ice cream&secret=1",Accent.UK)[0]);assertEquals(1,calls.get());
        provider.audio("ice cream&secret=1",Accent.US);assertTrue(query.get().endsWith("type=2"));assertEquals(2,calls.get());
    }
    @Test void providerRejectsHtmlInsteadOfReturningAnErrorPageAsAudio() throws Exception {
        var provider=new RemotePronunciationProvider(start(200,"<html>not audio</html>".getBytes(StandardCharsets.UTF_8),new AtomicReference<>(),new AtomicInteger()));
        var error=assertThrows(BusinessException.class,()->provider.audio("apple",Accent.US));
        assertFalse(error.getMessage().contains("html"));assertFalse(error.getMessage().contains("http"));
    }
    @Test void unavailableProviderAndOversizePayloadAreRejected() throws Exception {
        var failedProvider=new RemotePronunciationProvider(start(503,"private upstream diagnostic".getBytes(StandardCharsets.UTF_8),new AtomicReference<>(),new AtomicInteger()));
        assertThrows(BusinessException.class,()->failedProvider.audio("apple",Accent.US));server.stop(0);
        byte[] tooLarge=new byte[1024*1024+1];tooLarge[0]='I';tooLarge[1]='D';tooLarge[2]='3';
        var large=new RemotePronunciationProvider(start(200,tooLarge,new AtomicReference<>(),new AtomicInteger()));
        assertThrows(BusinessException.class,()->large.audio("apple",Accent.US));
    }
}
