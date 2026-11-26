"""Build the PDF documents of the RAG evaluation corpus from their text sources.

Sources live in ``pdf-src/`` next to this script. Format of a source file:

* line 1: document title, line 2: subtitle
* blank-line separated paragraphs
* a heading is a short line starting with ``제n장`` or a section number (``1.2``)
* a line ``<<<PAGE>>>`` forces a page break; every other break is an error,
  so the page layout (and therefore gold page numbers) stays fixed

Usage (needs reportlab; Malgun Gothic ships with Windows):

    python make_corpus_pdfs.py [--font C:/Windows/Fonts/malgun.ttf]

PDFs are written with ``invariant=1`` so re-running gives byte-identical files.
"""
import argparse
import re
from pathlib import Path

from reportlab import rl_config
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

rl_config.invariant = 1  # fixed timestamps/IDs -> reproducible output

TOOLS = Path(__file__).resolve().parent
CORPUS = TOOLS.parent / "resources" / "rag-eval" / "corpus"

# source file -> (output pdf relative to corpus/, footer label)
DOCS = {
    "a4-os-process-sync-notes.txt": ("session-a/a4-os-process-sync-notes.pdf", "운영체제 강의 노트"),
    "b4-transaction-concurrency-notes.txt": ("session-b/b4-transaction-concurrency-notes.pdf", "데이터베이스 강의 노트"),
}

PAGE_MARK = "<<<PAGE>>>"
HEADING = re.compile(r"^(제\d+장|\d+(\.\d+)*\.?)\s+\S")


def is_heading(line: str) -> bool:
    return bool(HEADING.match(line)) and len(line) <= 40 and not line.rstrip().endswith(("다.", ".", "?", "!"))


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def parse_pages(src: Path):
    """Return (title, subtitle, pages) where pages is a list of block lists."""
    lines = src.read_text(encoding="utf-8").splitlines()
    title, subtitle = lines[0].strip(), lines[1].strip()
    pages, blocks, para = [], [], []

    def flush_para():
        if para:
            blocks.append(("p", " ".join(para)))
            para.clear()

    for raw in lines[2:]:
        line = raw.strip()
        if line == PAGE_MARK:
            flush_para()
            pages.append(blocks)
            blocks = []
        elif not line:
            flush_para()
        elif is_heading(line) and not para:
            blocks.append(("h2" if line.startswith("제") else "h3", line))
        else:
            para.append(line)
    flush_para()
    pages.append(blocks)
    return title, subtitle, pages


def build(src: Path, out: Path, footer: str, font: str, bold: str) -> int:
    title, subtitle, pages = parse_pages(src)
    styles = {
        "title": ParagraphStyle("title", fontName=bold, fontSize=16, leading=22, spaceAfter=4),
        "sub": ParagraphStyle("sub", fontName=font, fontSize=9.5, leading=14, spaceAfter=10),
        "h2": ParagraphStyle("h2", fontName=bold, fontSize=13, leading=19, spaceBefore=8, spaceAfter=4),
        "h3": ParagraphStyle("h3", fontName=bold, fontSize=11, leading=16, spaceBefore=6, spaceAfter=3),
        "p": ParagraphStyle("p", fontName=font, fontSize=10, leading=16, spaceAfter=6),
        "foot": ParagraphStyle("foot", fontName=font, fontSize=8, alignment=TA_CENTER),
    }

    def on_page(canvas, doc):
        canvas.saveState()
        canvas.setFont(font, 8)
        canvas.drawCentredString(A4[0] / 2, 12 * mm, f"{footer} · {doc.page}쪽")
        canvas.restoreState()

    story = [Paragraph(esc(title), styles["title"]), Paragraph(esc(subtitle), styles["sub"])]
    for i, blocks in enumerate(pages):
        if i:
            story.append(PageBreak())
        for kind, text in blocks:
            story.append(Paragraph(esc(text), styles[kind]))
    story.append(Spacer(1, 1))

    out.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(str(out), pagesize=A4, leftMargin=22 * mm, rightMargin=22 * mm,
                            topMargin=20 * mm, bottomMargin=22 * mm, title=title, author="tutipal team",
                            subject="RAG evaluation corpus (team-written)")
    doc.build(story, onFirstPage=on_page, onLaterPages=on_page)
    if doc.page != len(pages):
        raise SystemExit(f"{src.name}: expected {len(pages)} pages but layout produced {doc.page}; "
                         "a page overflowed - move a <<<PAGE>>> marker")
    return doc.page


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default="C:/Windows/Fonts/malgun.ttf")
    ap.add_argument("--bold-font", default="C:/Windows/Fonts/malgunbd.ttf")
    args = ap.parse_args()
    pdfmetrics.registerFont(TTFont("KR", args.font))
    pdfmetrics.registerFont(TTFont("KR-Bold", args.bold_font))
    for name, (rel, footer) in DOCS.items():
        pages = build(TOOLS / "pdf-src" / name, CORPUS / rel, footer, "KR", "KR-Bold")
        print(f"{rel}: {pages} pages")


if __name__ == "__main__":
    main()
