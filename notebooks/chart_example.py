"""Frame exact worker PNG bytes in SVG without redrawing analytical content."""

import re
from base64 import b64encode
from hashlib import sha256
from html import escape
from io import BytesIO
from pathlib import Path

from IPython.display import SVG, display
from PIL import Image


def show_worker_png(frame, name: str, title: str, subtitle: str, source: str) -> dict:
    """Validate one complete worker PNG and embed its original bytes in an SVG frame."""
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        raise ValueError("The review name must contain only letters, numbers, hyphens or underscores.")
    if len(frame) != 1:
        raise RuntimeError(f"Expected one complete PNG; received {len(frame)} chart-so-far rows.")
    payload = bytes(frame.iloc[0].png)
    with Image.open(BytesIO(payload)) as image:
        if image.format != "PNG" or image.size != (1100, 640):
            raise RuntimeError("Expected the worker's 1100 by 640 PNG output.")
        image.verify()
    raw = Path("artifacts/worker-png")
    review = Path("artifacts/review")
    raw.mkdir(parents=True, exist_ok=True)
    review.mkdir(parents=True, exist_ok=True)
    (raw / f"{name}.png").write_bytes(payload)
    encoded = b64encode(payload).decode("ascii")
    # The nested image is the original BLOB byte for byte. SVG supplies only a
    # title, explanatory text and provenance, not vectorized analytical marks.
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
 width="1240" height="940" viewBox="0 0 1240 940" role="img" aria-label="{escape(title, quote=True)}">
 <rect width="1240" height="940" fill="white"/>
 <g font-family="DejaVu Sans, sans-serif" fill="#243B2F">
  <text x="70" y="55" font-size="26" font-weight="bold">{escape(title)}</text>
  <text x="70" y="100" font-size="15" fill="#52665A">{escape(subtitle)}</text>
  <image x="70" y="140" width="1100" height="640" xlink:href="data:image/png;base64,{encoded}"/>
  <text x="70" y="835" font-size="13" fill="#52665A">
   Native worker PNG, embedded unchanged. The analytical plot remains raster content.
  </text>
  <text x="70" y="890" font-size="12" fill="#627468">{escape(source)}</text>
 </g>
</svg>'''
    (review / f"{name}.svg").write_text(svg, encoding="utf-8")
    display(SVG(data=svg))
    return {
        "png_rows": 1,
        "png_bytes": len(payload),
        "width_px": 1100,
        "height_px": 640,
        "worker_png_sha256": sha256(payload).hexdigest(),
        "embedded_bytes_unchanged": True,
        "native_format": "PNG",
        "presentation_format": "SVG with raster image",
    }
