package com.wordtrail.word.support;

import com.wordtrail.word.vo.TranslationGroupVO;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Presentation-only parsing. Never replaces the dictionary's original translation. */
public final class TranslationParser {
    private TranslationParser() {}

    private static final String POS = "(?:interj|suffix|prefix|modal|conj|prep|pron|abbr|aux|adj|adv|art|det|num|int|vt|vi|pl|ad|n|v|a)\\.";
    private static final Pattern MARKER = Pattern.compile(
        "(?<![\\p{L}\\p{N}_.])(" + POS + "(?:\\h*(?:/|&|,|、|and)\\h*" + POS + ")*)(?=\\h|\\p{IsHan}|[（(\\[]|$)",
        Pattern.CASE_INSENSITIVE);

    public static List<TranslationGroupVO> parse(String original) {
        if (original == null || original.isBlank()) { return List.of(); }
        // ECDICT imports contain literal backslash-n; do not apply general JSON/HTML unescaping.
        String text = original.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
            .replace("\r\n", "\n").replace('\r', '\n');
        var groups = new ArrayList<TranslationGroupVO>();
        for (String line : text.split("\n")) {
            var matcher = MARKER.matcher(line);
            String part = null, marker = null; int start = 0;
            while (matcher.find()) {
                append(groups,part,marker,line.substring(start,matcher.start()));
                marker = matcher.group(1); part = marker; start = matcher.end();
            }
            append(groups,part,marker,line.substring(start));
        }
        return List.copyOf(groups);
    }

    private static void append(List<TranslationGroupVO> groups,String part,String marker,String meaning) {
        String value = meaning.strip();
        if (value.isEmpty()) {
            // A dangling or unknown label must not disappear or acquire an invented meaning.
            if (marker != null) { groups.add(new TranslationGroupVO(null,marker)); }
        } else { groups.add(new TranslationGroupVO(part,value)); }
    }
}
