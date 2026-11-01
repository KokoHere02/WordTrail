package com.wordtrail.sentence.service;
import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceNoteVO.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;
@Service @RequiredArgsConstructor
public class SentenceNoteService {
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceNoteMapper notes;
    private final SentenceNoteTagMapper tags;
    private final SentenceQuestionMapper questions;
    private final JdbcTemplate jdbc;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Note get(long sentenceId) {
        if(questions.selectCountByQuery(QueryWrapper.create().where(column("id").eq(sentenceId)))!=1) throw missing();
        var note=find(sentenceId);return note==null?empty():view(note,tagNames(List.of(note.getId())).getOrDefault(note.getId(),List.of()));
    }
    @Transactional
    public Note save(long sentenceId,SentenceNoteRequest request) {
        context.lockUser(user.id());if(questions.selectOneById(sentenceId)==null) throw missing();var note=find(sentenceId);
        long version=note==null?0L:note.getVersion();
        if(version!=request.getVersion().longValue()) throw conflict("SENTENCE_NOTE_VERSION_CONFLICT","笔记已被修改，请刷新后再保存");
        if(request.getAttemptId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM sentence_translation_record WHERE id=? AND user_id=? AND sentence_id=?",Long.class,request.getAttemptId(),user.id(),sentenceId)!=1)
            throw new BusinessException(HttpStatus.NOT_FOUND,"SENTENCE_ATTEMPT_NOT_FOUND","作答不存在或无权访问");
        if(note==null){note=new SentenceNote();note.setUserId(user.id());note.setSentenceId(sentenceId);note.setVersion(0L);}
        note.setContent(request.getContent().isBlank()?"":request.getContent());note.setAttemptId(request.getAttemptId());note.setVersion(note.getVersion()+1);note.setUpdatedAt(context.now());
        requireWrite(note.getId()==null?notes.insertSelective(note):notes.update(note,false));
        tags.deleteByQuery(QueryWrapper.create().where(column("note_id").eq(note.getId())));
        var selected=new TreeSet<>(request.getErrorTags());for(var tag:selected){var row=new SentenceNoteTag();row.setNoteId(note.getId());row.setErrorTag(tag.name());requireWrite(tags.insert(row));}
        return view(note,selected.stream().toList());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<Entry> list(SentenceNoteQuery query) {
        String tag=query.getErrorTag()==null?null:query.getErrorTag().name();long count=notes.countNotes(user.id(),tag,query.getWordId(),query.getDifficulty());long offset=((long)query.getPage()-1)*query.getSize();
        if(offset>=count) return new PageVO<>(query.getPage(),query.getSize(),count,List.of());
        var rows=notes.notes(user.id(),tag,query.getWordId(),query.getDifficulty(),offset,query.getSize());var grouped=tagNames(rows.stream().map(SentenceNoteMapper.Row::id).toList());
        return new PageVO<>(query.getPage(),query.getSize(),count,rows.stream().map(r->new Entry(r.sentenceId(),r.englishText(),r.available(),new Note(r.content(),grouped.getOrDefault(r.id(),List.of()),r.attemptId(),r.version(),r.updatedAt()))).toList());
    }
    private SentenceNote find(long sentenceId){return notes.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(user.id())).and(column("sentence_id").eq(sentenceId)));}
    private Map<Long,List<SentenceNoteRequest.ErrorTag>> tagNames(List<Long> ids) {
        var result=new HashMap<Long,List<SentenceNoteRequest.ErrorTag>>();if(ids.isEmpty())return result;
        for(var tag:tags.selectListByQuery(QueryWrapper.create().where(column("note_id").in(ids)).orderBy(column("error_tag").asc()))) result.computeIfAbsent(tag.getNoteId(),ignored->new ArrayList<>()).add(SentenceNoteRequest.ErrorTag.valueOf(tag.getErrorTag()));
        result.values().forEach(Collections::sort);return result;
    }
    private static Note view(SentenceNote n,List<SentenceNoteRequest.ErrorTag> tags){return new Note(n.getContent(),List.copyOf(tags),n.getAttemptId(),n.getVersion(),n.getUpdatedAt());}
    private static Note empty(){return new Note("",List.of(),null,0,null);}
    private static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","句子不存在");}
    private static void requireWrite(int n){if(n!=1)throw new IllegalStateException("Could not persist private sentence note");}
}
