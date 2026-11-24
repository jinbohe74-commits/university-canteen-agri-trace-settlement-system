# -*- coding: utf-8 -*-
"""为实验三生成分页核验用 PDF（可选删除指定段落的 pageBreakBefore 作为诊断）。"""
import os, sys, win32com.client

SRC = r"C:\Users\何金波\Desktop\大三上\管理信息系统\交付版\242806040121何金波——实验三_系统设计_实验报告.docx"

# 诊断：删掉该段的“段前分页”格式（不改其它内容），看分页是否真的取决于它
DROP_PB = "--drop-pb" in sys.argv

word = win32com.client.DispatchEx("Word.Application")
word.Visible = False
word.DisplayAlerts = 0
try:
    doc = word.Documents.Open(SRC, False, True)
    if DROP_PB:
        for p in doc.Paragraphs:
            t = p.Range.Text
            if "系统共设计" in t and "张数据表" in t:
                p.Format.PageBreakBefore = False
                print("cleared PageBreakBefore at para text:", t[:30])
                break
        out = r"C:\Users\何金波\Desktop\管理信息系统\docs\report_2026-11-11\t3_nopb.pdf"
        doc.SaveAs2(out, 17)
    else:
        out = r"C:\Users\何金波\Desktop\管理信息系统\docs\report_2026-11-11\t3.pdf"
        doc.SaveAs2(out, 17)
    print("pages:", doc.ComputeStatistics(2))
    doc.Close(0)
finally:
    word.Quit()
