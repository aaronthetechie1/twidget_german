#!/usr/bin/env python3
"""Rebuild the bundled face without dropping glyphs or active axis ranges (requires fonttools)."""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

root = Path(__file__).resolve().parents[1]
source = TTFont(root / 'design-assets/fonts/google_sans_flex.ttf')
result = instantiateVariableFont(source, {'GRAD': 0, 'opsz': 18}, inplace=False)
assert result.getGlyphOrder() == source.getGlyphOrder()
original_axes = {axis.axisTag: (axis.minValue, axis.defaultValue, axis.maxValue) for axis in source['fvar'].axes}
assert {axis.axisTag: (axis.minValue, axis.defaultValue, axis.maxValue) for axis in result['fvar'].axes} == {
    tag: values for tag, values in original_axes.items() if tag not in {'GRAD', 'opsz'}
}
result.save(root / 'app/src/main/res/font/google_sans_flex.ttf')
