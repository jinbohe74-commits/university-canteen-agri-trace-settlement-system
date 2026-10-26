import json, os, glob

src = r"C:\Users\何金波\Desktop\管理信息系统\.work\ocr"
out = r"C:\Users\何金波\Desktop\管理信息系统\.work\ocr\layout"
os.makedirs(out, exist_ok=True)

for f in sorted(glob.glob(os.path.join(src, "image*.json")), key=lambda p: int(os.path.basename(p)[5:-5])):
    data = json.load(open(f, encoding="utf-8-sig"))
    lines = sorted(data["lines"], key=lambda l: (l["y"], l["x"]))
    rows = []
    for l in lines:
        placed = False
        for r in rows:
            if abs(r["y"] - l["y"]) <= max(12, min(l["h"], r["itemh"]) * 0.7):
                r["items"].append(l)
                r["y"] = min(r["y"], l["y"])
                r["itemh"] = max(r["itemh"], l["h"])
                placed = True
                break
        if not placed:
            rows.append({"y": l["y"], "itemh": l["h"], "items": [l]})
    rows.sort(key=lambda r: r["y"])
    buf = []
    buf.append("# %s  (%dx%d)" % (data["image"], data["width"], data["height"]))
    for r in rows:
        items = sorted(r["items"], key=lambda i: i["x"])
        parts = ["%d:%s" % (it["x"], it["text"]) for it in items]
        buf.append("y=%-5d | %s" % (r["y"], "  ||  ".join(parts)))
    name = data["image"].replace(".png", ".txt")
    open(os.path.join(out, name), "w", encoding="utf-8").write("\n".join(buf))
    print(name, len(rows))
