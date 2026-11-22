"""Builds the web app's symbol fonts: the icons the app writes as text (⚙ ✕ ⟲ ⏩ 🏆 ...).

Noto Sans SC (tools/make_web_font.py) has no such symbols, and Compose for web has no system
fonts to fall back on, so each symbol the app uses that Noto Sans SC lacks is taken from the
first of these fonts that has it (all SIL Open Font License 1.1, see licenses/):
    npm pack @expo-google-fonts/noto-sans-symbols-2 @expo-google-fonts/noto-sans-math @expo-google-fonts/noto-emoji
    (unpack each) then
    python tools/make_web_symbols.py NotoSansSC.ttf NotoSansSymbols2.ttf NotoSansMath.ttf NotoEmoji.ttf
Writes web/src/commonMain/composeResources/font/symbols_1.ttf, symbols_2.ttf, ... (a few KB each).
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_web_font import used_characters  # noqa: E402

OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "web/src/commonMain/composeResources/font"


def main(base_font: str, *symbol_fonts: str) -> None:
    base = TTFont(base_font).getBestCmap()
    todo = sorted(c for c in used_characters() if ord(c) > 0x7F and ord(c) not in base)
    for old in OUT_DIR.glob("symbols_*.ttf"):
        old.unlink()
    n = 0
    for path in symbol_fonts:
        cmap = TTFont(path).getBestCmap()
        mine = [c for c in todo if ord(c) in cmap]
        if not mine:
            continue
        n += 1
        options = subset.Options()
        options.layout_features = ["*"]
        options.name_IDs = ["*"]
        font = TTFont(path)
        sub = subset.Subsetter(options)
        sub.populate(unicodes=[ord(c) for c in mine])
        sub.subset(font)
        out = OUT_DIR / f"symbols_{n}.ttf"
        font.save(out)
        print(f"{out.name}: {''.join(mine)} ({out.stat().st_size // 1024} KB) from {pathlib.Path(path).name}")
        todo = [c for c in todo if c not in mine]
    if todo:
        print("still missing:", " ".join(f"{c}(U+{ord(c):04X})" for c in todo))


if __name__ == "__main__":
    main(*sys.argv[1:])
