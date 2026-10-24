package com.wordtrail.book.support;

import com.wordtrail.book.dto.BookImportFormat;
import com.wordtrail.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Bounded UTF-8 TXT and RFC 4180 CSV parsing; no file paths or uploaded files are persisted. */
public final class BookTransferText {
    private BookTransferText() {}
    public static final int MAX_BYTES=256*1024, MAX_ROWS=100;
    public record Row(int rowNumber,String input,String word,String problem) {}
    public record Parsed(List<Row> rows,int ignoredBlankCount,List<String> ignoredColumns) {}
    private record CsvRow(int line,List<String> cells,boolean blank) {}

    public static String decode(byte[] bytes) {
        if(bytes.length>MAX_BYTES) throw tooLarge();
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException ex) { throw invalid("IMPORT_ENCODING_UNSUPPORTED","文件须为 UTF-8 编码，可包含 UTF-8 BOM"); }
    }

    public static Parsed parse(String source,BookImportFormat format) {
        if(source==null || source.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw tooLarge();
        if(source.startsWith("\uFEFF")) source=source.substring(1);
        if(source.isBlank()) throw invalid("IMPORT_EMPTY","没有待导入单词");
        var rows=new ArrayList<Row>(); int blanks=0; var ignored=new ArrayList<String>();
        if(format==BookImportFormat.TXT) {
            String[] lines=source.split("\\r\\n|\\r|\\n",-1);
            for(int i=0;i<lines.length;i++) {
                if(lines[i].isBlank()) { blanks++; continue; }
                rows.add(row(i+1,lines[i],false)); limit(rows.size());
            }
        } else {
            var records=csv(source); int header=-1;
            for(int i=0;i<records.size();i++) {
                if(records.get(i).blank()) { blanks++; continue; }
                header=i; break;
            }
            if(header<0) throw invalid("IMPORT_EMPTY","没有 CSV 表头");
            var columns=records.get(header).cells(); int wordColumn=-1;
            for(int i=0;i<columns.size();i++) {
                String name=columns.get(i).strip();
                if(name.equalsIgnoreCase("word") || name.equals("单词") || name.equals("英文")) {
                    if(wordColumn>=0) throw invalid("IMPORT_CSV_INVALID","CSV 必须且只能有一个 word/单词/英文列");
                    wordColumn=i;
                } else { ignored.add(name); }
            }
            if(wordColumn<0) throw invalid("IMPORT_CSV_INVALID","CSV 须包含 word（或单词、英文）表头");
            for(int i=header+1;i<records.size();i++) {
                var record=records.get(i);
                if(record.blank()) { blanks++; continue; }
                if(record.cells().size()!=columns.size()) {
                    rows.add(new Row(record.line(),String.join(",",record.cells()),"","列数与 CSV 表头不一致"));
                } else { rows.add(row(record.line(),record.cells().get(wordColumn),true)); }
                limit(rows.size());
            }
        }
        return new Parsed(List.copyOf(rows),blanks,List.copyOf(ignored));
    }

    private static Row row(int line,String input,boolean csv) {
        String word=csv?restoreCell(input):input;
        word=word.strip(); String problem=null;
        if(word.isEmpty()) problem="单词不能为空";
        else if(word.length()>255) problem="单词不能超过 255 个字符";
        else if(word.codePoints().anyMatch(Character::isISOControl)) problem="单词不能含控制字符或换行";
        return new Row(line,input,word,problem);
    }
    private static void limit(int count) { if(count>MAX_ROWS) throw invalid("IMPORT_ROW_LIMIT","每次最多预览 100 个非空数据行，请分批导入"); }

    // Preserve quoted commas/newlines and escaped quotes. Malformed quoting rejects the entire input.
    private static List<CsvRow> csv(String source) {
        var records=new ArrayList<CsvRow>(); var cells=new ArrayList<String>(); var field=new StringBuilder();
        boolean quoted=false,closed=false,structured=false; int line=1,start=1;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(quoted) {
                if(c=='"') {
                    if(i+1<source.length() && source.charAt(i+1)=='"') { field.append('"'); i++; }
                    else { quoted=false; closed=true; }
                } else {
                    field.append(c);
                    if(c=='\n' || c=='\r' && (i+1==source.length() || source.charAt(i+1)!='\n')) line++;
                }
            } else if(c=='"') {
                if(closed || field.length()!=0) throw invalid("IMPORT_CSV_INVALID","CSV 引号格式不正确");
                quoted=true; structured=true;
            } else if(c==',' || c=='\r' || c=='\n') {
                if(c==',') structured=true;
                cells.add(field.toString()); field.setLength(0); closed=false;
                if(c!=',') {
                    records.add(new CsvRow(start,List.copyOf(cells),!structured && cells.size()==1 && cells.getFirst().isBlank())); cells.clear(); structured=false;
                    if(c=='\r' && i+1<source.length() && source.charAt(i+1)=='\n') i++;
                    line++; start=line;
                }
            } else {
                if(closed) throw invalid("IMPORT_CSV_INVALID","CSV 结束引号后只能是逗号或换行");
                field.append(c);
            }
        }
        if(quoted) throw invalid("IMPORT_CSV_INVALID","CSV 存在未闭合引号");
        if(closed || field.length()>0 || !cells.isEmpty()) {
            cells.add(field.toString()); records.add(new CsvRow(start,List.copyOf(cells),!structured && cells.size()==1 && cells.getFirst().isBlank()));
        }
        return records;
    }

    /** Spreadsheet protection is reversible, including literal apostrophes in the source cell. */
    public static String csvCell(String original) {
        String value=original==null?"":original;
        if(value.startsWith("'") || formula(value)) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static String restoreCell(String value) {
        if(value.startsWith("'") && (value.substring(1).startsWith("'") || formula(value.substring(1)))) return value.substring(1);
        return value;
    }
    private static boolean formula(String value) {
        String text=value.stripLeading();
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0))>=0;
    }
    public static BusinessException tooLarge() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,"IMPORT_TOO_LARGE","导入内容或文件不能超过 256 KiB");
    }
    public static BusinessException invalid(String code,String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST,code,message);
    }
}
