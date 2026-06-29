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

    /**
     * Catalog/schema example queries as a JSON array of {@code {"description","sql"}}
     * objects — the format {@code vgi.example_queries} requires. Each SQL is
     * self-contained and catalog-qualified so it executes against the attached
     * worker.
     */
    static final String EXAMPLE_QUERIES_JSON =
            "["
            + "{\"description\":\"Render a line chart from inline points.\","
            + "\"sql\":\"SELECT octet_length(png) FROM chart.main.chart_line("
            + "(SELECT * FROM (VALUES (1, 10), (2, 25), (3, 18)) AS t(x, y)), x := 'x', y := 'y')\"},"
            + "{\"description\":\"Render a bar chart of category counts.\","
            + "\"sql\":\"SELECT octet_length(png) FROM chart.main.chart_bar("
            + "(SELECT * FROM (VALUES ('A', 30), ('B', 45)) AS t(category, value)), "
            + "category := 'category', value := 'value')\"},"
            + "{\"description\":\"Render a pie chart of two slices.\","
            + "\"sql\":\"SELECT octet_length(png) FROM chart.main.chart_pie("
            + "(SELECT * FROM (VALUES ('X', 65), ('Y', 35)) AS t(label, value)), "
            + "label := 'label', value := 'value')\"},"
            + "{\"description\":\"Render a scatter plot of numeric x/y points.\","
            + "\"sql\":\"SELECT octet_length(png) FROM chart.main.chart_scatter("
            + "(SELECT * FROM (VALUES (1.0, 2.1), (2.0, 3.9)) AS t(x, y)), x := 'x', y := 'y')\"},"
            + "{\"description\":\"Render a 5-bin histogram of a numeric column.\","
            + "\"sql\":\"SELECT octet_length(png) FROM chart.main.chart_histogram("
            + "(SELECT * FROM (VALUES (1.0), (1.5), (2.0)) AS t(value)), value := 'value', bins := 5)\"}"
            + "]";

    public static final String GIT_COMMIT =
            System.getenv("VGI_CHART_GIT_COMMIT") != null
                    ? System.getenv("VGI_CHART_GIT_COMMIT") : "unknown";

    /** Catalog-level VGI metadata tags (LLM/Markdown docs, authorship, support). */
    static Map<String, String> catalogTags() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.title", "Chart Rendering for SQL");
        t.put("vgi.keywords", ChartFunction.keywordsJson(
                "chart, charts, charting, plot, plotting, graph, visualization, png, image, "
                + "line, bar, pie, scatter, histogram, jfreechart, dataviz"));
        t.put("vgi.doc_llm",
                "Render a DuckDB query result into a chart as a PNG image BLOB. Five "
                + "table-in-out functions — `chart_line`, `chart_bar`, `chart_pie`, "
                + "`chart_scatter`, `chart_histogram` — each take a relation plus named "
                + "column arguments and emit a single `(png BLOB)` row holding the rendered "
                + "PNG. Use to visualize aggregates and series directly in SQL (e.g. a sales "
                + "bar chart, a time series line, a value distribution histogram) without an "
                + "external plotting tool. Rendered headless with JFreeChart via java.awt.");
        t.put("vgi.doc_md",
                "# Chart Rendering for SQL\n\n"
                + "**Turn any DuckDB query into a publication-ready chart — line, bar, pie, "
                + "scatter, or histogram — rendered to a PNG image BLOB entirely in SQL, with "
                + "no plotting notebook, BI tool, or external service.**\n\n"
                + "The `chart` extension lets you visualize query results where the data already "
                + "lives. Point a chart function at a relation, name the columns to plot, and get "
                + "back a single PNG ready to write to disk, embed in a report, attach to an "
                + "email, or stream to a web response. It is built for data engineers, analysts, "
                + "and automation pipelines that need data visualization as a first-class SQL "
                + "operation — dashboards generated on a schedule, charts pasted into Markdown or "
                + "HTML, and image artifacts produced straight from an ETL job, all without "
                + "leaving the database.\n\n"
                + "Charts are rendered by [JFreeChart](https://www.jfree.org/jfreechart/), the "
                + "mature, widely deployed Java charting library, running fully headless through "
                + "`java.awt` so no display or windowing system is required. Each function is a "
                + "table-in / table-out function: it consumes the input relation as a "
                + "table-valued first argument, accumulates the rows, draws the chart with "
                + "JFreeChart, and rasterizes it to PNG bytes. The result is returned as a single "
                + "`(png BLOB)` row, so the rendered image flows through the rest of your SQL like "
                + "any other value.\n\n"
                + "Five chart types are exposed as SQL functions. `chart_line(TABLE, x, y, "
                + "series, title, width, height)` plots a line or time series; `chart_bar(TABLE, "
                + "category, value, series, title, width, height)` draws a category bar chart; "
                + "`chart_pie(TABLE, label, value, title, width, height)` builds a pie chart of "
                + "labelled slices; `chart_scatter(TABLE, x, y, series, title, width, height)` "
                + "produces an x/y scatter plot; and `chart_histogram(TABLE, value, bins, title, "
                + "width, height)` bins a numeric column into a distribution histogram. A typical "
                + "call looks like `SELECT octet_length(png) FROM "
                + "chart.main.chart_bar((SELECT category, total FROM sales), category := "
                + "'category', value := 'total', title := 'Sales by region')`. Optional `title`, "
                + "`width`, and `height` arguments control the rendered output, and the returned "
                + "PNG can be saved with DuckDB's `COPY`/file functions or returned directly to a "
                + "caller.\n\n"
                + "Learn more from the [JFreeChart project homepage](https://www.jfree.org/jfreechart/), "
                + "the [source code on GitHub](https://github.com/jfree/jfreechart), and the "
                + "[JFreeChart API documentation](https://www.jfree.org/jfreechart/javadoc/index.html).");
        t.put("vgi.author", "Query.Farm");
        t.put("vgi.copyright", "Copyright 2026 Query Farm LLC - https://query.farm");
        t.put("vgi.license", "MIT");
        t.put("vgi.support_contact", "https://github.com/Query-farm/vgi-chart/issues");
        t.put("vgi.support_policy_url",
                "https://github.com/Query-farm/vgi-chart/blob/main/README.md");
        t.put("vgi.example_queries", EXAMPLE_QUERIES_JSON);
        return t;
    }

    /** Schema-level VGI metadata tags for the single `main` schema. */
    static Map<String, String> mainSchemaTags() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.title", "Chart Functions — main");
        t.put("vgi.keywords", ChartFunction.keywordsJson(
                "chart, chart_line, chart_bar, chart_pie, chart_scatter, chart_histogram, "
                + "plot, graph, visualization, png, jfreechart"));
        // VGI123 classifying tags use BARE keys (NOT vgi.-namespaced).
        t.put("domain", "data-visualization");
        t.put("category", "charting");
        t.put("topic", "chart-rendering");
        // VGI139: per-object vgi.source_url is intentionally not set; the source
        // URL lives only on the catalog object (Worker.builder().sourceUrl(...)).
        t.put("vgi.doc_llm",
                "Chart-rendering table-in-out functions: turn a relation into a PNG image "
                + "BLOB. `chart_line`, `chart_bar`, `chart_pie`, `chart_scatter`, and "
                + "`chart_histogram` each take an input relation plus named column arguments "
                + "and return a single `(png BLOB)` row.");
        t.put("vgi.doc_md",
                "## Chart functions\n\n"
                + "Render a query result to a PNG image BLOB via JFreeChart. One function per "
                + "chart type — line, bar, pie, scatter, histogram — each consuming a relation "
                + "(table-valued argument) plus named column arguments and emitting a single "
                + "`(png BLOB)` row.");
        t.put("vgi.example_queries", EXAMPLE_QUERIES_JSON);
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
