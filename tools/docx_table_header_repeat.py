# -*- coding: utf-8 -*-
"""给所有表格的表头行加 <w:tblHeader/>（跨页重复表头）；指定表格的表头行加 keepNext。"""
import os, re, shutil, sys, zipfile

DOCX = sys.argv[1] if len(sys.argv) > 1 else r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"

with zipfile.ZipFile(DOCX) as z:
    xml = z.read("word/document.xml").decode("utf-8")


def add_tblheader(m):
    tbl = m.group(0)
    m_tr = re.search(r"<w:tr\b.*?</w:tr>", tbl, flags=re.S)
    if not m_tr:
        return tbl
    tr = m_tr.group(0)
    if "w:tblHeader" in tr:
        return tbl
    if "<w:trPr>" in tr:
        new_tr = tr.replace("<w:trPr>", "<w:trPr><w:tblHeader/>", 1)
    elif "<w:trPr/>" in tr:
        new_tr = tr.replace("<w:trPr/>", "<w:trPr><w:tblHeader/></w:trPr>", 1)
    else:
        head = re.match(r"<w:tr\b[^>]*>", tr).group(0)
        new_tr = head + "<w:trPr><w:tblHeader/></w:trPr>" + tr[len(head):]
    return tbl.replace(tr, new_tr, 1)


xml, n = re.subn(r"<w:tbl>.*?</w:tbl>", add_tblheader, xml, flags=re.S)
print("tables:", n, "tblHeader:", xml.count("<w:tblHeader/>"))

# 表6 表头行：每个单元格段落加 keepNext（让表头跟着第一行数据走）
tbls = re.findall(r"<w:tbl>.*?</w:tbl>", xml, flags=re.S)
t6 = [t for t in tbls if "基础支撑子系统" in t]
if t6:
    first_tr = re.search(r"<w:tr\b.*?</w:tr>", t6[0], flags=re.S).group(0)
    if "<w:keepNext/>" not in first_tr:
        segs = re.split(r"(<w:p\b[^>]*>)", first_tr)
        out = []
        for seg in segs:
            if re.match(r"<w:p\b", seg):
                out.append(seg)
            elif "<w:pPr>" in seg:
                out.append(seg.replace("<w:pPr>", "<w:pPr><w:keepNext/>", 1))
            elif seg.strip():
                out.append(seg)
            else:
                out.append(seg)
        new_tr = "".join(out)
        if new_tr != first_tr:
            xml = xml.replace(first_tr, new_tr, 1)
        print("keepNext count in header row:", new_tr.count("<w:keepNext/>"))

bak = DOCX.replace(".docx", "_prehdr.docx")
shutil.copy(DOCX, bak)
tmp = DOCX + ".tmp"
with zipfile.ZipFile(DOCX) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        data = zin.read(it.filename)
        if it.filename == "word/document.xml":
            data = xml.encode("utf-8")
        zout.writestr(it, data)
os.replace(tmp, DOCX)
print("saved. backup:", bak)
