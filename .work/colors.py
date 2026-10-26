from PIL import Image
import os

base = r"C:\Users\何金波\Desktop\管理信息系统\.work\prototype\unz\word\media"

def hexc(c):
    return "#%02x%02x%02x" % c[:3]

def scan_row(img, y, step=1):
    w = img.width
    px = img.load()
    out = []
    prev = None
    run = 0
    for x in range(0, w, step):
        c = px[x, y]
        c3 = (c[0] // 8, c[1] // 8, c[2] // 8)
        if c3 != prev:
            if prev is not None and run >= 8:
                out.append((prev[0] * 8, x - run, x))
            prev = c3
            run = 0
        run += step
    if run >= 8:
        out.append((prev[0] * 8, w - run, w))
    return out

for n in [1, 2, 3, 13, 16, 21]:
    p = os.path.join(base, "image%d.png" % n)
    img = Image.open(p).convert("RGB")
    w, h = img.size
    px = img.load()
    print("=== image%d %dx%d" % (n, w, h))
    y = int(h * 0.55)
    # boundaries on a row
    segs = scan_row(img, y, 2)
    print("  row y=%d boundaries:" % y)
    for c3, x0, x1 in segs:
        print("    x %5d-%5d  ~#%02x%02x%02x" % (x0, x1, c3, c3, c3))
    # samples
    pts = {
        "topleft(5,5)": (5, 5),
        "sidebar_top(30,120)": (30, 120),
        "sidebar_mid(30,%d)" % (h // 2): (30, h // 2),
        "sidebar_bot(30,%d)" % (h - 200): (30, h - 200),
        "header(1500,60)": (1500, 60),
        "pagebg(2600,%d)" % (h - 120): (2600, h - 120),
    }
    for k, (x, yy) in pts.items():
        if x < w and yy < h:
            print("    %-24s %s" % (k, hexc(px[x, yy])))

