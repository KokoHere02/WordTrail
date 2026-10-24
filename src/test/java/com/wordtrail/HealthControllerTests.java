package com.wordtrail;
import com.wordtrail.ops.HealthController;
import com.wordtrail.ops.mapper.HealthMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HealthControllerTests {
    @Test void livenessDoesNotDependOnDatabase(){var mapper=mock(HealthMapper.class);assertEquals(200,new HealthController(mapper).live().getStatusCode().value());verifyNoInteractions(mapper);}
    @Test void readinessHidesFailureDetailsAndReturns503(){var mapper=mock(HealthMapper.class);when(mapper.ready()).thenThrow(new IllegalStateException("private database diagnostic"));var response=new HealthController(mapper).ready();assertEquals(503,response.getStatusCode().value());assertEquals(java.util.Map.of("status","DOWN"),response.getBody());}
}
