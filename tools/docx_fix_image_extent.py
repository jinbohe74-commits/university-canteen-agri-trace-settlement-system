# -*- coding: utf-8 -*-
"""按图片真实宽高比修正 docx 里图片的显示尺寸（cy）。

背景：替换图片时编辑通道保留了原图的 extent（cx 固定 5688000 EMU，cy 沿用旧图比例），
新图比例不同就会被纵向拉伸。这里按 media 里真实像素尺寸重算 cy，cx 不变，
wp:extent 与 a:ext 同步改，保证 Word 里不变形。
"""
import re, sys, zipfile, shutil, io, os
from PIL import Image

def fix(path):
    tmp = path + ".tmp"
    zin = zipfile.ZipFile(path)
    rels = zin.read("word/_rels/document.xml.rels").decode("utf-8")
    rid2target = dict(re.findall(r'Id="([^"]+)"[^>]*Target="([^"]+)"', rels))
    xml = zin.read("word/document.xml").decode("utf-8")

    changed = [0]
    def repl(m):
        block = m.group(0)
        mm = re.search(r'<wp:extent cx="(\d+)" cy="(\d+)"\s*/>', block)
        if not mm:
            return block
        cx, cy = int(mm.group(1)), int(mm.group(2))
        if cx < 1000000:
            return block
        emb = re.search(r'r:embed="([^"]+)"', block)
        if not emb:
            return block
        target = rid2target.get(emb.group(1))
        if not target:
            return block
        media = "word/" + target.replace("../", "")
        try:
            data = zin.read(media)
            w, h = Image.open(io.BytesIO(data)).size
        except Exception:
            return block
        new_cy = int(round(cx * h / w))
        if abs(new_cy - cy) < 500:
            return block
        changed[0] += 1
        block = block.replace(mm.group(0), '<wp:extent cx="%d" cy="%d"/>' % (cx, new_cy))
        block = re.sub(r'(<a:ext cx="%d" cy=")\d+(")' % cx, r'\g<1>%d\g<2>' % new_cy, block)
        return block

    new_xml = re.sub(r"<w:drawing>.*?</w:drawing>", repl, xml, flags=re.S)
    print(os.path.basename(path), "adjusted drawings:", changed[0])

    zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "word/document.xml":
            data = new_xml.encode("utf-8")
        zout.writestr(item, data)
    zout.close()
    zin.close()
    shutil.move(tmp, path)

for p in sys.argv[1:]:
    fix(p)
