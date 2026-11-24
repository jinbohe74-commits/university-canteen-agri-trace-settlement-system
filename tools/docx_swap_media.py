# -*- coding: utf-8 -*-
"""把 docx 内指定的 word/media 图片替换成本地新图（尺寸必须一致，避免 extent 比例变化）。"""
import os, shutil, sys, zipfile
from PIL import Image
import io

DOCX = r"C:/Users/何金波/Desktop/大三上/管理信息系统/交付版/242806040121何金波——实验三_系统设计_实验报告.docx"
REPL = {
    "word/media/image1.png": r"C:/Users/何金波/Desktop/管理信息系统/tmp_media3/image1_fixed.png",
    "word/media/image2.png": r"C:/Users/何金波/Desktop/管理信息系统/tmp_media3/image2_fixed.png",
}

bak = DOCX.replace(".docx", "_media_bak.docx")
shutil.copy(DOCX, bak)

tmp = DOCX + ".tmp"
with zipfile.ZipFile(DOCX) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename in REPL:
            src = REPL[item.filename]
            old = Image.open(io.BytesIO(data)).size
            new = Image.open(src).size
            assert old == new, "%s size mismatch %s vs %s" % (item.filename, old, new)
            data = open(src, "rb").read()
            print("replace", item.filename, old, "->", len(data), "bytes")
        zout.writestr(item, data)

os.replace(tmp, DOCX)
print("done; backup:", bak)
