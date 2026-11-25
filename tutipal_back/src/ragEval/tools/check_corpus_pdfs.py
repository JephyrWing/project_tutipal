"""Check that the corpus PDFs extract back to their source text, page by page.

For every page, the whitespace-normalized source text of that page must be
contained in the whitespace-normalized text pypdf extracts from it.

    python check_corpus_pdfs.py
"""
import re
import sys

from pypdf import PdfReader

from make_corpus_pdfs import CORPUS, DOCS, PAGE_MARK, TOOLS, is_heading


def norm(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def main() -> int:
    failed = 0
    for name, (rel, _footer) in DOCS.items():
        lines = (TOOLS / "pdf-src" / name).read_text(encoding="utf-8").splitlines()
        src_pages = "\n".join(lines[2:]).split(PAGE_MARK)
        reader = PdfReader(CORPUS / rel)
        print(f"{rel}: {len(reader.pages)} pages (source {len(src_pages)})")
        if len(reader.pages) != len(src_pages):
            failed += 1
        for i, (page, src) in enumerate(zip(reader.pages, src_pages), start=1):
            got = norm(page.extract_text() or "")
            want = norm(src)
            ok = want in got
            failed += not ok
            headings = [ln.strip() for ln in src.splitlines() if is_heading(ln.strip())]
            print(f"  p{i}: extracted {len(got)} chars, source {len(want)} chars, "
                  f"{'OK' if ok else 'MISMATCH'} | headings: {', '.join(headings) or '(none)'}")
            if not ok:
                for sent in re.split(r"(?<=다\.)\s", want):
                    if norm(sent) not in got:
                        print(f"    missing: {sent[:80]}")
                        break
    print("FAILED" if failed else "ALL PAGES OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
