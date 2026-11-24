# -*- coding: utf-8 -*-
"""给「系统共设计 33 张数据表」段落加 pageBreakBefore，让表10 整体在新页开始。"""
import os, re, shutil, sys, time, zipfile

DOCX = r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"

with zipfile.ZipFile(DOCX) as z:
    xml = z.read("word/document.xml").decode("utf-8")

m = re.search(r"<w:p\b[^>]*>(?:(?!</w:p>).)*?系统共设计(?:(?!</w:p>).)*?</w:p>", xml, flags=re.S)
assert m, "paragraph not found"
para = m.group(0)
pass
if "<w:pPr>" in para:
    newp = para.replace("<w:pPr>", "<w:pPr><w:pageBreakBefore/>", 1)
else:
    head = re.match(r"<w:p\b[^>]*>", para).group(0)
    newp = head + "<w:pPr><w:pageBreakBefore/></w:pPr>" + para[len(head):]
xml = xml.replace(para, newp, 1)

bak = DOCX.replace(".docx", "_prepb.docx")
shutil.copy(DOCX, bak)
tmp = DOCX + ".tmp.bak"
with zipfile.ZipFile(DOCX) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        data = zin.read(it.filename)
        if it.filename == "word/document.xml":
            data = xml.encode("utf-8")
        zout.writestr(it, data)

for i in range(40):
    try:
        os.replace(tmp, DOCX)
        print("saved at try", i)
        break
    except PermissionError:
        time.sleep(1.5)
else:
    print("FAILED: file locked, tmp at", tmp)
