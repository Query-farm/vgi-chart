# CLAUDE.md — vgi-chart

Contributor/agent notes. User-facing docs live in `README.md`; this is the
"how it's built and where the sharp edges are" companion.

## What this is

A [VGI](https://query.farm) worker (Java) wrapping **JFreeChart** to render charts
— line, bar, pie, scatter, histogram — from a query result into a **PNG image
BLOB**, as DuckDB SQL functions. Modeled on `vgi-poi` (its `write_xlsx` is the
exact "relation in → one BLOB out" data-flow). Built with Gradle (Kotlin DSL,
JDK 21) into a shaded fat JAR. Catalog name `chart` (single `main` schema).

## Layout

```
build.gradle.kts / settings.gradle.kts / gradle.properties   Gradle, shadow plugin (com.gradleup.shadow 9.4.2)
src/main/java/farm/query/vgi/chart/
  Main.java                     Worker.builder().catalogName("chart")...registerTableInOut x5; forces headless
  ChartFunction.java            abstract base: TABLE-IN-OUT buffering ChartState (accumulate per batch, render, emit one PNG row)
  ChartLineFunction.java        chart_line(TABLE, x, y, series, title, width, height)
  ChartBarFunction.java         chart_bar(TABLE, category, value, series, title, width, height)
  ChartScatterFunction.java     chart_scatter(TABLE, x, y, series, title, width, height)
  ChartPieFunction.java         chart_pie(TABLE, label, value, title, width, height)
  ChartHistogramFunction.java   chart_histogram(TABLE, value, bins, title, width, height)
  ChartTypesFunction.java       chart_types() -> browsable reference table (one row per chart type); also registered as a CatalogTable (VGI146/VGI311)
  ChartRenderer.java            JFreeChart builders (one per type) + toPng / blankPng (ImageIO -> PNG byte[])
  ChartSchemas.java             PNG_SCHEMA = single (png BINARY) column
  Columns.java                  named-column lookup + numeric/string coercion (clear error on non-numeric)
src/test/java/...               JUnit: ChartFunctionsTest + TestSupport (in-process table-in-out driver)
test/sql/chart.test             haybarn-unittest E2E (one assertion per chart type)
Makefile                        build / test-unit / test-sql / test / clean
```

## Sharp edges (learned the hard way)

1. **Table-in-out has no finalize signal** (the key one, inherited from
   `vgi-poi`'s `write_xlsx`). `TableInOutExchangeState.onInputBatch` is called per
   input batch and the tick loop just ends when the input stream closes — there is
   **no** finalize callback and DuckDB's `vgi` extension sends **no guaranteed
   terminal empty batch**. So every chart function buffers all rows and emits the
   chart-so-far after *each* batch: single-batch input (≤~2048 rows) → exactly one
   PNG row; multi-batch input → one row per batch, last is complete (documented).
   `ChartFunction.ChartState` centralizes this; subclasses only implement
   `accumulate(VectorSchemaRoot)` and `render()`.
2. **Empty relation → zero input batches → no rows.** When the input relation is
   genuinely empty, DuckDB delivers *no* batch at all, so `onInputBatch` never
   runs and the worker emits **no rows** (not a blank PNG). The blank-PNG path
   (`blankPng`) only fires for a zero-*row* batch, which the in-process JUnit
   driver sends explicitly. The SQL E2E asserts `count(*) = 0` for an empty input;
   JUnit asserts a valid blank PNG for the zero-row-batch path. Both are documented.
3. **DECIMAL coercion.** DuckDB numeric literals like `VALUES (1.0, 2.0)` arrive
   as Arrow `DecimalVector` (or `Decimal256Vector`), **not** `Float8Vector`. The
   first SQL E2E run failed with *"column 'y' is Decimal(3,1,128) but a numeric
   column is required"* until `Columns.asNumeric` + `ChartLineFunction`'s numeric
   detection handled decimals via `getObject().doubleValue()`. If a chart errors on
   a numeric-looking column, check the Arrow vector type first.
4. **PNG rendering is not byte-stable.** Fonts / anti-aliasing / AWT versions
   differ across platforms, so tests never assert exact bytes. JUnit asserts the
   `\x89PNG` signature + `ImageIO.read` → exact width×height; SQL E2E asserts the
   signature (`png[1:4] = '\x89PNG'::BLOB`) + `octet_length(png) > 100`.
5. **DuckDB BLOB SQL gotchas.** `substr(BLOB,...)` and `length(BLOB)` do **not**
   exist — use **`png[1:4]`** (BLOB slice) and **`octet_length(png)`**. The first
   E2E run failed on both; this is the haybarn-correct form.
6. **Headless AWT.** `Main` sets `java.awt.headless=true` (also set via build JVM
   args and `applicationDefaultJvmArgs`) so the worker never needs a display.
7. **`Add-Opens: java.base/java.nio`** is baked into the manifest (Arrow needs it),
   so a bare `java -jar vgi-chart-all.jar` works as a LOCATION with no extra flags.
8. **`haybarn-unittest` skips `require vgi`** — `.test` files use explicit
   `LOAD vgi;`. Table-in-out takes the `(SELECT ...)` input form (poi's pattern).
9. **Logging → stderr.** stdout is the Arrow-IPC channel; `slf4j-simple` defaults
   all output to `System.err`. Any dependency writing to stdout breaks a stdio
   worker. (JFreeChart/AWT are quiet, but the bridge is in place regardless.)

## SDK dependency & CI (self-contained via Maven Central)

Depends on `farm.query:vgi:0.19.0` (pulls in `farm.query:vgirpc:0.16.0`
transitively; vgirpc declared explicitly since the code imports
`farm.query.vgirpc.*`) and `org.jfree:jfreechart:1.5.6`. The VGI SDK provides
`Worker.schemaTags`, `FunctionMetadata.withTag(s)` and `withExamples`, used to
carry the VGI metadata-quality tags (catalog/schema `vgi.doc_llm`/`vgi.doc_md`,
authorship/support tags, per-object `vgi.result_columns_schema` +
`vgi.example_queries` + `vgi.agent_test_tasks`) that the latest `vgi-lint-check`
(`Query-farm/vgi-lint-check@v1`, unpinned) checks at `--fail-on info` (0
findings; gated in CI's `metadata-quality` job). All **on Maven Central**,
so the build is fully self-contained: no sibling checkout, no `mavenLocal`, no
composite build. `.github/workflows/test.yml` is a single `build-and-test` job:
JUnit + shadowJar + HTTP boot smoke test + `make test-sql`.

The in-process test driver (`TestSupport.run`) constructs vgi records directly.
vgi 0.4.0's `TableInOutInitParams` is
`(functionName, arguments, inputSchema, outputSchema, settings, allocator, storage)`
— same shape `vgi-poi`'s `WriteXlsxTest` uses; get exact signatures via
`javap -cp <vgi jar> farm.query.vgi.tableinout.TableInOutInitParams`.

## Licensing — JFreeChart is LGPL-2.1

JFreeChart is **LGPL-2.1**, used as an **unmodified Maven Central dependency**
(the worker stays MIT; the LGPL relink/replace obligation is satisfied by it being
a standard, swappable, version-pinned dependency). See the README licensing table
+ the dedicated LGPL note — same posture `vgi-grammar` takes toward LanguageTool.
Don't vendor or patch JFreeChart; bump the version in `build.gradle.kts` to relink.

## Testing

```sh
./gradlew test    # JUnit (10 tests: one per chart type + grouped line + empty + error cases)
make test-sql     # shadowJar + haybarn-unittest over test/sql/*
make test         # both
```

`make test-sql` builds `build/libs/vgi-chart-*-all.jar`, sets
`VGI_CHART_WORKER="java -jar <abs jar>"`, and runs `haybarn-unittest`. **The SQL
suite is authoritative for type/coercion edges** — the DECIMAL bug above passed
JUnit (which uses explicit `Float8Vector` fixtures) and was caught only by E2E.

## Packaging

~33 MB shaded JAR (JFreeChart 1.5.6, LGPL-2.1; VGI SDK + Apache Arrow dominate the size).
