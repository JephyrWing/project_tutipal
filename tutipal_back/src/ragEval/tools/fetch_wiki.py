"""Fetch the Korean Wikipedia plain-text extracts used by the RAG evaluation corpus.

    python fetch_wiki.py <out-dir>

Writes <out-dir>/w0.txt ... (header: TITLE / REVID / TS, then the extract) for
adapt_wiki.py. The TextExtracts API only serves the *current* revision, so a
later run may differ from the revisions recorded in corpus/SOURCES.md; the
committed Markdown files are the frozen copy. Raw extracts are not committed.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

# index matters: adapt_wiki.py refers to w<index>.txt
TITLES = ["교착 상태", "해시 테이블", "데이터베이스 정규화", "도메인 네임 시스템", "페이지 교체 알고리즘"]


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    for i, title in enumerate(TITLES):
        path = f"{out}/w{i}.txt"
        if os.path.exists(path):
            continue
        time.sleep(5)  # the API answers 429 to bursts
        q = urllib.parse.urlencode({"action": "query", "prop": "extracts|revisions", "rvprop": "ids|timestamp",
                                    "explaintext": "1", "format": "json", "redirects": "1", "titles": title})
        req = urllib.request.Request("https://ko.wikipedia.org/w/api.php?" + q,
                                     headers={"User-Agent": "rag-eval-corpus-builder/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.load(resp)
        for page in data["query"]["pages"].values():
            rev = page.get("revisions", [{}])[0]
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"TITLE: {page.get('title')}\nREVID: {rev.get('revid')}\nTS: {rev.get('timestamp')}\n\n"
                        f"{page.get('extract', '')}")
            print(path, page.get("title"), rev.get("revid"))


if __name__ == "__main__":
    main(sys.argv[1])
