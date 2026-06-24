package farm.query.vgi.chart;

import farm.query.vgi.Worker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VGI worker entry point: render charts from a query result into PNG image BLOBs
 * via JFreeChart, as DuckDB table-in-out SQL functions. Catalog name: {@code chart}.
 *
 * <p>Attach from DuckDB with:
 * <pre>{@code
 * ATTACH 'chart' (TYPE vgi, LOCATION 'java -jar vgi-chart-all.jar');
 * SELECT length(png) FROM chart.chart_line((SELECT x, y FROM points), x := 'x', y := 'y');
 * }</pre>
 */
public final class Main {

    private Main() {}

    public static final String GIT_COMMIT =
            System.getenv("VGI_CHART_GIT_COMMIT") != null
                    ? System.getenv("VGI_CHART_GIT_COMMIT") : "unknown";

    /** Catalog-level VGI metadata tags (LLM/Markdown docs, authorship, support). */
    static Map<String, String> catalogTags() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.description_llm",
                "Render a DuckDB query result into a chart as a PNG image BLOB. Five "
                + "table-in-out functions — `chart_line`, `chart_bar`, `chart_pie`, "
                + "`chart_scatter`, `chart_histogram` — each take a relation plus named "
                + "column arguments and emit a single `(png BLOB)` row holding the rendered "
                + "PNG. Use to visualize aggregates and series directly in SQL (e.g. a sales "
                + "bar chart, a time series line, a value distribution histogram) without an "
                + "external plotting tool. Rendered headless with JFreeChart via java.awt.");
        t.put("vgi.description_md",
                "# chart\n\n"
                + "Render charts from a DuckDB query result into a **PNG image BLOB**, "
                + "powered by [JFreeChart](https://www.jfree.org/jfreechart/).\n\n"
                + "Table-in-out functions (each emits one `(png BLOB)` row):\n\n"
                + "- `chart_line(TABLE, x, y, series, title, width, height)` — line chart\n"
                + "- `chart_bar(TABLE, category, value, series, title, width, height)` — bar chart\n"
                + "- `chart_pie(TABLE, label, value, title, width, height)` — pie chart\n"
                + "- `chart_scatter(TABLE, x, y, series, title, width, height)` — scatter plot\n"
                + "- `chart_histogram(TABLE, value, bins, title, width, height)` — histogram");
        t.put("vgi.author", "Query.Farm");
        t.put("vgi.copyright", "Copyright 2026 Query Farm LLC - https://query.farm");
        t.put("vgi.license", "MIT");
        t.put("vgi.support_contact", "https://github.com/Query-farm/vgi-chart/issues");
        t.put("vgi.support_policy_url",
                "https://github.com/Query-farm/vgi-chart/blob/main/README.md");
        return t;
    }

    /** Schema-level VGI metadata tags for the single `main` schema. */
    static Map<String, String> mainSchemaTags() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.description_llm",
                "Chart-rendering table-in-out functions: turn a relation into a PNG image "
                + "BLOB. `chart_line`, `chart_bar`, `chart_pie`, `chart_scatter`, and "
                + "`chart_histogram` each take an input relation plus named column arguments "
                + "and return a single `(png BLOB)` row.");
        t.put("vgi.description_md",
                "Chart-rendering functions (line, bar, pie, scatter, histogram) that render a "
                + "query result to a PNG image BLOB via JFreeChart.");
        return t;
    }

    public static Worker buildWorker() {
        // Charts are rasterized through java.awt; force headless so the worker
        // never needs a display (also set by the build's JVM args / manifest).
        System.setProperty("java.awt.headless", "true");
        return Worker.builder()
                .catalogName("chart")
                .implementationVersion(GIT_COMMIT)
                .catalogComment("Render charts (line/bar/pie/scatter/histogram) from a query result "
                        + "into PNG image BLOBs via JFreeChart")
                .catalogTags(catalogTags())
                .sourceUrl("https://github.com/Query-farm/vgi-chart")
                .schemaComment("main", "Chart-rendering functions that turn a query result into "
                        + "a PNG image BLOB (line, bar, pie, scatter, histogram).")
                .schemaTags("main", mainSchemaTags())
                .registerTableInOut(new ChartLineFunction())
                .registerTableInOut(new ChartBarFunction())
                .registerTableInOut(new ChartScatterFunction())
                .registerTableInOut(new ChartPieFunction())
                .registerTableInOut(new ChartHistogramFunction());
    }

    public static void main(String[] args) {
        String stderrPath = System.getenv("VGI_WORKER_STDERR");
        if (stderrPath != null && !stderrPath.isEmpty()) {
            try {
                java.io.PrintStream ps = new java.io.PrintStream(
                        new java.io.FileOutputStream(stderrPath, true), true);
                System.setErr(ps);
            } catch (Exception ignore) {
                // best-effort stderr redirect
            }
        }
        buildWorker().runFromArgs(args);
    }
}
