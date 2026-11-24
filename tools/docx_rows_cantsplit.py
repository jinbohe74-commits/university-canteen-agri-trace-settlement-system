# -*- coding: utf-8 -*-
"""给 docx 中所有表格行加 <w:cantSplit/>，避免表格行被分页切断。"""
import os, re, shutil, sys, zipfile

DOCX = sys.argv[1] if len(sys.argv) > 1 else r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"

with zipfile.ZipFile(DOCX) as z:
    xml = z.read("word/document.xml").decode("utf-8")

rows_before = xml.count("<w:tr ")
rows_before2 = xml.count("<w:tr>")
print("rows:", rows_before, rows_before2)


def fix_tr(m):
    s = m.group(0)
    if "w:cantSplit" in s:
        return s
    h = re.search(r"<w:tr\b[^>]*>", s)
    head = h.group(0)
    rest = s[len(head):]
    if "<w:trPr>" in rest:
        rest = rest.replace("<w:trPr>", "<w:trPr><w:cantSplit/>", 1)
    elif "<w:trPr/>" in rest:
        rest = rest.replace("<w:trPr/>", "<w:trPr><w:cantSplit/></w:trPr>", 1)
    else:
        rest = "<w:trPr><w:cantSplit/></w:trPr>" + rest
    return head + rest


new_xml, n = re.subn(r"<w:tr\b.*?</w:tr>", fix_tr, xml, flags=re.S)
added = new_xml.count("<w:cantSplit/>")
print("rows processed:", n, " cantSplit now:", added)
assert n > 0

bak = DOCX.replace(".docx", "_presplit.docx")
shutil.copy(DOCX, bak)
tmp = DOCX + ".tmp"
with zipfile.ZipFile(DOCX) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = new_xml.encode("utf-8")
        zout.writestr(item, data)
os.replace(tmp, DOCX)
print("saved. backup:", bak)
