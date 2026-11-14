# Chart notebooks

[Choose the denominator before drawing the chart](01_choose_the_denominator.ipynb)
compares average weekly failure rates with pooled request rates for a small synthetic
fixture. Visible SQL prepares the data and calls the real Java chart worker. Its
returned line/bar PNGs are displayed with provenance footers; Python does not redraw
the analytical charts.

## Run from this folder

Prerequisites: [uv](https://docs.astral.sh/uv/), Java 21+ on PATH and network access for
initial package/extension and JAR downloads. `uv` can provision Python. Copy this
whole `notebooks/` folder anywhere; no worker checkout, Gradle installation, source
build or root virtual environment is required.

```bash
cd notebooks
uv sync --frozen
uv run --frozen python execute.py
```

The notebook environment is locked to Haybarn 2.0.0rc11. The literal ATTACH uses the
published `v0.4.0/vgi-chart-v0.4.0.jar` and SHA256
`532df4080167ff55381fa282682abeb149f4b7e7a9e72aeaf3ea68878e45f6c7`.
`release_jar.py` downloads it from GitHub, verifies that digest and caches it under
`${XDG_CACHE_HOME:-~/.cache}/vgi-notebook-jars`. It rechecks cached bytes before reuse,
serializes downloads with a POSIX file lock, then replaces itself with headless Java
so VGI owns stdin/stdout. Setup output goes to stderr. The helper supports macOS/Linux;
execution was verified on macOS arm64 with Java 21.0.13. Windows is not validated.

To upgrade, change the literal release filename, tag and digest together, then rerun
both parameter cases from a notebook-only folder. The release JAR bundles its existing
dependencies; licensing is described in the worker repository. No JAR is vendored here.

For an interactive kernel:

```bash
uv run --frozen python -m ipykernel install --user \
  --name vgi-chart-notebooks --display-name 'Python (vgi-chart notebooks)'
```

Open the notebook in your preferred Jupyter interface, select that kernel and use
this folder as its working directory. Parameters, connection and analytical SQL
remain visible. Setup, image presentation and cleanup are folded.

## Data and rendering boundaries

All 12 observations are invented: four weeks of request/failure counts in three
fictional regions. `WEEKS` includes weeks 1 through the chosen integer 2–4. There is
no live data, credential or external dataset. Request counts are positive and failures
are a subset of requests; SQL checks the grain and these bounds.

An average of weekly percentages gives each week equal weight. A pooled rate gives
each request equal weight. Neither definition is automatically correct for every
question; the notebook explains the distinction and checks the weighted identity.
The renderer consumes these SQL calculations rather than choosing the denominator.

The worker emits a chart-so-far after each input batch. These tiny relations are
validated to return exactly one PNG; the helper refuses multiple rows instead of
assuming an arbitrary image is final. Each PNG is checked for format and requested
1100×640 dimensions. Bar input is aggregated to one row per category/series pair,
because this worker replaces duplicate pairs instead of summing them.

The helper writes unmodified worker bytes to `validation/worker-png/` and places the
image inside a Matplotlib frame with a source footer under `validation/charts/`.
JFreeChart controls the plot itself; Seaborn styles only the surrounding presentation.
Exact PNG bytes can vary across operating systems, fonts and JVMs.

## Focused experiment

```bash
uv run --frozen python execute.py --parameters '{"WEEKS":2}' \
  --output-dir validation/weeks-2
```

Keep the fixture unchanged and shorten the horizon from four weeks to two. Compare
region rankings, Briar's averaging gap and both images. This changes the reporting
population, not the behavior of a real service. Reset `WEEKS = 4` afterward.

The runner uses a fresh temporary kernel and writes output only on success. Overrides
require a separate destination; companion assets and runtime documentation are copied
there. Logs, extracted PNGs and experiment copies are ignored by Git. The main notebook
retains successful embedded outputs.
