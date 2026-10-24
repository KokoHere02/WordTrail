package com.wordtrail.sentence.service;

import com.wordtrail.book.support.BookTransferText;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.wordtrail.book.support.BookTransferText.invalid;

/** Bounded RFC 4180 CSV with physical line numbers and strict quoting. */
public final class SentenceCsv {
    private SentenceCsv() {}
    public record Row(int line,List<String> cells) {}
    public static List<Row> parse(String text) {
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>BookTransferText.MAX_BYTES) throw BookTransferText.tooLarge();
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        var result=new ArrayList<Row>();var cells=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,closed=false,structured=false;int line=1,start=1;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<text.length() && text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}
                else {field.append(c);if(c=='\n' || c=='\r' && (i+1==text.length() || text.charAt(i+1)!='\n')) line++;}
            } else if(c=='"') {
                if(closed || !field.isEmpty()) throw invalid("IMPORT_CSV_INVALID","CSV 引号格式不正确");
                quoted=true;structured=true;
            } else if(c==',' || c=='\r' || c=='\n') {
                cells.add(field.toString());field.setLength(0);closed=false;
                if(c==',') structured=true;
                else {
                    append(result,start,cells,structured);cells.clear();structured=false;
                    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                    line++;start=line;
                }
            } else {if(closed) throw invalid("IMPORT_CSV_INVALID","CSV 结束引号后只能是逗号或换行");field.append(c);}
        }
        if(quoted) throw invalid("IMPORT_CSV_INVALID","CSV 存在未闭合引号");
        if(closed || !field.isEmpty() || !cells.isEmpty()){cells.add(field.toString());append(result,start,cells,structured);}
        if(result.size()<2) throw invalid("IMPORT_EMPTY","CSV 需要表头及至少一行题目");
        return List.copyOf(result);
    }
    private static void append(List<Row> result,int line,List<String> cells,boolean structured) {
        if(!structured && cells.size()==1 && cells.getFirst().isBlank()) return;
        result.add(new Row(line,List.copyOf(cells)));
        if(result.size()>101) throw invalid("IMPORT_ROW_LIMIT","每次最多导入 100 行题目");
    }
}
