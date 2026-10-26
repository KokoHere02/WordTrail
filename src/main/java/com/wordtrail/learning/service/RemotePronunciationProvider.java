package com.wordtrail.learning.service;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RemotePronunciationProvider implements PronunciationProvider {
    private static final int MAX_BYTES=1024*1024;
    private final String endpoint;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String,byte[]> cache=Collections.synchronizedMap(new LinkedHashMap<>(256,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,byte[]> entry) { return size()>256; }
    });
    public RemotePronunciationProvider(@Value("${app.audio.endpoint:https://dict.youdao.com/dictvoice}") String endpoint) { this.endpoint=endpoint; }
    @Override public byte[] audio(String word,Accent accent) {
        String key=accent+"\n"+word; byte[] saved=cache.get(key); if(saved!=null) return saved.clone();
        try {
            String url=endpoint+"?audio="+URLEncoder.encode(word,StandardCharsets.UTF_8)+"&type="+(accent==Accent.UK?1:2);
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).header("Accept","audio/mpeg").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200) throw unavailable();
                byte[] bytes=stream.readNBytes(MAX_BYTES+1);
                boolean mp3=bytes.length>=3 && ((bytes[0]=='I' && bytes[1]=='D' && bytes[2]=='3')
                    || ((bytes[0]&255)==255 && (bytes[1]&224)==224));
                if(!mp3 || bytes.length>MAX_BYTES) throw unavailable();
                cache.put(key,bytes.clone()); return bytes;
            }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(java.io.IOException|IllegalArgumentException ex) { throw unavailable(); }
    }
    private static BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"AUDIO_UNAVAILABLE","发音暂不可用，请重试或切换学习方式"); }
}
