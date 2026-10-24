package com.wordtrail.sentence.service;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.CurrentUser;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SentenceEditorAccess {
    private final Set<Long> editors;
    private final CurrentUser user;
    public SentenceEditorAccess(CurrentUser user,@Value("${app.sentence.editor-user-ids:}") String configured) {
        this.user=user;var ids=new HashSet<Long>();
        for(String item:configured.split(",")) if(!item.isBlank()) {
            try {long id=Long.parseLong(item.strip());if(id<=0) throw new NumberFormatException();ids.add(id);}
            catch(NumberFormatException ex){throw new IllegalStateException("Sentence editor IDs must be positive integer user IDs");}
        }
        this.editors=Set.copyOf(ids);
    }
    public boolean allowed() {return editors.contains(user.id());}
    public void require() {if(!allowed()) throw new BusinessException(HttpStatus.FORBIDDEN,"SENTENCE_EDITOR_REQUIRED","当前账号没有题库维护权限");}
}
