# -*- coding: utf-8 -*-
"""把当前系统 6 个界面拼成实验三「主要界面高保真原型」用的大图。"""
from PIL import Image, ImageDraw, ImageFont
import os

SH = r"C:/Users/何金波/Desktop/管理信息系统/docs/report_2026-11-11/shots"
OUT = r"C:/Users/何金波/Desktop/管理信息系统/docs/report_2026-11-11/shots/00-主要界面拼图.png"

panels = [
    ("01-登录页", "① 登录页"),
    ("02-管理员数据看板", "② 管理员数据看板"),
    ("11-采购需求与比价", "③ 采购需求与比价"),
    ("06-质检录入", "④ 质检录入（按标准判定）"),
    ("15-配送任务与实时轨迹", "⑤ 配送任务与实时轨迹"),
    ("09-批次溯源", "⑥ 批次溯源查询"),
]

CELL_W, CELL_H = 1200, 760          # 图片展示区
PAD, GAP, CAP_H = 22, 40, 68
COLS, ROWS = 3, 2
CARD_W = CELL_W + PAD * 2
CARD_H = CELL_H + PAD * 2 + CAP_H
W = COLS * CARD_W + (COLS + 1) * GAP
H = ROWS * CARD_H + (ROWS + 1) * GAP

font_paths = [r"C:/Windows/Fonts/msyh.ttc", r"C:/Windows/Fonts/simhei.ttf"]
font = None
for fp in font_paths:
    if os.path.exists(fp):
        font = ImageFont.truetype(fp, 34)
        break

canvas = Image.new("RGB", (W, H), (238, 241, 240))
draw = ImageDraw.Draw(canvas)

for i, (fn, cap) in enumerate(panels):
    r, c = divmod(i, COLS)
    x = GAP + c * (CARD_W + GAP)
    y = GAP + r * (CARD_H + GAP)
    # 卡片 + 细边框 + 轻投影
    draw.rounded_rectangle([x + 3, y + 4, x + CARD_W + 3, y + CARD_H + 4], 18, fill=(226, 230, 229))
    draw.rounded_rectangle([x, y, x + CARD_W, y + CARD_H], 18, fill=(255, 255, 255), outline=(216, 221, 219), width=2)

    im = Image.open(os.path.join(SH, fn + ".png")).convert("RGB")
    # 等比缩放到能覆盖展示区，再从顶部裁切（截图顶部信息最关键）
    sw, sh = im.size
    scale = max(CELL_W / sw, CELL_H / sh)
    im = im.resize((int(sw * scale + 0.5), int(sh * scale + 0.5)), Image.LANCZOS)
    sw, sh = im.size
    left = max(0, (sw - CELL_W) // 2)
    im = im.crop((left, 0, left + CELL_W, CELL_H))
    canvas.paste(im, (x + PAD, y + PAD))

    if font:
        tw = draw.textlength(cap, font=font)
        draw.text((x + (CARD_W - tw) / 2, y + PAD + CELL_H + 14), cap, font=font, fill=(70, 78, 76))

canvas.save(OUT, quality=95)
print("saved", OUT, canvas.size)
