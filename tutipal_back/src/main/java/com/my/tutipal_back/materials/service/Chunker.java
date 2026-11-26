package com.my.tutipal_back.materials.service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.my.tutipal_back.ai.client.AiClient;

/**
 * 자료 글을 검색용 조각으로 자른다. 두 방식이 있고 tutipal.materials.chunker 로 고른다 (PT-86).
 * <ul>
 *   <li>{@value #CHAR_V1}: {@link #split(String, int, int)} 문단(빈 줄) 단위로 모으다가 size 를 넘으면 자르고,
 *       앞 조각 끝 일부를 겹쳐 문맥을 잇는다. 글자 수 기준 단순 분할 (되돌리기 · 비교용으로 그대로 둔다).</li>
 *   <li>{@value #SECTION_V1}: {@link #split(List)} 제목 경로를 쪽을 넘어 이어 가며 문장 단위로 800자까지 모은다.
 *       겹침은 앞 조각의 마지막 문장. 조각은 쪽을 넘지 않고, 제목이 있으면 첫 줄이 "[1장 &gt; 1.2 절]".</li>
 * </ul>
 */
final class Chunker {

    static final String CHAR_V1 = "char800-v1";
    static final String SECTION_V1 = "section-v1";

    /** 조각 하나. headingPath 는 바깥 제목부터, 없으면 빈 목록. content 는 저장·임베딩하는 글 그대로. */
    record Chunk(Integer page, List<String> headingPath, String content) {
        Chunk {
            headingPath = List.copyOf(headingPath);
        }
    }

    private static final int TARGET = 800;
    private static final int MIN_CHARS = 20;
    /** 번호 제목은 짧은 한 줄만 (본문 번호 목록과 구분). */
    private static final int MAX_NUMBERED_HEADING_CHARS = 60;
    /** 이보다 긴 MD "#" 줄은 제목이 아니라 본문으로 본다. */
    private static final int MAX_MARKDOWN_HEADING_CHARS = 100;
    private static final int MAX_HEADING_NAME_CHARS = 50;
    private static final Pattern CODE_FENCE = Pattern.compile("^(```|~~~).*");
    private static final Pattern KOREAN_PREDICATE_END = Pattern.compile(".*(다|요)$");

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^제\\s*\\d{1,3}\\s*장(?:\\s+\\S.*)?$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3})*)\\.?\\s+\\S.*$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.?!。…]$");
    /** BreakIterator 가 놓치는 한국어 문장 끝 보정: "다." "요." "?" "!" 뒤 공백에서 끊는다. */
    private static final Pattern KOREAN_SENTENCE_GAP = Pattern.compile("(?<=[다요]\\.|[?!])\\s+");
    /** "1." 같은 목록 번호만 떨어져 나온 조각은 다음 문장에 붙인다. */
    private static final Pattern ENUMERATOR = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3})*\\.$");

    private Chunker() {
    }

    static List<String> split(String text, int size, int overlap) {
        String clean = text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").replaceAll("\n{3,}", "\n\n").strip();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : clean.split("\n\n")) {
            for (int i = 0; i < para.length(); i += size) {   // 너무 긴 문단은 size 단위로
                String piece = para.substring(i, Math.min(para.length(), i + size));
                if (cur.length() > 0 && cur.length() + piece.length() > size) {
                    out.add(cur.toString().strip());
                    String done = cur.toString();
                    cur = new StringBuilder(done.substring(Math.max(0, done.length() - overlap)));
                }
                cur.append(piece).append("\n\n");
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out.stream().filter(s -> s.length() >= 20).toList();   // 쪽 번호 · 머리글 같은 짧은 조각은 버림
    }

    /** section-v1: 문서 전체(쪽 순서대로)를 제목 · 문장 기준으로 자른다. */
    static List<Chunk> split(List<AiClient.Page> pages) {
        List<Chunk> out = new ArrayList<>();
        List<Heading> path = new ArrayList<>();   // 쪽이 바뀌어도 이어진다
        boolean inFence = false;                  // ``` 코드 블록도 쪽을 넘어 이어질 수 있다
        for (AiClient.Page page : pages) {
            List<Sentence> section = new ArrayList<>();
            boolean headingOnPage = false;
            String text = page.text() == null ? "" : page.text();
            List<String> lines = new ArrayList<>();
            for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
                String line = raw.replaceAll("[ \\t\\u00A0]+", " ").strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (CODE_FENCE.matcher(line).matches()) {
                    inFence = !inFence;
                }
                if (inFence || CODE_FENCE.matcher(line).matches()) {
                    section.add(new Sentence(line, true));   // 코드 줄은 제목·문장으로 해석하지 않고 그대로 둔다
                    continue;
                }
                Heading heading = heading(line, i > 0 ? lines.get(i - 1) : null,
                        i + 1 < lines.size() ? lines.get(i + 1) : null);
                if (heading != null) {
                    // 한 조각에 두 절을 섞지 않는다. 같은 쪽에서 시작해 다음 제목으로 닫힌 짧은 절 본문은 버리지 않는다.
                    // (쪽 머리의 짧은 줄은 앞 쪽에서 이어진 제목 아래 꼬리말·쪽 번호일 수 있어 기존처럼 버린다)
                    pack(page.page(), names(path), section, out, headingOnPage);
                    section = new ArrayList<>();
                    path.removeIf(h -> h.level() >= heading.level());
                    path.add(heading);
                    headingOnPage = true;
                    continue;
                }
                List<String> parts = sentences(line);
                for (int p = 0; p < parts.size(); p++) {
                    section.add(new Sentence(parts.get(p), p == 0));
                }
            }
            // 조각은 쪽을 넘지 않는다. 쪽 끝의 짧은 조각은 쪽 번호 · 꼬리말일 수 있어 버린다.
            pack(page.page(), names(path), section, out, false);
        }
        return out;
    }

    private record Heading(int level, String name) {
    }

    /** startsLine: 원문에서 줄의 첫 문장이면 true (다시 이을 때 공백 대신 줄바꿈). */
    private record Sentence(String text, boolean startsLine) {
    }

    private static List<String> names(List<Heading> path) {
        return path.stream().map(Heading::name).toList();
    }

    /** 제목 경로 접두어가 본문을 밀어내지 않도록 이름 하나를 자른다 (6단계 × 51자 이하). */
    private static String headingName(String text) {
        return text.length() <= MAX_HEADING_NAME_CHARS ? text : text.substring(0, MAX_HEADING_NAME_CHARS) + "…";
    }

    /**
     * MD "#".."######"(본문 100자 이하) 또는 짧은 번호 줄("제1장 …", "1. …", "1.2 …", "1.2.3 …", 문장 부호로 끝나지 않음).
     * 번호 줄은 바로 위·아래 줄이 같은 깊이의 번호 줄이면 목록 항목으로 보고 본문에 둔다 ("1. push\n2. pop").
     * "1. …" 꼴은 한국어 서술어("…다", "…요")로 끝나면 목록 문장으로 본다.
     */
    private static Heading heading(String line, String previous, String next) {
        Matcher md = MARKDOWN_HEADING.matcher(line);
        if (md.matches()) {
            String name = md.group(2).strip();
            return name.length() > MAX_MARKDOWN_HEADING_CHARS ? null : new Heading(md.group(1).length(), headingName(name));
        }
        if (line.length() > MAX_NUMBERED_HEADING_CHARS || SENTENCE_END.matcher(line).matches()) {
            return null;
        }
        if (CHAPTER_HEADING.matcher(line).matches()) {
            return new Heading(1, headingName(line));
        }
        int depth = numberDepth(line);
        if (depth == 0 || depth == numberDepth(previous) || depth == numberDepth(next)) {
            return null;
        }
        if (depth == 1 && KOREAN_PREDICATE_END.matcher(line).matches()) {
            return null;
        }
        return new Heading(depth, headingName(line));
    }

    /** "1. …" → 1, "1.2 …" → 2, "1.2.3 …" → 3. 점 없는 숫자("2024 회고")나 번호 줄이 아니면 0. */
    private static int numberDepth(String line) {
        if (line == null) {
            return 0;
        }
        Matcher numbered = NUMBERED_HEADING.matcher(line);
        if (!numbered.matches()) {
            return 0;
        }
        String number = numbered.group(1);
        boolean dotted = number.contains(".") || line.startsWith(number + ".");
        return dotted ? number.split("\\.").length : 0;
    }

    /** 한 줄을 문장으로. BreakIterator(KOREAN) 뒤에 한국어 문장 끝 보정을 한 번 더 한다. */
    private static List<String> sentences(String line) {
        List<String> pieces = new ArrayList<>();
        BreakIterator it = BreakIterator.getSentenceInstance(Locale.KOREAN);
        it.setText(line);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            for (String part : KOREAN_SENTENCE_GAP.split(line.substring(start, end))) {
                String s = part.strip();
                if (!s.isEmpty()) {
                    pieces.add(s);
                }
            }
        }
        List<String> merged = new ArrayList<>(pieces.size());
        String pending = null;
        for (String piece : pieces) {
            if (pending != null) {
                piece = pending + " " + piece;
                pending = null;
            }
            if (ENUMERATOR.matcher(piece).matches()) {
                pending = piece;
            } else {
                merged.add(piece);
            }
        }
        if (pending != null) {
            merged.add(pending);
        }
        return merged;
    }

    /**
     * 문장을 TARGET 자까지 모은다. 다음 조각은 앞 조각의 마지막 문장으로 시작한다. 너무 긴 문장은 TARGET 자마다 자른다.
     * keepShort: 다음 제목으로 닫힌 절 하나가 통째로 20자 미만이어도 버리지 않는다 ("## 정답\n42번.").
     */
    private static void pack(Integer page, List<String> headingPath, List<Sentence> sentences, List<Chunk> out,
                             boolean keepShort) {
        List<Sentence> cur = new ArrayList<>();
        int length = 0;
        boolean fresh = false;   // 겹침 문장 말고 새 문장이 들어 있는지
        boolean emitted = false;
        for (Sentence s : sentences) {
            int added = cur.isEmpty() ? s.text().length() : length + 1 + s.text().length();
            if (added > TARGET && fresh && length < MIN_CHARS) {
                // 모아 둔 글이 20자 미만이면 따로 내보내는 순간 버려진다 → 다음 문장 앞에 붙여 함께 자른다.
                // ("짧은 문장." + 800자 넘는 문장, 또는 합쳐 800자를 조금 넘는 경우)
                hardSplit(page, headingPath, body(cur) + (s.startsLine() ? '\n' : ' ') + s.text(), out);
                cur = new ArrayList<>();
                length = 0;
                fresh = false;
                emitted = true;
                continue;
            }
            if (s.text().length() > TARGET) {
                if (fresh) {
                    emit(page, headingPath, cur, out, MIN_CHARS);
                }
                hardSplit(page, headingPath, s.text(), out);
                cur = new ArrayList<>();
                length = 0;
                fresh = false;
                emitted = true;
                continue;
            }
            if (added <= TARGET) {
                cur.add(s);
                length = added;
                fresh = true;
                continue;
            }
            emit(page, headingPath, cur, out, MIN_CHARS);
            emitted = true;
            Sentence last = cur.getLast();
            cur = new ArrayList<>();
            if (last.text().length() + 1 + s.text().length() <= TARGET) {
                cur.add(last);
                length = last.text().length() + 1 + s.text().length();
            } else {
                length = s.text().length();
            }
            cur.add(s);
            fresh = true;
        }
        if (fresh) {
            emit(page, headingPath, cur, out, keepShort && !emitted ? 1 : MIN_CHARS);
        }
    }

    private static void emit(Integer page, List<String> headingPath, List<Sentence> sentences, List<Chunk> out,
                             int minChars) {
        add(page, headingPath, body(sentences), out, minChars);
    }

    private static String body(List<Sentence> sentences) {
        StringBuilder body = new StringBuilder();
        for (Sentence s : sentences) {
            if (!body.isEmpty()) {
                body.append(s.startsLine() ? '\n' : ' ');
            }
            body.append(s.text());
        }
        return body.toString();
    }

    /** TARGET 자마다 자른다. 마지막 조각이 20자 미만으로 남으면 마지막 두 조각을 반씩 나눠 꼬리를 버리지 않는다. */
    private static void hardSplit(Integer page, List<String> headingPath, String sentence, List<Chunk> out) {
        int start = 0;
        while (start < sentence.length()) {
            int remaining = sentence.length() - start;
            int size = remaining > TARGET && remaining - TARGET < MIN_CHARS ? (remaining + 1) / 2 : TARGET;
            int end = Math.min(sentence.length(), start + size);
            if (end < sentence.length() && Character.isHighSurrogate(sentence.charAt(end - 1))) {
                end--;
            }
            add(page, headingPath, sentence.substring(start, end), out, MIN_CHARS);
            start = end;
        }
    }

    private static void add(Integer page, List<String> headingPath, String body, List<Chunk> out, int minChars) {
        if (body.length() < minChars) {
            return;   // 쪽 번호 · 머리글 같은 짧은 조각은 버림
        }
        String content = headingPath.isEmpty() ? body : "[" + String.join(" > ", headingPath) + "]\n" + body;
        out.add(new Chunk(page, headingPath, content));
    }
}
