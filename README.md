# vgi-chart

[![test](https://github.com/Query-farm/vgi-chart/actions/workflows/test.yml/badge.svg)](https://github.com/Query-farm/vgi-chart/actions/workflows/test.yml)

A [VGI](https://query.farm) worker (Java) that **renders charts** — line, bar,
pie, scatter, and histogram — from a query result into a **PNG image BLOB**, as
DuckDB SQL functions, powered by [JFreeChart](https://www.jfree.org/jfreechart/).
Catalog name: `chart`.

```sql
INSTALL vgi FROM community; LOAD vgi;
ATTACH 'chart' (TYPE vgi, LOCATION 'java -jar /path/to/vgi-chart-all.jar');

-- a line chart of (x, y) points -> one PNG BLOB row
SELECT png
FROM chart.chart_line((SELECT x, y FROM points), x := 'x', y := 'y');

-- a bar chart with a custom title and size
SELECT octet_length(png)
FROM chart.chart_bar((SELECT category, total FROM sales),
                     category := 'category', value := 'total',
                     title := 'Sales by region', width := 1000, height := 700);

-- a series-grouped line chart (one line per `series` value)
SELECT png
FROM chart.chart_line((SELECT day, amount, region FROM ts),
                      x := 'day', y := 'amount', series := 'region');
```

Each function is a **buffering table-in-out**: it consumes a whole `(SELECT ...)`
input relation and emits a single `(png BLOB)` row — the same data-flow as
`vgi-poi`'s `write_xlsx` (a relation in, one BLOB out). Write the BLOB to disk
with DuckDB's `COPY ... TO 'chart.png' (FORMAT 'raw')` or store it in a table.

The worker is a self-contained, shaded fat JAR. Its manifest sets
`Add-Opens: java.base/java.nio`, so a bare `java -jar vgi-chart-all.jar` works
directly as a VGI `LOCATION` with no extra JVM flags. Charts are rasterized
headlessly (`java.awt.headless=true`), so no display is ever needed.

## Functions

Every function returns a single `(png BLOB)` row containing the rendered PNG.

| Function | Signature | Chart |
| --- | --- | --- |
| `chart_line` | `chart_line(TABLE, x := 'x', y := 'y', series := NULL, title := NULL, width := 800, height := 600)` | Line chart. `series` → one line per series value. `x` numeric → XY plot (points sorted by x); `x` non-numeric → category axis. |
| `chart_bar` | `chart_bar(TABLE, category := 'category', value := 'value', series := NULL, title := NULL, width := 800, height := 600)` | Vertical bar chart. `series` → bars grouped per series value. |
| `chart_scatter` | `chart_scatter(TABLE, x := 'x', y := 'y', series := NULL, title := NULL, width := 800, height := 600)` | Scatter plot. `x` and `y` must be numeric. `series` → one point series per value. |
| `chart_pie` | `chart_pie(TABLE, label := 'label', value := 'value', title := NULL, width := 800, height := 600)` | Pie chart, one slice per distinct `label` (duplicate labels are summed). |
| `chart_histogram` | `chart_histogram(TABLE, value := 'value', bins := 20, title := NULL, width := 800, height := 600)` | Histogram of a numeric `value` over `bins` equal-width buckets. |

### Conventions & behaviour

- **Column names are named arguments** — the `x`, `y`, `category`, `value`,
  `label`, and `series` arguments name columns of the input relation, not values.
  A missing column raises a clear, query-failing error naming the available
  columns.
- **Numeric coercion** — columns where a number is required accept any DuckDB
  numeric type (`TINYINT`…`HUGEINT`, `FLOAT`/`DOUBLE`, `DECIMAL`, `BOOLEAN`, and
  `DATE`/`TIMESTAMP`, which map to their epoch position so a time axis still
  plots). A **non-numeric column where a number is expected** (e.g. a `VARCHAR`
  `y`) raises a clear error: *"… column 'y' is Utf8 but a numeric column is
  required for this chart axis"*.
- **NULLs** — rows with a NULL in a required column are skipped.
- **Empty / NULL relation** → no PNG row. When the input relation is genuinely
  empty, DuckDB's `vgi` extension delivers **no input batch at all**, so the
  worker emits **no rows** (documented). A zero-row *batch* (which some plans
  send as a terminal flush) instead yields a single **blank** chart PNG — that
  path is covered by the JUnit suite.
- **Logging → stderr only.** stdout is the Arrow-IPC channel for a stdio worker;
  all worker logging is routed to stderr (`slf4j-simple` defaults to `System.err`).

## Streaming model (buffering / per-batch)

The VGI table-in-out transport delivers input batches until the input stream
closes; DuckDB's `vgi` extension provides **no separate finalize callback and no
guaranteed terminal empty batch** (the same constraint as `vgi-poi`'s
`write_xlsx`). So each chart function **buffers** every batch's rows and, after
each input batch, **re-renders the complete chart** from all rows seen so far and
emits it as one PNG row:

- **Single-batch input** (the common case, ≤ ~2048 rows) → exactly **one** PNG
  row of the whole relation.
- **Input spanning multiple Arrow batches** (> ~2048 rows) → **one row per
  batch**, the last of which is the complete chart. Consumers that want the final
  image should take the last row (e.g. `ORDER BY`/`LIMIT 1` on a monotonic key,
  or simply read the single row in the common case). This is a documented
  simplification, mirroring `vgi-poi`.

## Determinism / testing note

PNG rendering is **not byte-stable across platforms** (fonts, anti-aliasing, AWT
versions differ), so tests never assert exact bytes. They assert the BLOB is a
valid PNG — its first four bytes are the PNG signature `\x89PNG` — and (in the
JUnit suite) that it **decodes via `ImageIO.read` to the requested
width × height**. The SQL E2E suite only checks the PNG signature + a non-empty
length; pixel dimensions are JUnit-only.

## Build

```sh
./gradlew test          # JUnit (in-process table-in-out driver, real JFreeChart)
./gradlew shadowJar     # -> build/libs/vgi-chart-<version>-all.jar  (a runnable fat JAR)
```

Run the worker directly (a VGI `LOCATION` is just a launcher command):

```sh
java -jar build/libs/vgi-chart-*-all.jar                       # stdio transport (default)
java -jar build/libs/vgi-chart-*-all.jar --http --port 8000    # HTTP transport
```

### The VGI Java SDK

The build depends on the VGI Java SDK — `farm.query:vgi` (the worker/catalog API;
pulls in `farm.query:vgirpc` transitively) — which is **published to Maven
Central**, alongside JFreeChart. A clean checkout builds and tests with no sibling
repos, no `mavenLocal`, and no composite build. The SDK version is pinned in
`build.gradle.kts`; keep it aligned with the `vgi` DuckDB extension version you
run against.

## Dependencies & licensing

| Component | License | Notes |
| --- | --- | --- |
| `vgi-chart` (this worker) | **MIT** | This repository's own code. |
| [JFreeChart](https://www.jfree.org/jfreechart/) (`org.jfree:jfreechart`) | **LGPL-2.1** | The chart rendering engine. **See the LGPL note below.** |
| [`farm.query:vgi` / `farm.query:vgirpc`](https://github.com/Query-farm) | Query Farm Source-Available | The VGI Java SDK (the worker/catalog + RPC API). |
| Apache Arrow, SLF4J | Apache-2.0 / MIT | Arrow IPC transport + logging-to-stderr bridge. |

### LGPL note for JFreeChart

**JFreeChart is licensed under the LGPL-2.1.** This worker uses it as an
**unmodified, standard Maven dependency** — it is depended upon and called
through its public API, never copied into or patched within this repository.
Under the LGPL that is the "using the library" case (not "modifying" it), so
**`vgi-chart`'s own code remains MIT and is fine for commercial use.**

The standard LGPL obligation is that a recipient of a distributed bundle must be
able to **relink or replace** the LGPL component with a modified version. That is
satisfied here because JFreeChart is consumed as an ordinary, **swappable** Maven
Central artifact (`org.jfree:jfreechart`, pinned by version in
`build.gradle.kts`): the build is reproducible from source, the JFreeChart JAR is
an unmodified upstream release, and anyone can bump or replace the JFreeChart
version and rebuild the fat JAR to relink against a different (e.g. self-modified)
JFreeChart. If you ever **vendor or patch** JFreeChart itself, those changes must
in turn be offered under the LGPL. (This is the same posture `vgi-grammar` takes
toward LanguageTool.)

## Testing

```sh
make test        # JUnit (./gradlew test) + SQL E2E
make test-unit   # JUnit only
make test-sql    # fat JAR + haybarn-unittest over test/sql/*
```

The SQL E2E suite (`test/sql/*.test`) runs the real functions inside DuckDB via
[`haybarn-unittest`](https://pypi.org/project/haybarn-unittest/) with the fat JAR
as the VGI `LOCATION`. Install the runner once with `uv tool install haybarn-unittest`
and put `~/.local/bin` on `PATH`. Each `.test` builds its data with `VALUES`/a CTE
and asserts the result is a non-empty PNG via `png[1:4] = '\x89PNG'::BLOB` and
`octet_length(png) > 100`.

> Note: under `haybarn-unittest`, `require vgi` *skips* the file — the `.test`
> files use an explicit `LOAD vgi;` instead.

The VGI SDK and JFreeChart both resolve from **Maven Central**, so the build is
fully self-contained — no `mavenLocal`, no sibling checkout, no composite build.
CI (`.github/workflows/test.yml`) is a single `build-and-test` job: JUnit →
shadowJar → HTTP boot smoke test → `make test-sql`.

## License

MIT — see [LICENSE](LICENSE). JFreeChart is LGPL-2.1 (see the note above).
