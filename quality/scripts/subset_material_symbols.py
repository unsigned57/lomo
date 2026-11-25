#!/usr/bin/env python3
"""Regenerate the bundled icon font from the original Material Symbols Rounded TTF.

Requires fontTools and uharfbuzz for asset maintenance. The original bundled font
is available in the matching resource path at Lomo commit 7aacbd64.
"""

import argparse
import re
from io import BytesIO
from pathlib import Path

import uharfbuzz as hb
from fontTools import subset
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

MODULE = Path(__file__).resolve().parents[2] / "apps/android/ui-components"
CATALOGUE = MODULE / "src/icon/LomoIcons.kt"
RESOURCE = MODULE / "composeResources/font/material_symbols_rounded.ttf"


def shape(font, name):
    buffer = hb.Buffer()
    buffer.add_str(name)
    buffer.guess_segment_properties()
    hb.shape(font, buffer, {"liga": True})
    glyphs = buffer.glyph_infos
    if len(glyphs) != 1 or glyphs[0].codepoint == 0:
        raise ValueError(f"Expected one glyph for {name!r}")
    return glyphs[0].codepoint, buffer.glyph_positions[0].x_advance


def sample_variations(axes):
    defaults = {axis.axisTag: axis.defaultValue for axis in axes}
    for weight in (100, 300, 350, 400, 500, 600, 700):
        for fill in (0, 1):
            for optical_size in (20, 24, 48):
                yield defaults | {"wght": weight, "FILL": fill, "opsz": optical_size}


def draw(font, glyph):
    pen = RecordingPen()
    font.draw_glyph_with_pen(glyph, pen)
    return pen.value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original Material Symbols Rounded TTF")
    parser.add_argument("--output", type=Path, default=RESOURCE)
    args = parser.parse_args()

    names = sorted(set(re.findall(r'glyphName = "([a-z0-9_]+)"', CATALOGUE.read_text())))
    if not names:
        raise ValueError("The icon catalogue must contain glyph names")
    source = hb.Font(hb.Face(args.source.read_bytes()))
    font = TTFont(args.source, recalcTimestamp=False)
    variations = list(sample_variations(font["fvar"].axes))
    kept = set()
    for axes in variations:
        source.set_variations(axes)
        kept.update(shape(source, name)[0] for name in names)

    codepoints = {ord(char) for name in names for char in name}
    codepoints.update(cp for cp, glyph in font.getBestCmap().items() if font.getGlyphID(glyph) in kept)
    options = subset.Options(layout_closure=False, name_IDs=["*"], name_languages=["*"])
    subsetter = subset.Subsetter(options)
    subsetter.populate(gids=kept, unicodes=codepoints)
    subsetter.subset(font)
    font["name"].setName("Subset for Lomo's icon catalogue.", 10, 3, 1, 0x409)

    output = BytesIO()
    font.save(output)
    reduced = hb.Font(hb.Face(output.getvalue()))
    for axes in variations:
        source.set_variations(axes)
        reduced.set_variations(axes)
        for name in names:
            original_glyph, original_advance = shape(source, name)
            glyph, advance = shape(reduced, name)
            if advance != original_advance or draw(reduced, glyph) != draw(source, original_glyph):
                raise ValueError(f"Glyph changed for {name!r} at {axes}")

    args.output.write_bytes(output.getvalue())
    print(f"{len(names)} ligatures, {len(output.getvalue())} bytes")


if __name__ == "__main__":
    main()
