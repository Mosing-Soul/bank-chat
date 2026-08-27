import json
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(r"D:\JetBrains\project\bank-chat")
SOURCE = ROOT / "bank-agent-lab/document_generation/rag_excel_sample_work/sop_documents.json"
ASSETS = ROOT / "bank-agent-demo/assets"

pdfmetrics.registerFont(TTFont("CN", r"C:\Windows\Fonts\msyh.ttc"))
pdfmetrics.registerFont(TTFont("CNB", r"C:\Windows\Fonts\msyhbd.ttc"))
base = getSampleStyleSheet()
styles = {
    "title": ParagraphStyle("title", parent=base["Title"], fontName="CNB", fontSize=20, leading=28,
                            alignment=TA_CENTER, textColor=colors.HexColor("#17365D"), spaceAfter=12),
    "subtitle": ParagraphStyle("subtitle", fontName="CN", fontSize=11, leading=18, alignment=TA_CENTER,
                               textColor=colors.HexColor("#526777"), spaceAfter=14),
    "h1": ParagraphStyle("h1", fontName="CNB", fontSize=13, leading=20,
                         textColor=colors.HexColor("#17365D"), spaceBefore=8, spaceAfter=7),
    "body": ParagraphStyle("body", fontName="CN", fontSize=9.5, leading=16,
                           textColor=colors.HexColor("#263238"), spaceAfter=5),
    "small": ParagraphStyle("small", fontName="CN", fontSize=8, leading=12,
                            textColor=colors.HexColor("#455A64")),
}


def p(value, style="small"):
    return Paragraph(str(value).replace("&", "&amp;"), styles[style])


def add_table(story, table):
    rows = [[p(x) for x in table["headers"]]]
    rows.extend([[p(x) for x in row] for row in table["rows"]])
    width = 174 * mm
    col_widths = [width / len(rows[0])] * len(rows[0])
    grid = Table(rows, colWidths=col_widths, repeatRows=1)
    grid.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#17365D")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, -1), "CN"),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#B8C4CE")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#EEF5FB")]),
        ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.append(grid)


def build(item):
    target = ASSETS / item["filename"]
    doc = SimpleDocTemplate(str(target), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
                            topMargin=16 * mm, bottomMargin=16 * mm, title=item["title"], author="华辰银行")
    meta = [[p(h) for h in item["metaHeaders"]],
            [p(item["code"]), p(item["version"]), p(item["effectiveDate"]), p(item["owner"])]]
    meta_table = Table(meta, colWidths=[43.5 * mm] * 4)
    meta_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#17365D")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), .4, colors.HexColor("#B8C4CE")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    story = [p("华辰银行", "title"), p(item["title"], "title"), p(item["subtitle"], "subtitle"),
             meta_table, Spacer(1, 10), p(item["scopeLabel"] + item["scope"], "body"),
             p(item["warning"], "body"), PageBreak()]
    for index, section in enumerate(item["sections"]):
        story.append(p(section["heading"], "h1"))
        for text in section.get("paragraphs", []):
            story.append(p(text, "body"))
        for text in section.get("bullets", []):
            story.append(p("• " + text, "body"))
        if section.get("table"):
            add_table(story, section["table"])
        if index < len(item["sections"]) - 1:
            story.append(Spacer(1, 8))
    doc.build(story)
    print(target)


for document in json.loads(SOURCE.read_text(encoding="utf-8"))["documents"]:
    build(document)
