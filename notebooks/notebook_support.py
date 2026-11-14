"""Present the worker's actual PNG bytes with a consistent provenance footer."""

from io import BytesIO
from pathlib import Path

import matplotlib.pyplot as plt
import seaborn as sns
from IPython.display import Markdown, display
from PIL import Image


def theme() -> None:
    """Set the notebook frame style; the embedded chart remains the worker's render."""
    sns.set_theme(style="white", context="notebook", font="DejaVu Sans")
    plt.rcParams.update({"figure.dpi": 100, "text.parse_math": False})


def prose(text: str) -> None:
    """Render result-derived Markdown."""
    display(Markdown(text))


def show_worker_png(frame, filename: str, source: str) -> dict:
    """Validate and display one complete PNG without recreating its plotted values."""
    if len(frame) != 1:
        raise RuntimeError(f"Expected one complete PNG for this small input; received {len(frame)} chart-so-far rows.")
    payload = bytes(frame.iloc[0].png)
    with Image.open(BytesIO(payload)) as decoded:
        if decoded.format != "PNG" or decoded.size != (1100, 640):
            raise RuntimeError("Unexpected worker image format or dimensions")
        pixels = decoded.copy()
    directory = Path("validation/worker-png")
    directory.mkdir(parents=True, exist_ok=True)
    (directory / filename).write_bytes(payload)
    fig = plt.figure(figsize=(11, 7.1))
    ax = fig.add_axes((0, 0.1, 1, 0.9))
    ax.imshow(pixels, interpolation="none")
    ax.set_axis_off()
    fig.text(0.025, 0.035, source, color="#52675a", fontsize=10)
    destination = Path("validation/charts")
    destination.mkdir(parents=True, exist_ok=True)
    fig.savefig(destination / filename, dpi=100)
    plt.show()
    return {"png_bytes": len(payload), "width_px": pixels.width, "height_px": pixels.height, "png_rows": len(frame)}
