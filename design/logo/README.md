# Wireless Whale — logo provenance

Primary artwork was produced with the **image_generate** tool (genuine model
generation, not an SVG/Python drawing), per explicit request.

- Model: openai/gpt-5.4-image-2 (via openrouter)
- Aspect ratio: square (1024×1024)
- Generation handle / source file:
  local model-generation cache (path omitted — machine-local artifact, not
  published); the byte-exact output is preserved below as
  `wireless-whale-logo-original.png`.
- Prompt (exact):
  > Minimal professional app logo brandmark, black on pure white background: a
  > simple timeless whale silhouette (side profile, gently arched body with
  > tail) merged with subtle concentric wireless signal arcs rising from its
  > back, drawn as one coherent flat vector-style symbol. Strong negative
  > space, bold clean lines, geometric, no text, no letters, no 3D, no shadows,
  > no gradients, no cartoon, no cute style, no emoji. Centered composition with
  > generous white safe margin on all sides, square 1:1, high contrast
  > monochrome, legible at tiny 24px size, premium tech brand identity like a
  > Geist-style minimal icon.

## Files (all under `design/logo/`)

| File | Role |
|---|---|
| `wireless-whale-logo-original.png` | Byte-exact copy of the generated original (white matte, preserved). |
| `wireless-whale-logo-canonical-1024.png` | Canonical mark, 1024px, black on white. |
| `wireless-whale-mark-black-transparent-1024.png` | Derived: black art on transparency (flat-white matte keyed out; verified corner alpha 0, art alpha 255). For dark-on-light / adaptive-icon foreground use. |
| `wireless-whale-mark-white-transparent-1024.png` | Derived: inverted white art on transparency (verified alpha). For light-on-dark use. |
| `preview-48px.png`, `preview-24px.png` | Legibility checks rendered from the canonical mark. |

## Visual assessment (vision_analyze on the actual result)

- Coherent whale + wireless-arcs mark: side-profile whale, tail fluke up-right,
  concentric signal arcs rising from the blowhole position as the "spout".
- Strictly flat black on white: no text, no lettering, no 3D, no shadows, no
  gradients, no cartoon styling. Professional pictogram grade.
- 48px preview: whale silhouette + arcs remain recognizable. 24px: readable as
  a mark (fine eye/jaw cutouts soften, expected at that size).
- No regeneration needed: no factual defects, so kept to a single generation.
- Not vector: PNG only. No SVG is claimed; parent integrates raster assets.

Only `design/logo/` outputs were touched. No edits to `app/src`, Gradle,
manifest, or other workers' areas.
