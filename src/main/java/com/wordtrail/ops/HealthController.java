package com.wordtrail.ops;
import com.wordtrail.ops.mapper.HealthMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class HealthController {
    private final HealthMapper database;
    @GetMapping("/health/live") public ResponseEntity<Map<String,String>> live(){return response(HttpStatus.OK,"UP");}
    @GetMapping("/health/ready") public ResponseEntity<Map<String,String>> ready(){
        try{database.ready();return response(HttpStatus.OK,"UP");}catch(RuntimeException ex){return response(HttpStatus.SERVICE_UNAVAILABLE,"DOWN");}
    }
    private static ResponseEntity<Map<String,String>> response(HttpStatus status,String value){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("status",value));}
}
