# Chart Rendering walkthroughs

Two independent investigations use the real released worker to render SQL results:

- [01 · Choose the denominator](01_choose_the_denominator.ipynb) compares equally weighted weekly percentages with a rate pooled over all requests. The catalog guides the line/bar choices; neither renderer selects the denominator.
- [02 · Observations and contributions](02_observations_and_contributions.ipynb) separates a row-weighted distribution, volume-versus-rate coordinates, and each region's contribution to total failures. Scatter overlap is audited explicitly; a histogram counts rows and a pie sums failure counts.

Both use twelve **synthetic** region-week observations. They make no claims about a real service or a causal relationship. Every transformation, aggregation and render call is visible SQL. Python validates and presents the returned images without redrawing their analytical content.

## Run locally or in Colab

Install [uv](https://docs.astral.sh/uv/). The tested launcher supports POSIX macOS and glibc Linux on arm64/x86_64; native Windows is not supported because the JAR cache uses POSIX locking. First use needs network access to retrieve the shared package, VGI extension, Python, managed Java and the release JAR. A system Java installation, Gradle and the worker source checkout are unnecessary.

From `notebooks/`:

```sh
uv sync --locked
uv run --locked vgi-notebooks 01_choose_the_denominator.ipynb
uv run --locked vgi-notebooks 02_observations_and_contributions.ipynb
```

An editor can use `notebooks/.venv/bin/python` and `notebooks/` as the working directory. Restart/run all after changing a parameter. Each Colab badge opens the same notebook source; its bootstrap installs the shared **base** package, while the local environment adds `[runner]`. The shared `chart` registry retrieves three immutable companions with SHA256 checks, so a notebook-only copy runs without the repository. The connection uses an empty temporary SQL secret directory with persistent secrets disabled.

The connection remains short and explicit:

```sql
ATTACH 'chart' (TYPE vgi,
 LOCATION 'uv run --locked --no-project --python 3.13 chart_release.py v0.4.0');
```

[`chart_release.py`](chart_release.py) pins the public [v0.4.0 JAR](https://github.com/Query-farm/vgi-chart/releases/tag/v0.4.0) to SHA256 `532df4080167ff55381fa282682abeb149f4b7e7a9e72aeaf3ea68878e45f6c7`. Its [PEP 723 lock](chart_release.py.lock) supplies `jdk4py==21.0.8.2` in an isolated environment, checks the actual **Java 21.0.8** binary, and never uses system Java. Cache publication is atomic and cached bytes are rechecked before execution. Java and the worker libraries do not enter the notebook kernel.

## Native PNG inside an SVG frame

This release returns **PNG only**. The worker has no theme or SVG-output argument. Its native palette, grid, labels and legend are preserved. [`chart_example.py`](chart_example.py) requires exactly one 1100 × 640 PNG, checks its format/dimensions, saves the original BLOB under ignored `artifacts/worker-png/`, and embeds those same bytes in an SVG containing titles and provenance. The analytical plot remains raster content; the SVG does not vectorize it. Validation decodes the embedded image and compares its bytes with the worker output.

Published SVGs are linked under `charts/`, and the shared runner preserves native SVG output for Jupyter and Colab. PNG review previews are ignored. These tiny relations fit one input batch; larger relations may return a chart-so-far per batch, which the helper refuses to silently collapse. Empty input returns zero rows. PNG byte identity across platforms/fonts is not promised; the exact-byte claim is between each actual worker result and its displayed frame.

## Focused experiments

Preserve saved defaults by writing experiments separately:

```sh
uv run --locked vgi-notebooks 01_choose_the_denominator.ipynb \
  --parameters '{"WEEKS":2}' --output-dir validation/two-weeks
uv run --locked vgi-notebooks 02_observations_and_contributions.ipynb \
  --parameters '{"HIST_BINS":8}' --output-dir validation/eight-bins
```

In 01, weeks 1–4 contain 7,800 requests and 370 failures; the highest mean weekly rate is Briar's 6.50%, while the highest pooled rate is Aster's 5.27%. Weeks 1–2 contain 3,300 requests and 112 failures; Briar leads both summaries at 10%. This changes the reporting population, not just the display.

In 02, all four weeks remain fixed. Four equal-width bins over 2–10% have counts **4, 4, 0, 4**. Eight bins have counts **1, 3, 4, 0, 0, 0, 2, 2**. Finer resolution exposes additional empty intervals without adding observations. The scatter and failure contributions remain unchanged: Aster 232, Briar 50 and Cedar 88 failures. SQL independently audits bin membership and additive shares.

The runner starts a fresh kernel, replaces a default only after complete success, rejects unknown parameters and prevents overrides from overwriting the saved default. Parameter validation occurs before ATTACH. Experiment copies retain branding and documented dependencies.

## Coverage and checks

[coverage.json](coverage.json) maps **six of six** distinct public SQL names to meaningful executed SQL, result interpretation and reviews: all five renderers and `chart_types`. Its table/function alias counts once. Complete unfiltered worker function, table and view exports are retained in [coverage/](coverage/), including empty views. No advertised handler names or capabilities are excluded.

The current public-main baseline `e4d57b2b2a2115ebd19f47c30d486273e8cadd3a` differs from release `6d7df69faec4c3a2cbd1c75c7c8b275ecba05418` only in notebooks and README. The worker implementation and build inputs are byte-identical. Current-interface evidence explicitly reuses the released JAR with that source-equivalence review; it is not a claim of a separate current-build execution. Hash-bound execution receipts record the actual release revision.

Validation comprises both defaults, both notebook-only detached experiments, fifteen SQL invariants including empty input, PNG signature/dimension/embedding checks, three runner guards, and invalid parameters for both books. One fresh Python 3.12 kernel with already imported older pandas/Matplotlib/IPython/ipykernel/jupyter-client checks the full companion/bootstrap path without replacing those libraries or installing runner/Java dependencies in the kernel. This is local Colab-style compatibility testing; hosted Colab was not manually executed.

Existing green [source CI at the unchanged baseline](https://github.com/Query-farm/vgi-chart/actions/runs/43563691447) is reused for this notebook-only update. There is no worker rebuild or repeat source suite. JFreeChart 1.5.6 renders locally; no private credentials, external account writes or service data are involved. Full notebook standards live in the [shared package](https://github.com/Query-farm/vgi-example-notebooks/blob/main/docs/standard.md).
