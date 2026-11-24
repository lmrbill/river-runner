"""Builds the web app's symbol fonts: the icons the app writes as text (⚙ ✕ ⟲ ⏩ 🏆 ...).

Noto Sans SC (tools/make_web_font.py) has no such symbols, and Compose for web has no system
fonts to fall back on, so each symbol the app uses that Noto Sans SC lacks is taken from the
first of these fonts that has it (all SIL Open Font License 1.1, see licenses/):
    npm pack @expo-google-fonts/noto-sans-symbols-2 @expo-google-fonts/noto-sans-math @expo-google-fonts/noto-emoji
    (unpack each) then
    python tools/make_web_symbols.py NotoSansSC.ttf NotoSansSymbols2.ttf NotoSansMath.ttf NotoEmoji.ttf
    python tools/make_web_symbols.py --target=noto_sans_sc_bold.ttf NotoSansSC.ttf ... (same fonts)
Run after make_web_font.py: the symbols are merged into its noto_sans_sc.ttf (adds about 15 KB),
because Compose for web 1.9 does not fall back from one bundled font to another.
"""
import pathlib
import sys

from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_web_font import used_characters  # noqa: E402

OUT_DIR = pathlib.Path(__file__).resolve().parent.parent / "web/src/commonMain/composeResources/font"
TARGET = OUT_DIR / "noto_sans_sc.ttf"


def main(*args: str) -> None:
    global TARGET
    if args and args[0].startswith("--target="):
        TARGET = OUT_DIR / args[0].split("=", 1)[1]
        args = args[1:]
    base_font, *symbol_fonts = args
    base = TTFont(base_font).getBestCmap()
    todo = sorted(c for c in used_characters() if ord(c) > 0x7F and ord(c) not in base)
    base = TTFont(TARGET)
    glyf, hmtx = base["glyf"], base["hmtx"]
    added = {}
    for path in symbol_fonts:
        src = TTFont(path)
        cmap = src.getBestCmap()
        mine = [c for c in todo if ord(c) in cmap]
        if not mine:
            continue
        glyph_set = src.getGlyphSet()
        scale = 1000 / src["head"].unitsPerEm
        for c in mine:
            # copy the outline (components decomposed), scaled to Noto Sans SC's 1000 units per em
            rec = DecomposingRecordingPen(glyph_set)
            glyph_set[cmap[ord(c)]].draw(rec)
            pen = TTGlyphPen(None)
            rec.replay(TransformPen(pen, (scale, 0, 0, scale, 0, 0)))
            name = f"sym{ord(c):04X}"
            glyf[name] = pen.glyph()
            glyf[name].recalcBounds(glyf)
            advance = round(src["hmtx"][cmap[ord(c)]][0] * scale)
            hmtx[name] = (advance, getattr(glyf[name], "xMin", 0))
            if "vmtx" in base:  # every glyph needs vertical metrics too, or browsers reject the font
                base["vmtx"][name] = (1000, max(0, 880 - getattr(glyf[name], "yMax", 0)))
            added[c] = name
        print(f"{''.join(mine)} from {pathlib.Path(path).name}")
        todo = [c for c in todo if c not in mine]
    if added:
        base.setGlyphOrder(glyf.glyphOrder)
        if not any(t.format == 12 for t in base["cmap"].tables) and any(ord(c) > 0xFFFF for c in added):
            from fontTools.ttLib.tables._c_m_a_p import cmap_format_12
            t12 = cmap_format_12(12)
            t12.platformID, t12.platEncID, t12.language, t12.cmap = 3, 10, 0, dict(base.getBestCmap())
            base["cmap"].tables.append(t12)
        for t in base["cmap"].tables:
            if not t.isUnicode():
                continue
            for c, name in added.items():
                if ord(c) <= 0xFFFF or t.format == 12:
                    t.cmap[ord(c)] = name
        base.save(TARGET)
        print(f"merged {len(added)} symbols into {TARGET.name} ({TARGET.stat().st_size // 1024} KB)")
    if todo:
        print("still missing:", " ".join(f"{c}(U+{ord(c):04X})" for c in todo))


if __name__ == "__main__":
    main(*sys.argv[1:])
