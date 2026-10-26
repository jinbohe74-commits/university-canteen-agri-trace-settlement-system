from PIL import Image
import glob, os

files = sorted(glob.glob(r"C:\Users\何金波\Desktop\管理信息系统\docs\screenshots\*.png"))
for f in files:
    im = Image.open(f).convert("RGB")
    w, h = im.size
    small = im.resize((160, max(1, int(160 * h / w))))
    px = list(small.getdata())
    n = len(px)
    white = sum(1 for c in px if c[0] > 245 and c[1] > 245 and c[2] > 245)
    dark = sum(1 for c in px if sum(c) < 200)
    green = sum(1 for c in px if c[1] > c[0] + 12 and c[1] > c[2] + 6)
    uniq = len(set(px))
    print("%-34s %5dx%-5d 非白%4.1f%% 深色%4.1f%% 绿系%4.1f%% 色数%5d %s" % (
        os.path.basename(f), w, h, 100 * (n - white) / n, 100 * dark / n, 100 * green / n, uniq,
        "OK" if (n - white) / n > 0.08 and uniq > 60 else "!!疑似空白"))
