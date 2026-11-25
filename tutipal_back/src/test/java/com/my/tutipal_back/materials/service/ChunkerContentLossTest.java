package com.my.tutipal_back.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.client.AiClient.Page;

/** PT-86 review round 1: section-v1 must not lose body text (list items, code, short tails/sections, long headings). */
class ChunkerContentLossTest {

    private static List<Chunker.Chunk> split(String text) {
        return Chunker.split(List.of(new Page(1, text)));
    }

    private static String all(List<Chunker.Chunk> chunks) {
        return chunks.stream().map(Chunker.Chunk::content).collect(Collectors.joining("\n"));
    }

    @Test
    void numberedListItemsWithoutPeriodStayInBody() {
        List<Chunker.Chunk> chunks = split("## 정렬\n1. 버블 정렬은 인접한 두 원소를 비교한다\n2. 선택 정렬은 최솟값을 고른다\n"
                + "3. 삽입 정렬은 앞쪽을 정렬된 상태로 유지한다");

        assertThat(all(chunks)).contains("1. 버블 정렬은 인접한 두 원소를 비교한다", "2. 선택 정렬은 최솟값을 고른다",
                "3. 삽입 정렬은 앞쪽을 정렬된 상태로 유지한다");
        assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).containsExactly("정렬"));
    }

    @Test
    void listItemsDoNotReplaceTheEnclosingHeadingPath() {
        String text = "제2장 스택\n2.1 스택의 연산\n스택은 나중에 넣은 원소를 먼저 꺼내는 자료구조입니다.\n"
                + "1. 원소를 넣는 push 연산\n2. 원소를 꺼내는 pop 연산\n3. 맨 위를 보는 peek 연산\n"
                + "세 연산은 모두 상수 시간에 끝나는 것이 특징입니다.";
        List<Chunker.Chunk> chunks = split(text);

        assertThat(all(chunks)).contains("1. 원소를 넣는 push 연산", "2. 원소를 꺼내는 pop 연산", "3. 맨 위를 보는 peek 연산");
        assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).containsExactly("제2장 스택", "2.1 스택의 연산"));
    }

    @Test
    void singleListLineEndingWithKoreanPredicateIsBody() {
        List<Chunker.Chunk> chunks = split("# 소개\n1. 큐는 먼저 넣은 원소를 먼저 꺼낸다\n큐는 줄 서기와 같은 방식으로 동작하는 자료구조입니다.");

        assertThat(all(chunks)).contains("1. 큐는 먼저 넣은 원소를 먼저 꺼낸다");
        assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).containsExactly("소개"));
    }

    @Test
    void numberedHeadingFollowedBySubheadingIsStillAHeading() {
        List<Chunker.Chunk> chunks = split("1. 서론\n1.1 배경\n이 문서는 정렬 알고리즘의 배경을 충분히 길게 설명하는 본문입니다.");

        assertThat(chunks).singleElement().satisfies(c -> assertThat(c.headingPath()).containsExactly("1. 서론", "1.1 배경"));
    }

    @Test
    void hashLinesInsideCodeFenceAreNotHeadings() {
        String text = "## 2.2 스택의 응용\n괄호 검사에 스택을 쓰는 예제를 아래 코드로 보입니다.\n```python\n# 스택 만들기\nstack = []\n"
                + "stack.append(1)\n```\n코드를 실행하면 스택에 원소 하나가 들어가 있게 됩니다.";
        List<Chunker.Chunk> chunks = split(text);

        assertThat(chunks).allSatisfy(c -> assertThat(c.headingPath()).containsExactly("2.2 스택의 응용"));
        assertThat(all(chunks)).contains("# 스택 만들기", "stack.append(1)", "코드를 실행하면");
    }

    @Test
    void hardSplitKeepsShortTail() {
        String sentence = "가".repeat(810) + " 정답은 42번";
        List<Chunker.Chunk> chunks = split(sentence);

        assertThat(chunks).hasSize(2).allSatisfy(c -> assertThat(c.content().length()).isBetween(20, 800));
        assertThat(chunks.getFirst().content() + chunks.getLast().content()).isEqualTo(sentence);
    }

    @Test
    void shortSentenceBeforeOverLongSentenceIsKept() {
        // Codex round 1: the short sentence used to be emitted alone (< 20 chars) and dropped.
        String longSentence = "가".repeat(900) + ".";
        List<Chunker.Chunk> chunks = split("짧은 문장. " + longSentence);

        assertThat(all(chunks)).contains("짧은 문장.");
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isBetween(20, 800));
        assertThat(chunks.stream().map(Chunker.Chunk::content).collect(Collectors.joining()))
                .isEqualTo("짧은 문장. " + longSentence);
    }

    @Test
    void shortLineBeforeOverLongLineIsKept() {
        List<Chunker.Chunk> chunks = split("짧은 줄\n" + "나".repeat(1000) + ".");

        assertThat(all(chunks)).contains("짧은 줄");
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(800));
    }

    @Test
    void shortSentenceThatJustDoesNotFitWithTheNextIsKept() {
        // 6 + 1 + 796 = 803 > 800: the pair does not fit one chunk, the short one alone is under 20 chars.
        String next = "다".repeat(795) + ".";
        List<Chunker.Chunk> chunks = split("짧은 문장. " + next);

        assertThat(all(chunks)).contains("짧은 문장.");
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isBetween(20, 800));
    }

    @Test
    void shortSectionClosedByNextHeadingIsKept() {
        List<Chunker.Chunk> chunks = split("## 정답\n42번.\n## 해설\n광합성은 엽록체에서 일어나는 과정이다.");

        assertThat(chunks).extracting(Chunker.Chunk::content)
                .containsExactly("[정답]\n42번.", "[해설]\n광합성은 엽록체에서 일어나는 과정이다.");
    }

    @Test
    void shortTextAtPageEndIsStillDropped() {
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, "# 1장\n" + "이것은 정렬 알고리즘을 설명하는 충분히 긴 본문 문장입니다.\n"),
                new Page(2, "운영체제 노트 · 2쪽")));

        assertThat(chunks).extracting(Chunker.Chunk::page).containsOnly(1);
    }

    @Test
    void shortFooterAtPageStartBeforeFirstHeadingIsStillDropped() {
        // PDFBox can emit the page footer before the page's first heading; it belongs to the carried-over heading.
        List<Chunker.Chunk> chunks = Chunker.split(List.of(
                new Page(1, "제1장 동기화\n1.1 경쟁 상태\n여러 프로세스가 공유 자원에 동시에 접근하면 결과가 달라질 수 있다.\n"),
                new Page(2, "운영체제 노트 · 2쪽\n1.2 임계 구역\n임계 구역에는 한 번에 하나의 프로세스만 들어가야 한다.\n")));

        assertThat(all(chunks)).doesNotContain("2쪽");
        assertThat(chunks).hasSize(2);
    }

    @Test
    void headingPrefixIsBounded() {
        String heading = "제목".repeat(200);
        StringBuilder text = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            text.append("#".repeat(level)).append(' ').append(heading.substring(0, 90)).append('\n');
        }
        text.append("# ").append("긴".repeat(1500)).append('\n');
        text.append("본문은 정렬 알고리즘의 안정성을 설명하는 충분히 긴 문장입니다.");
        List<Chunker.Chunk> chunks = split(text.toString());

        assertThat(chunks).isNotEmpty().allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(2000);
            assertThat(c.headingPath()).allSatisfy(name -> assertThat(name.length()).isLessThanOrEqualTo(51));
        });
        assertThat(all(chunks)).contains("긴".repeat(700)).contains("본문은 정렬 알고리즘의");
    }

    @Test
    void manyListLinesProduceChunks() {
        String text = IntStream.range(1, 200).mapToObj(i -> i + ". 항목 " + i + " 설명").collect(Collectors.joining("\n"));

        assertThat(all(split(text))).contains("1. 항목 1 설명", "199. 항목 199 설명");
    }
}
