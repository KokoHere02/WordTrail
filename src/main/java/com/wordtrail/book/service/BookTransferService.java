package com.wordtrail.book.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.dto.BookImportFormat;
import com.wordtrail.book.mapper.WordBookItemMapper;
import com.wordtrail.book.support.BookTransferText;
import com.wordtrail.book.vo.*;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.mapper.WordMapper;
import com.wordtrail.word.vo.DictionaryWordVO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class BookTransferService {
    private final BookService books;
    private final CurrentUser currentUser;
    private final WordMapper words;
    private final WordBookItemMapper items;
    private static final int MAX_EXPORT_WORDS=10000,MAX_EXPORT_BYTES=10*1024*1024;

    private void requirePersonal(long bookId) {
        var book=books.requireAccessible(bookId,currentUser.id());
        if(book.getOwnerId()==null) throw new BusinessException(HttpStatus.FORBIDDEN,"BOOK_READ_ONLY","仅支持本人个人词本的导入和导出");
    }

    public BookImportPreviewVO preview(long bookId,String text,BookImportFormat format) {
        requirePersonal(bookId);
        return match(bookId,BookTransferText.parse(text,format==null?BookImportFormat.TXT:format),format==null?BookImportFormat.TXT:format);
    }

    public BookImportPreviewVO previewFile(long bookId,MultipartFile file) {
        requirePersonal(bookId);
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        BookImportFormat format;
        if(name.endsWith(".txt")) format=BookImportFormat.TXT;
        else if(name.endsWith(".csv")) format=BookImportFormat.CSV;
        else throw BookTransferText.invalid("IMPORT_FILE_TYPE","仅支持 .txt 或 .csv 文件");
        if(file.getSize()>BookTransferText.MAX_BYTES) throw BookTransferText.tooLarge();
        try(var input=file.getInputStream()) { return match(bookId,BookTransferText.parse(BookTransferText.decode(input.readNBytes(BookTransferText.MAX_BYTES+1)),format),format); }
        catch(java.io.IOException ex) { throw BookTransferText.invalid("IMPORT_FILE_UNREADABLE","文件读取失败，请重新上传"); }
    }

    private BookImportPreviewVO match(long bookId,BookTransferText.Parsed parsed,BookImportFormat format) {
        var terms=parsed.rows().stream().filter(row->row.problem()==null).map(BookTransferText.Row::word).distinct().toList();
        var candidates=terms.isEmpty()?Map.<String,List<WordMapper.ImportMatch>>of():words.importMatches(bookId,terms).stream()
            .collect(Collectors.groupingBy(WordMapper.ImportMatch::word));
        var seen=new HashMap<String,Integer>(); var ready=new LinkedHashSet<Long>();
        var counts=new HashMap<String,Integer>(); var records=new ArrayList<BookImportRowVO>();
        for(var row:parsed.rows()) {
            var matched=candidates.getOrDefault(row.word(),List.of());
            var options=matched.stream().map(item->new DictionaryWordVO(item.id(),item.word(),item.phonetic(),item.translation(),item.inBook())).toList();
            long count=matched.isEmpty()?0:matched.getFirst().candidateCount();
            String status,message; Integer duplicate=null;
            if(row.problem()!=null) { status="INVALID"; message=row.problem(); }
            else if((duplicate=seen.putIfAbsent(row.word(),row.rowNumber()))!=null) { status="DUPLICATE"; message="与输入中的第 "+duplicate+" 行重复"; }
            else if(count==0) { status="NOT_FOUND"; message="词库中没有精确匹配的单词"; }
            else if(count>1) { status="AMBIGUOUS"; message="存在多个候选，请明确选择 wordId"; }
            else {
                ready.add(matched.getFirst().id());
                boolean present=matched.getFirst().inBook(); status=present?"ALREADY_IN_BOOK":"MATCHED";
                message=present?"词本已收录，重复确认不会新增记录":"精确匹配成功";
            }
            counts.merge(status,1,Integer::sum);
            records.add(new BookImportRowVO(row.rowNumber(),row.input(),row.word(),status,duplicate,message,count,count>options.size(),options));
        }
        return new BookImportPreviewVO(bookId,format.name(),records.size(),counts.getOrDefault("MATCHED",0),
            counts.getOrDefault("ALREADY_IN_BOOK",0),counts.getOrDefault("DUPLICATE",0),counts.getOrDefault("NOT_FOUND",0),
            counts.getOrDefault("AMBIGUOUS",0),counts.getOrDefault("INVALID",0),parsed.ignoredBlankCount(),parsed.ignoredColumns(),List.copyOf(ready),records);
    }

    // Build the whole bounded file before sending headers, so failures still use the normal JSON error response.
    public byte[] export(long bookId) {
        requirePersonal(bookId);
        long count=items.selectCountByQuery(QueryWrapper.create().where(column("book_id").eq(bookId)));
        if(count>MAX_EXPORT_WORDS) throw exportLimit();
        var output=new ByteArrayOutputStream(); write(output,"\uFEFFword,phonetic,translation\r\n");
        for(long offset=0;offset<count;offset+=200) {
            for(var row:items.exportWords(bookId,offset,200)) {
                write(output,BookTransferText.csvCell(row.word())+","+BookTransferText.csvCell(row.phonetic())+","+BookTransferText.csvCell(row.translation())+"\r\n");
            }
        }
        return output.toByteArray();
    }
    private void write(ByteArrayOutputStream output,String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(output.size()+bytes.length>MAX_EXPORT_BYTES) throw exportLimit();
        output.writeBytes(bytes);
    }
    private BusinessException exportLimit() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,"BOOK_EXPORT_TOO_LARGE","词本导出最多 10000 个单词、10 MiB，请拆分词本后导出");
    }
}
