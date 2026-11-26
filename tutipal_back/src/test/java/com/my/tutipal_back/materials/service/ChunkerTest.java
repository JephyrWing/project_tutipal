package com.my.tutipal_back.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.client.AiClient.Page;

/**
 * PT-86 spec 6: document-level sentence/heading chunker (section-v1) next to the old char splitter (char800-v1).
 * Chunker.split(List<Page>) -> List<Chunker.Chunk(page, headingPath, content)>.
 */
class ChunkerTest {

    private static final int TARGET = 800;

    // ---- helpers ----

    /** A normal Korean sentence (~45 chars) ending with "다." and carrying a marker word. */
    private static String sentence(String marker, int i) {
        return "이것은 " + marker + " " + i + "번째 문장이며 정렬 알고리즘의 안정성과 시간 복잡도를 함께 설명합니다.";
    }

    private static String sentences(String marker, int count) {
        return IntStream.range(0, count).mapToObj(i -> sentence(marker, i)).collect(Collectors.joining(" "));
    }

    private static String prefix(List<String> headingPath) {
        return "[" + String.join(" > ", headingPath) + "]";
    }

    /** Content without the "[a > b]" first line (if the chunk has a heading path). */
    private static String body(Chunker.Chunk chunk) {
        if (chunk.headingPath().isEmpty()) {
            return chunk.content();
        }
        String first = prefix(chunk.headingPath()) + "\n";
        assertThat(chunk.content()).startsWith(first);
        return chunk.content().substring(first.length());
    }

    private static List<String> sentencesOf(String body) {
        return Arrays.stream(body.strip().split("(?<=[.?!])\\s+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ---- empty / short input ----

    @Test
    void emptyPageListGivesNoChunks() {
        assertThat(Chunker.split(List.of())).isEmpty();
    }

    @Test
    void blankPagesGiveNoChunks() {
        assertThat(Chunker.split(List.of(new Page(1, ""), new Page(2, "   \n \r\n  ")))).isEmpty();
    }

    @Test
    void piecesShorterThanTwentyCharsAreDropped() {
        assertThat(Chunker.split(List.of(new Page(1, "12"), new Page(2, "- 3 -"), new Page(3, "짧은 글이다.")))).isEmpty();
    }

    @Test
    void textPageKeepsNullPageNumber() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(null, sentences("가람", 5))));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.page()).isNull());
    }

    // ---- sentence boundaries ----

    @Test
    void normalKoreanSentencesAreNeverCutInTheMiddle() {
        String text = sentences("가람", 40);
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, text)));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        for (Chunker.Chunk chunk : chunks) {
            String body = body(chunk);
            assertThat(body).startsWith("이것은 ");
            assertThat(body).endsWith("다.");
            assertThat(body.length()).isLessThanOrEqualTo(TARGET);
        }
        for (int i = 0; i < 40; i++) {
            String s = sentence("가람", i);
            assertThat(chunks).as("sentence %d kept whole", i).anySatisfy(c -> assertThat(c.content()).contains(s));
        }
    }

    @Test
    void questionExclamationAndYoEndingsAreSentenceBoundaries() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            parts.add(switch (i % 3) {
                case 0 -> "버블 정렬은 " + i + "번째 예시에서 인접한 두 원소를 비교하는 방법인가요?";
                case 1 -> "선택 정렬은 " + i + "번째 예시에서 가장 작은 원소를 앞으로 보내요.";
                default -> "삽입 정렬은 " + i + "번째 예시에서 거의 정렬된 배열에 정말 빠르다!";
            });
        }
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, String.join(" ", parts))));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (Chunker.Chunk chunk : chunks) {
            String body = body(chunk);
            assertThat(body).matches("(?s).*(요\\.|\\?|!|다\\.)$");
            assertThat(body.length()).isLessThanOrEqualTo(TARGET);
        }
        for (String part : parts) {
            assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains(part));
        }
    }

    @Test
    void newlineIsASentenceBoundaryForLinesWithoutPunctuation() {
        List<String> lines = IntStream.range(0, 60)
                .mapToObj(i -> "- 항목 " + i + " 연결 리스트 노드 삽입과 삭제 비용 정리")
                .toList();
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, String.join("\n", lines))));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (Chunker.Chunk chunk : chunks) {
            for (String line : body(chunk).split("\n")) {
                if (!line.isBlank()) {
                    assertThat(lines).as("chunk line is a whole source line").contains(line.strip());
                }
            }
        }
    }

    @Test
    void overlapIsTheLastSentenceOfThePreviousChunk() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, sentences("가람", 40))));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 1; i < chunks.size(); i++) {
            List<String> previous = sentencesOf(body(chunks.get(i - 1)));
            List<String> current = sentencesOf(body(chunks.get(i)));
            assertThat(current.getFirst()).isEqualTo(previous.getLast());
            assertThat(current.size()).as("chunk adds new sentences beyond the overlap").isGreaterThan(1);
        }
    }

    @Test
    void overLongSentenceIsHardSplitAtTarget() {
        String longSentence = "가나다라마바사아자차카타파하".repeat(180) + "다.";   // 2522 chars, one sentence
        List<Chunker.Chunk> chunks = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Chunker.split(List.of(new Page(1, longSentence))));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
        int total = 0;
        for (Chunker.Chunk chunk : chunks) {
            String body = body(chunk);
            assertThat(body.length()).isLessThanOrEqualTo(TARGET);
            assertThat(longSentence).contains(body);
            total += body.length();
        }
        assertThat(total).isGreaterThanOrEqualTo(longSentence.length());
        assertThat(body(chunks.getFirst())).isEqualTo(longSentence.substring(0, body(chunks.getFirst()).length()));
        assertThat(body(chunks.getLast())).endsWith("다.");
    }

    @Test
    void crlfGivesSameChunksAsLf() {
        String lf = "# 1장 소개\n\n" + sentences("가람", 20).replace(". ", ".\n") + "\n\n## 1.2 정렬\n\n" + sentences("나래", 20);
        String crlf = lf.replace("\n", "\r\n");

        List<Chunker.Chunk> fromLf = Chunker.split(List.of(new Page(1, lf)));
        List<Chunker.Chunk> fromCrlf = Chunker.split(List.of(new Page(1, crlf)));

        assertThat(fromCrlf).isEqualTo(fromLf);
        assertThat(fromCrlf).allSatisfy(c -> assertThat(c.content()).doesNotContain("\r"));
    }

    // ---- page boundaries ----

    @Test
    void chunkNeverSpansPages() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, sentences("알파", 25)),
                new Page(2, sentences("베타", 25))));

        assertThat(chunks).extracting(Chunker.Chunk::page).containsOnly(1, 2);
        assertThat(chunks).filteredOn(c -> c.page() == 1).isNotEmpty()
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("베타"));
        assertThat(chunks).filteredOn(c -> c.page() == 2).isNotEmpty()
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("알파"));
        assertThat(chunks).extracting(Chunker.Chunk::page).isSorted();
    }

    // ---- headings ----

    @Test
    void noHeadingMeansNoPrefixLine() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, sentences("가람", 30))));

        assertThat(chunks).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).isEmpty();
            assertThat(c.content()).doesNotStartWith("[");
            assertThat(c.content()).startsWith("이것은 ");
        });
    }

    @Test
    void markdownHeadingsBuildNestedPathAndPrefixFirstLineOnly() {
        String text = "# 1장 소개\n\n" + sentences("알파", 8) + "\n\n## 1.2 정렬\n\n" + sentences("베타", 8);
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(null, text)));

        List<Chunker.Chunk> alpha = chunks.stream().filter(c -> c.content().contains("알파")).toList();
        List<Chunker.Chunk> beta = chunks.stream().filter(c -> c.content().contains("베타")).toList();
        assertThat(alpha).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).containsExactly("1장 소개");
            assertThat(c.content().lines().findFirst()).hasValue("[1장 소개]");
        });
        assertThat(beta).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).containsExactly("1장 소개", "1.2 정렬");
            assertThat(c.content().lines().findFirst()).hasValue("[1장 소개 > 1.2 정렬]");
            assertThat(c.content().lines().filter(l -> l.startsWith("[")).count()).isEqualTo(1);
        });
    }

    @Test
    void chunkDoesNotMixTwoSections() {
        String text = "# 1장 소개\n\n" + sentences("알파", 8) + "\n\n## 1.2 정렬\n\n" + sentences("베타", 8);

        assertThat(Chunker.split(List.of(new Page(1, text))))
                .noneSatisfy(c -> assertThat(c.content()).contains("알파").contains("베타"));
    }

    @Test
    void markdownSiblingHeadingReplacesDeeperLevels() {
        String text = "# 가장\n\n" + sentences("알파", 3)
                + "\n\n## 나절\n\n" + sentences("베타", 3)
                + "\n\n### 다항\n\n" + sentences("감마", 3)
                + "\n\n## 라절\n\n" + sentences("델타", 3)
                + "\n\n# 마장\n\n" + sentences("엡실론", 3);
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(null, text)));

        assertThat(pathOf(chunks, "감마")).containsExactly("가장", "나절", "다항");
        assertThat(pathOf(chunks, "델타")).containsExactly("가장", "라절");
        assertThat(pathOf(chunks, "엡실론")).containsExactly("마장");
    }

    @Test
    void numberedHeadingPathCarriesAcrossPages() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, "제1장 자료구조\n1.2 정렬\n" + sentences("알파", 6)),
                new Page(2, sentences("베타", 6))));

        List<Chunker.Chunk> page2 = chunks.stream().filter(c -> c.page() == 2).toList();
        assertThat(page2).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).containsExactly("제1장 자료구조", "1.2 정렬");
            assertThat(c.content()).startsWith("[제1장 자료구조 > 1.2 정렬]\n");
            assertThat(c.content()).doesNotContain("알파");
        });
        assertThat(pathOf(chunks, "알파")).containsExactly("제1장 자료구조", "1.2 정렬");
    }

    @Test
    void markdownHeadingPathCarriesAcrossPages() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, "# 1장 소개\n## 1.2 정렬\n" + sentences("알파", 4)),
                new Page(2, sentences("베타", 4))));

        assertThat(chunks).filteredOn(c -> c.page() == 2).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).containsExactly("1장 소개", "1.2 정렬");
            assertThat(c.content().lines().findFirst()).hasValue("[1장 소개 > 1.2 정렬]");
        });
    }

    @Test
    void numberedSiblingAndNextChapterReplaceThePath() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, "제1장 자료구조\n1.2 정렬\n" + sentences("알파", 4)),
                new Page(2, "1.3 탐색\n" + sentences("베타", 4)),
                new Page(3, "1.3.1 이진 탐색\n" + sentences("감마", 4)),
                new Page(4, "제2장 그래프\n" + sentences("델타", 4))));

        assertThat(pathOf(chunks, "베타")).containsExactly("제1장 자료구조", "1.3 탐색");
        assertThat(pathOf(chunks, "감마")).containsExactly("제1장 자료구조", "1.3 탐색", "1.3.1 이진 탐색");
        assertThat(pathOf(chunks, "델타")).containsExactly("제2장 그래프");
    }

    @Test
    void singleNumberHeadingIsRecognised() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, "1. 서론\n" + sentences("알파", 4))));

        assertThat(pathOf(chunks, "알파")).containsExactly("1. 서론");
    }

    // Added after review round 1 (leader ruling 4b): list-detection must not demote real numbered headings.

    @Test
    void singleNumberHeadingFollowedBySubheadingKeepsBothLevels() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, "1. 서론\n1.1 배경\n" + sentences("알파", 4))));

        assertThat(pathOf(chunks, "알파")).containsExactly("1. 서론", "1.1 배경");
    }

    @Test
    void singleNumberHeadingsSeparatedByBodyAreSiblings() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1,
                "1. 서론\n" + sentences("알파", 4) + "\n2. 본론\n" + sentences("베타", 4))));

        assertThat(pathOf(chunks, "알파")).containsExactly("1. 서론");
        assertThat(pathOf(chunks, "베타")).containsExactly("2. 본론");
    }

    @Test
    void numberedLineEndingWithSentencePunctuationIsNotAHeading() {
        String text = "1. 리스트는 순서가 있는 자료구조이다.\n2. 스택은 나중에 넣은 것을 먼저 꺼낸다.\n" + sentences("알파", 4);
        List<Chunker.Chunk> chunks = Chunker.split(List.of(new Page(1, text)));

        assertThat(chunks).isNotEmpty().allSatisfy(c -> {
            assertThat(c.headingPath()).isEmpty();
            assertThat(c.content()).doesNotStartWith("[");
        });
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("1. 리스트는 순서가 있는 자료구조이다."));
    }

    private static List<String> pathOf(List<Chunker.Chunk> chunks, String marker) {
        List<Chunker.Chunk> matching = chunks.stream().filter(c -> c.content().contains(marker)).toList();
        assertThat(matching).as("chunks containing %s", marker).isNotEmpty();
        List<String> path = matching.getFirst().headingPath();
        assertThat(matching).allSatisfy(c -> assertThat(c.headingPath()).isEqualTo(path));
        return path;
    }

    // ---- regression: old char splitter (char800-v1) unchanged ----

    @Test
    void charSplitterMergesShortParagraphsAndOverlapsHundredChars() {
        String p1 = "가".repeat(500);
        String p2 = "나".repeat(500);

        assertThat(Chunker.split(p1 + "\n\n" + p2, 800, 100))
                .containsExactly(p1, "가".repeat(98) + "\n\n" + p2);
    }

    @Test
    void charSplitterCutsLongParagraphEveryEightHundredChars() {
        assertThat(Chunker.split("x".repeat(1700), 800, 100)).containsExactly(
                "x".repeat(800),
                "x".repeat(98) + "\n\n" + "x".repeat(800),
                "x".repeat(98) + "\n\n" + "x".repeat(100));
    }

    @Test
    void charSplitterNormalisesCrlfAndSpaces() {
        assertThat(Chunker.split("첫 문단입니다 충분히 긴 글자\r\n\r\n\r\n\r\n둘째   문단\t입니다 역시 충분히 긴 글", 800, 100))
                .containsExactly("첫 문단입니다 충분히 긴 글자\n\n둘째 문단 입니다 역시 충분히 긴 글");
    }

    @Test
    void charSplitterDropsShortAndEmptyText() {
        assertThat(Chunker.split("12", 800, 100)).isEmpty();
        assertThat(Chunker.split("", 800, 100)).isEmpty();
    }
}
