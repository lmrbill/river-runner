"""Builds the web app's Chinese font: Noto Sans SC cut down to the characters the app uses.

Compose for web draws its own text and has no system fallback, so every character on screen
must be in a bundled font. The full Noto Sans SC is about 10 MB; this keeps only the characters
found in the app's sources (plus ASCII and common punctuation), which is a few hundred KB.

Run it again after adding text with new characters:
    pip install fonttools
    npm pack @expo-google-fonts/noto-sans-sc && tar xzf expo-google-fonts-noto-sans-sc-*.tgz
    python tools/make_web_font.py package/400Regular/NotoSansSC_400Regular.ttf
then tools/make_web_symbols.py, which adds the symbols Noto Sans SC lacks (⚙ ✕ ⟲ ...).
Noto Sans SC is licensed under the SIL Open Font License 1.1 (licenses/NotoSansSC-OFL.txt).
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = ["ui/src", "app/src/main", "engine/src/commonMain", "web/src", "CHANGELOG.md"]
OUT = ROOT / "web/src/commonMain/composeResources/font/noto_sans_sc.ttf"
EXTRA = "，。！？：；、“”‘’（）《》【】…—·～￥％＋－×÷＝<>/|\\"


def used_characters() -> set[str]:
    chars = {chr(c) for c in range(0x20, 0x7F)} | set(EXTRA)
    for src in SOURCES:
        path = ROOT / src
        files = [path] if path.is_file() else [p for p in path.rglob("*") if p.suffix in {".kt", ".md", ".html"}]
        for f in files:
            chars |= {c for c in f.read_text(encoding="utf-8") if ord(c) >= 0x20}
    return chars


def main(full_font: str) -> None:
    chars = used_characters()
    cmap = TTFont(full_font).getBestCmap()
    missing = sorted(c for c in chars if ord(c) not in cmap and ord(c) > 0x7F)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    font = subset.load_font(full_font, options)
    sub = subset.Subsetter(options)
    sub.populate(text="".join(chars))
    sub.subset(font)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(OUT), options)
    print(f"{len(chars)} characters -> {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB)")
    if missing:
        # symbols the font cannot draw (e.g. emoji); the UI must draw these some other way
        print("not in Noto Sans SC:", " ".join(f"{c}(U+{ord(c):04X})" for c in missing))


if __name__ == "__main__":
    main(sys.argv[1])
