package com.wordtrail.sentence.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.support.BookTransferText;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceBankVO.*;
import com.wordtrail.word.mapper.WordMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class SentenceBankService {
    private final SentenceEditorAccess access;
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceQuestionMapper questions;
    private final SentenceWordMapper links;
    private final WordMapper words;
    private final SentenceImportBatchMapper imports;
    private final SentenceImportItemMapper importItems;
    private final Validator validator;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<ManagedQuestion> list(SentenceManagementQuery query) {
        access.require();var where=QueryWrapper.create();
        if(query.getStatus()!=null) where.and(column("status").eq(query.getStatus().name()));
        if(query.getDifficulty()!=null) where.and(column("difficulty").eq(query.getDifficulty()));
        if(query.getKeyword()!=null && !query.getKeyword().isBlank()) {
            String prefix=query.getKeyword().strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where.and("english_text LIKE ? ESCAPE '!'",prefix);
        }
        if(query.getWordId()!=null) where.and(column("id").in(QueryWrapper.create().select("sentence_id").from("sentence_word").where(column("word_id").eq(query.getWordId()))));
        var page=questions.paginate(query.getPage(),query.getSize(),where.orderBy(column("id").desc()));
        var grouped=new HashMap<Long,List<com.wordtrail.sentence.vo.SentenceTranslationVO.LinkedWord>>();
        if(!page.getRecords().isEmpty()) for(var link:questions.linkedForQuestions(page.getRecords().stream().map(SentenceQuestion::getId).toList()))
            grouped.computeIfAbsent(link.sentenceId(),ignored->new ArrayList<>()).add(new com.wordtrail.sentence.vo.SentenceTranslationVO.LinkedWord(link.wordId(),link.word()));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream().map(q->view(q,grouped.getOrDefault(q.getId(),List.of()))).toList());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ManagedQuestion detail(long id) {access.require();return view(find(id,false));}
    @Transactional
    public ManagedQuestion create(SentenceQuestionWriteRequest request) {
        access.require();context.lockUser(user.id());validateWords(request.getWordIds());
        if(request.getVersion()!=0) throw BookTransferText.invalid("INVALID_PARAMETER","新题目 version 必须为 0");
        var question=new SentenceQuestion();apply(question,request);question.setVersion(0L);insert(question);
        replaceLinks(question.getId(),request.getWordIds());return view(question);
    }
    @Transactional
    public ManagedQuestion update(long id,SentenceQuestionWriteRequest request) {
        access.require();context.lockUser(user.id());var question=find(id,true);
        if(!question.getVersion().equals(request.getVersion())) throw conflict("SENTENCE_VERSION_CONFLICT","题目已被修改，请刷新后再编辑");
        validateWords(request.getWordIds());apply(question,request);question.setVersion(question.getVersion()+1);
        try {requireWrite(questions.update(question,false));}catch(DuplicateKeyException ex){throw duplicate();}
        replaceLinks(id,request.getWordIds());return view(question);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(String text) {access.require();return prepare(text).preview();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview previewFile(MultipartFile file) {
        access.require();if(file.getSize()>BookTransferText.MAX_BYTES) throw BookTransferText.tooLarge();
        try{return preview(BookTransferText.decode(file.getBytes()));}
        catch(java.io.IOException ex){throw BookTransferText.invalid("IMPORT_FILE_UNREADABLE","无法读取上传文件");}
    }
    @Transactional
    public Imported confirm(SentenceImportRequest request) {
        access.require();context.lockUser(user.id());
        if(request.requestId()==null) throw BookTransferText.invalid("INVALID_PARAMETER","确认导入需要 requestId");
        if(request.text().getBytes(StandardCharsets.UTF_8).length>BookTransferText.MAX_BYTES) throw BookTransferText.tooLarge();
        String contentHash=hash(request.text());
        var prior=imports.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(user.id())).and(column("request_id").eq(request.requestId())));
        if(prior!=null) {
            if(!contentHash.equals(prior.getContentHash())) throw conflict("REQUEST_ID_REUSED","该请求标识已用于其他导入内容");
            return imported(prior);
        }
        var prepared=prepare(request.text());
        if(prepared.preview().readyCount()!=prepared.rows().size()) throw conflict("SENTENCE_IMPORT_NOT_READY","预览包含非法或重复题目，请修正后重新预览");
        var batch=new SentenceImportBatch();batch.setUserId(user.id());batch.setRequestId(request.requestId());batch.setContentHash(contentHash);
        batch.setRowCount(prepared.rows().size());batch.setCreatedAt(context.now());requireWrite(imports.insertSelective(batch));
        for(var row:prepared.rows()) {
            var question=new SentenceQuestion();apply(question,row.request());question.setVersion(0L);insert(question);replaceLinks(question.getId(),row.request().getWordIds());
            var entry=new SentenceImportItem();entry.setBatchId(batch.getId());entry.setRowNumber(row.line());entry.setSentenceId(question.getId());requireWrite(importItems.insert(entry));
        }
        return imported(batch);
    }
    private record ParsedRow(int line,SentenceQuestionWriteRequest request,String problem) {}
    private record Prepared(Preview preview,List<ParsedRow> rows) {}
    private Prepared prepare(String text) {
        var csv=SentenceCsv.parse(text);var header=csv.getFirst().cells().stream().map(s->s.strip().toLowerCase(Locale.ROOT)).toList();
        var allowed=Set.of("englishtext","referencetranslation","explanation","difficulty","source","sourcereference","wordids","status");
        if(new HashSet<>(header).size()!=header.size() || !allowed.containsAll(header) || !header.containsAll(Set.of("englishtext","referencetranslation","explanation","difficulty","source")))
            throw BookTransferText.invalid("IMPORT_CSV_INVALID","CSV 表头须包含 englishText、referenceTranslation、explanation、difficulty、source，不能重复或包含未知列");
        var parsed=new ArrayList<ParsedRow>();var allWords=new HashSet<Long>();var hashes=new HashSet<String>();
        for(var row:csv.subList(1,csv.size())) {
            var request=new SentenceQuestionWriteRequest();String problem=null;
            if(row.cells().size()!=header.size()) problem="列数与 CSV 表头不一致";
            else try {
                var values=new HashMap<String,String>();for(int i=0;i<header.size();i++) values.put(header.get(i),row.cells().get(i));
                request.setEnglishText(values.get("englishtext"));request.setReferenceTranslation(values.get("referencetranslation"));request.setExplanation(values.get("explanation"));
                request.setSource(values.get("source"));request.setSourceReference(values.get("sourcereference"));request.setDifficulty(Integer.valueOf(values.get("difficulty").strip()));
                if(values.containsKey("status")) request.setStatus(SentenceQuestionWriteRequest.Status.valueOf(values.get("status").strip()));
                if(values.containsKey("wordids") && !values.get("wordids").isBlank()) request.setWordIds(Arrays.stream(values.get("wordids").split("\\|",-1)).map(String::strip).map(Long::valueOf).distinct().toList());
                if(!validator.validate(request).isEmpty()) problem="题目字段为空、超长、难度/状态或关联单词 ID 不合法";
                else {allWords.addAll(request.getWordIds());hashes.add(hash(trimSpaces(request.getEnglishText())));}
            }catch(IllegalArgumentException ex){problem="难度、状态或关联单词 ID 格式不合法";}
            parsed.add(new ParsedRow(row.line(),request,problem));
        }
        var knownWords=allWords.isEmpty()?Set.<Long>of():new HashSet<>(words.selectListByQuery(QueryWrapper.create().where(column("id").in(allWords))).stream().map(w->w.getId()).toList());
        var existing=new HashMap<String,Long>();
        if(!hashes.isEmpty()) for(var question:questions.selectListByQuery(QueryWrapper.create().where(column("content_hash").in(hashes)))) existing.put(hash(trimSpaces(question.getEnglishText())),question.getId());
        var seen=new HashSet<String>();var result=new ArrayList<ImportRow>();
        for(var row:parsed) {
            var r=row.request();String problem=row.problem(),status="READY";Long existingId=null;
            if(problem==null && !knownWords.containsAll(r.getWordIds())) problem="关联单词 ID 不存在";
            if(problem!=null) status="INVALID";
            else {String key=hash(trimSpaces(r.getEnglishText()));existingId=existing.get(key);
                if(!seen.add(key)){status="DUPLICATE_IN_FILE";problem="与文件中前面的题目重复";}
                else if(existingId!=null){status="ALREADY_EXISTS";problem="题库已存在相同英文题目，含已停用题目";}}
            result.add(new ImportRow(row.line(),status,problem,existingId,r.getEnglishText(),r.getReferenceTranslation(),r.getExplanation(),r.getDifficulty(),r.getSource(),r.getSourceReference(),r.getWordIds()));
        }
        return new Prepared(new Preview(result.size(),result.stream().filter(r->r.status().equals("READY")).count(),result.stream().filter(r->r.status().equals("DUPLICATE_IN_FILE")||r.status().equals("ALREADY_EXISTS")).count(),result.stream().filter(r->r.status().equals("INVALID")).count(),List.copyOf(result)),parsed);
    }
    private Imported imported(SentenceImportBatch batch) {
        return new Imported(batch.getId(),batch.getRowCount(),importItems.selectListByQuery(QueryWrapper.create().where(column("batch_id").eq(batch.getId())).orderBy(column("csv_row_number").asc())).stream().map(SentenceImportItem::getSentenceId).toList());
    }
    private void validateWords(List<Long> ids) {
        var unique=new HashSet<>(ids);if(!unique.isEmpty() && words.selectCountByQuery(QueryWrapper.create().where(column("id").in(unique)))!=unique.size())
            throw conflict("SENTENCE_WORD_UNAVAILABLE","关联单词须全部存在");
    }
    private void replaceLinks(long id,List<Long> ids) {
        links.deleteByQuery(QueryWrapper.create().where(column("sentence_id").eq(id)));
        for(long wordId:new LinkedHashSet<>(ids)){var link=new SentenceWord();link.setSentenceId(id);link.setWordId(wordId);requireWrite(links.insert(link));}
    }
    private SentenceQuestion find(long id,boolean lock) {
        var query=QueryWrapper.create().where(column("id").eq(id));if(lock) query.forUpdate();var question=questions.selectOneByQuery(query);
        if(question==null) throw new BusinessException(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","句子不存在");return question;
    }
    private ManagedQuestion view(SentenceQuestion q) {return view(q,questions.linkedWords(q.getId()));}
    private ManagedQuestion view(SentenceQuestion q,List<com.wordtrail.sentence.vo.SentenceTranslationVO.LinkedWord> linked) {return new ManagedQuestion(q.getId(),q.getEnglishText(),q.getReferenceTranslation(),q.getExplanation(),q.getDifficulty(),q.getSource(),q.getSourceReference(),q.getStatus(),q.getVersion(),linked);}
    private static void apply(SentenceQuestion q,SentenceQuestionWriteRequest r) {
        q.setEnglishText(r.getEnglishText());q.setReferenceTranslation(r.getReferenceTranslation());q.setExplanation(r.getExplanation());q.setDifficulty(r.getDifficulty());q.setSource(r.getSource());q.setSourceReference(r.getSourceReference());q.setStatus(r.getStatus().name());
    }
    private void insert(SentenceQuestion q) {try{requireWrite(questions.insertSelective(q));}catch(DuplicateKeyException ex){throw duplicate();}}
    private static BusinessException duplicate(){return conflict("SENTENCE_ALREADY_EXISTS","题库已存在相同英文题目");}
    private static void requireWrite(int n){if(n!=1) throw new IllegalStateException("Could not persist sentence bank change");}
    public static String trimSpaces(String text){return text.replaceAll("^ +| +$","");}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
