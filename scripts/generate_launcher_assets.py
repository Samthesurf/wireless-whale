#!/usr/bin/env python3
"""Derive Android launcher assets from the genuine generated logo.

Run: uv run --with pillow python scripts/generate_launcher_assets.py
Original artwork and provenance remain under design/logo/. No drawing or
image-generation substitute is performed here, only raster asset preparation.
"""
from pathlib import Path
import hashlib
import json
import math

from PIL import Image, ImageDraw, features

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SOURCE = ROOT / 'design/logo/wireless-whale-mark-black-transparent-1024.png'


def ink_radius(image: Image.Image) -> float:
    """Farthest visible ink pixel from the image's geometric center."""
    cx, cy = (image.width - 1) / 2, (image.height - 1) / 2
    alpha = image.getchannel('A')
    pixels = alpha.tobytes()
    radius_squared = max(
        ((i % image.width) - cx) ** 2 + ((i // image.width) - cy) ** 2
        for i, value in enumerate(pixels) if value > 8
    )
    return math.sqrt(radius_squared)


def centered_mark(art: Image.Image, size: int, radius: float) -> Image.Image:
    scale = (radius - 2) / ink_radius(art)
    resized = art.resize(
        (max(1, round(art.width * scale)), max(1, round(art.height * scale))),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((size - resized.width) // 2, (size - resized.height) // 2))
    assert ink_radius(canvas) <= radius, 'Ink exceeds launcher safe circle'
    return canvas


def main() -> None:
    assert features.check('webp'), 'Pillow needs WebP support for existing launcher resource paths'
    original = Image.open(SOURCE).convert('RGBA')
    assert original.getpixel((0, 0))[3] == 0, 'Expected the transparent generated derivative'
    bounds = original.getchannel('A').getbbox()
    assert bounds is not None
    art = original.crop(bounds)
    output = []

    drawable = RES / 'drawable-nodpi'
    drawable.mkdir(parents=True, exist_ok=True)
    icon_art = Image.new('RGBA', art.size, 'white')
    icon_art.putalpha(art.getchannel('A'))
    foreground = centered_mark(icon_art, 432, 432 * 33 / 108)
    foreground_path = drawable / 'wireless_whale_launcher_foreground.png'
    foreground.save(foreground_path, optimize=True)
    output.append(foreground_path)

    # Keep the cropped mark's aspect ratio for the in-app 56dp x 36dp brand slot.
    header = art.copy()
    header.thumbnail((256, 176), Image.Resampling.LANCZOS)
    header_path = drawable / 'wireless_whale_mark.png'
    header.save(header_path, optimize=True)
    output.append(header_path)

    sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    previews = []
    for density, size in sizes.items():
        folder = RES / ('mipmap-' + density)
        folder.mkdir(parents=True, exist_ok=True)
        mark = centered_mark(icon_art, size * 4, size * 4 * 0.38)
        square = Image.new('RGBA', mark.size, 'black')
        square.alpha_composite(mark)
        square = square.resize((size, size), Image.Resampling.LANCZOS)
        circle = square.copy()
        mask = Image.new('L', (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        circle.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        for filename, image in [('ic_launcher.webp', square), ('ic_launcher_round.webp', circle)]:
            destination = folder / filename
            image.save(destination, 'WEBP', lossless=True, method=6)
            output.append(destination)
        if density in {'mdpi', 'xxxhdpi'}:
            previews.append(square.resize((192, 192), Image.Resampling.NEAREST))

    # Preview the actual adaptive layer under a conservative circular mask.
    adaptive_preview = Image.new('RGBA', foreground.size, 'black')
    adaptive_preview.alpha_composite(foreground)
    # Android displays the central 72dp of each 108dp adaptive layer.
    inset = round(432 * 18 / 108)
    adaptive_preview = adaptive_preview.crop((inset, inset, 432 - inset, 432 - inset))
    adaptive_preview = adaptive_preview.resize((192, 192), Image.Resampling.LANCZOS)
    mask = Image.new('L', (768, 768), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 767, 767), fill=255)
    adaptive_preview.putalpha(mask.resize((192, 192), Image.Resampling.LANCZOS))
    previews.append(adaptive_preview)
    sheet = Image.new('RGB', (len(previews) * 224, 224), '#ececec')
    for index, preview in enumerate(previews):
        sheet.paste(preview, (index * 224 + 16, 16), preview.getchannel('A'))
    preview_path = ROOT / 'design/logo/android-icon-preview.png'
    sheet.save(preview_path, optimize=True)

    print(json.dumps({
        'source': str(SOURCE.relative_to(ROOT)),
        'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'adaptive_foreground_size': foreground.size,
        'adaptive_ink_radius_pixels': ink_radius(foreground),
        'safe_radius_pixels': 432 * 33 / 108,
        'header_bitmap_size': header.size,
        'resource_count': len(output),
        'resources': [str(path.relative_to(ROOT)) for path in output],
        'preview': str(preview_path),
    }, indent=2))


if __name__ == '__main__':
    main()
