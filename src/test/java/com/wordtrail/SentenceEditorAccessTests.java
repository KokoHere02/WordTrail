package com.wordtrail;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.service.SentenceEditorAccess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SentenceEditorAccessTests {
    @Test void emptyConfigurationDeniesEveryAccount() {
        var user=mock(CurrentUser.class);when(user.id()).thenReturn(1L);var access=new SentenceEditorAccess(user,"");
        assertFalse(access.allowed());assertEquals("SENTENCE_EDITOR_REQUIRED",assertThrows(BusinessException.class,access::require).code());
    }
    @Test void onlyExplicitServerConfiguredAccountsCanManageSharedContent() {
        var user=mock(CurrentUser.class);var access=new SentenceEditorAccess(user," 1,2,1 ");
        when(user.id()).thenReturn(1L);assertTrue(access.allowed());assertDoesNotThrow(access::require);
        when(user.id()).thenReturn(3L);assertFalse(access.allowed());assertThrows(BusinessException.class,access::require);
    }
    @Test void invalidEditorConfigurationFailsAtStartup() {
        var user=mock(CurrentUser.class);for(String config:new String[]{"0","-1","admin","1.2","9223372036854775808"}) assertThrows(IllegalStateException.class,()->new SentenceEditorAccess(user,config));
    }
}
