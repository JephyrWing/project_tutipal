"""Turn fetched Korean Wikipedia plain extracts into trimmed Markdown corpus docs.

    python adapt_wiki.py <dir written by fetch_wiki.py> <corpus dir>

Each entry lists the sections dropped (with their subsections). Every edit made
here is also described in corpus/SOURCES.md, as CC BY-SA 4.0 requires.
"""
import re
import sys

WIKI, OUT = sys.argv[1], sys.argv[2]

DOCS = [
    ("w0.txt", "session-a/a2-deadlock-wiki.md",
     {"같이 보기", "각주", "외부 링크"}),
    ("w1.txt", "session-a/a1-hash-table-wiki.md",
     {"역사", "문자열 해싱", "해시 함수 선택", "분리 체이닝을 위한 다른 자료 구조", "캐싱 및 참조 국부성",
      "병합 해싱", "홉스카치 해싱", "로빈 후드 해싱", "일괄 재해싱의 대안", "선형 해싱", "전치 테이블",
      "구현", "참조", "같이 보기", "외부 링크"}),
    ("w2.txt", "session-b/b2-normalization-wiki.md",
     {"인용 자료", "같이 보기", "외부 링크"}),
    ("w3.txt", "session-b/b3-dns-wiki.md",
     {"주요 DNS 목록", "같이 보기", "각주", "외부 링크"}),
    ("w4.txt", "session-a/a5-page-replacement-wiki.md",
     {"역사", "참조 및 수정된 페이지 감지", "사전 청소", "(h,k)-페이징 문제", "마킹 알고리즘", "보수적 알고리즘",
      "클럭의 변형", "LRU의 변형", "무작위 (Random)", "최장 거리 우선 (LDF) 페이지 교체 알고리즘",
      "구현 세부 사항", "같이 보기", "각주"}),
]

HEAD = re.compile(r"^(=+)\s*(.*?)\s*=+\s*$")

for src, dst, drop in DOCS:
    raw = open(f"{WIKI}/{src}", encoding="utf-8").read()
    meta, body = raw.split("\n\n", 1)
    title = re.search(r"TITLE: (.*)", meta).group(1)
    revid = re.search(r"REVID: (\d+)", meta).group(1)
    out = [f"# {title}", "",
           f"> 출처: 한국어 위키백과 「{title}」 (revision {revid}), CC BY-SA 4.0. "
           "강의 자료용으로 일부 절을 생략하고 제목 형식을 바꿨다. 자세한 내용은 SOURCES.md 참고.", ""]
    skipping, skip_level = False, 0
    for line in body.splitlines():
        m = HEAD.match(line)
        if m:
            level, name = len(m.group(1)), m.group(2)
            if skipping and level > skip_level:
                continue
            skipping = name in drop
            skip_level = level
            if not skipping:
                out += ["", "#" * level + " " + name, ""]
            continue
        if skipping or not line.strip():
            continue
        out.append(line.strip())
        out.append("")
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip() + "\n"
    # obvious typo in the source revision (stray "hh" before a definition)
    text = text.replace("hh관계 스키마", "관계 스키마")
    # math markup lost by the plain-text extract, and prose that points at a dropped code sample
    text = re.sub(r"(?m)^.*kk−h\+1-경쟁적이다\.\n\n", "", text)
    text = re.sub(r"(?s)다음 파이썬 코드는 에이징 알고리즘을 시뮬레이션한다\..*?이진수 표현으로 출력한다\.\n\n", "", text)
    # hash table formulas: the extract drops fraction bars and spacing, restore them as plain text
    for old, new in [("load factor (α)=nm,", "load factor (α) = n / m"),
                     ("h(x) = xmodm,", "h(x) = x mod m"),
                     ("h(x)=⌊m((xA)mod1)⌋", "h(x) = ⌊m((xA) mod 1)⌋"),
                     ("1+α2+Θ(1m)", "1 + α/2 + Θ(1/m)"),
                     ("e−α+α+Θ(1m)", "e^(−α) + α + Θ(1/m)")]:
        text = text.replace(old, new)
    # NRU class list: keep as a Markdown list instead of bare "3. ..." lines
    text = re.sub(r"(?m)^([0-3])\. (참조.*)$", r"- 클래스 \1: \2", text)
    with open(f"{OUT}/{dst}", "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(dst, revid, len(text))
