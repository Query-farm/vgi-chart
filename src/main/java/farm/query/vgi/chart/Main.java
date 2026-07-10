package farm.query.vgi.chart;

import farm.query.vgi.Worker;
import farm.query.vgi.catalog.CatalogTable;
import farm.query.vgi.internal.SchemaUtil;

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

    /**
     * Fixed analyst task suite for {@code vgi-lint simulate} (the
     * {@code vgi.agent_test_tasks} tag, VGI152). Each task names one chart
     * function so the suite covers all five objects. A rendered PNG is not
     * byte-stable across platforms, so every reference grades on a deterministic
     * bounded property — the result-row count (always 1) or a "PNG larger than N
     * bytes" boolean — never on exact bytes. {@code ignore_column_names} keeps
     * grading value-only so an analyst's column alias does not matter. Only the
     * {@code prompt} is shown to the analyst; {@code reference_sql} is grader-only.
     */
    static final String AGENT_TEST_TASKS_JSON =
            "["
            + "{\"name\":\"bar chart row count\","
            + "\"prompt\":\"Use the chart worker to render a vertical bar chart from three "
            + "categories built inline with a VALUES relation: 'A' = 30, 'B' = 45, 'C' = 12 "
            + "(columns named category and value). Return the number of result rows the chart "
            + "function produces as a single column.\","
            + "\"reference_sql\":\"SELECT count(*) AS n FROM chart.main.chart_bar("
            + "(SELECT * FROM (VALUES ('A', 30), ('B', 45), ('C', 12)) AS t(category, value)), "
            + "category := 'category', value := 'value')\","
            + "\"ignore_column_names\":true},"
            + "{\"name\":\"line chart row count\","
            + "\"prompt\":\"Use the chart worker to render a line chart from the points "
            + "(x, y) = (1, 10), (2, 25), (3, 18), (4, 30), built inline with a VALUES relation "
            + "(columns x and y). Return the number of result rows the chart function produces as "
            + "a single column.\","
            + "\"reference_sql\":\"SELECT count(*) AS n FROM chart.main.chart_line("
            + "(SELECT * FROM (VALUES (1, 10), (2, 25), (3, 18), (4, 30)) AS t(x, y)), "
            + "x := 'x', y := 'y')\","
            + "\"ignore_column_names\":true},"
            + "{\"name\":\"pie chart png size check\","
            + "\"prompt\":\"Use the chart worker to draw a pie chart of two slices built inline "
            + "with a VALUES relation: 'X' = 65 and 'Y' = 35 (columns label and value). Return a "
            + "single boolean column that is true when the rendered PNG image is larger than 100 "
            + "bytes.\","
            + "\"reference_sql\":\"SELECT octet_length(png) > 100 AS ok FROM chart.main.chart_pie("
            + "(SELECT * FROM (VALUES ('X', 65), ('Y', 35)) AS t(label, value)), "
            + "label := 'label', value := 'value')\","
            + "\"ignore_column_names\":true},"
            + "{\"name\":\"scatter plot row count\","
            + "\"prompt\":\"Use the chart worker to render a scatter plot of the numeric points "
            + "(x, y) = (1.0, 2.1), (2.0, 3.9), (3.0, 1.5), built inline with a VALUES relation "
            + "(columns x and y). Return the number of result rows the chart function produces as "
            + "a single column.\","
            + "\"reference_sql\":\"SELECT count(*) AS n FROM chart.main.chart_scatter("
            + "(SELECT * FROM (VALUES (1.0, 2.1), (2.0, 3.9), (3.0, 1.5)) AS t(x, y)), "
            + "x := 'x', y := 'y')\","
            + "\"ignore_column_names\":true},"
            + "{\"name\":\"histogram png size check\","
            + "\"prompt\":\"Use the chart worker to render a histogram with 5 bins of the numeric "
            + "values 1.0, 1.5, 2.0, 2.5, 3.0, built inline with a VALUES relation (column value). "
            + "Return a single boolean column that is true when the rendered PNG image is larger "
            + "than 100 bytes.\","
            + "\"reference_sql\":\"SELECT octet_length(png) > 100 AS ok FROM "
            + "chart.main.chart_histogram("
            + "(SELECT * FROM (VALUES (1.0), (1.5), (2.0), (2.5), (3.0)) AS t(value)), "
            + "value := 'value', bins := 5)\","
            + "\"ignore_column_names\":true},"
            + "{\"name\":\"chart type catalog count\","
            + "\"prompt\":\"Using the chart worker's browsable catalog, find out how many "
            + "distinct chart types it offers. Return that total as a single column.\","
            + "\"reference_sql\":\"SELECT count(*) AS n FROM chart.main.chart_types()\","
            + "\"ignore_column_names\":true}"
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
                "Render a DuckDB query result into a chart as a PNG image BLOB, entirely in "
                + "SQL. Each chart function is a table-in-out function: it consumes an input "
                + "relation as its table-valued first argument, reads the columns you name, "
                + "draws the chart headless with JFreeChart, and returns a single `(png BLOB)` "
                + "row holding the rendered image. Reach for this worker when you want to "
                + "visualize aggregates, series, or distributions directly where the data lives "
                + "— a category comparison, a time series, a value spread — and get back an "
                + "image you can write to disk, embed in a report, or return to a caller, "
                + "without an external plotting notebook or BI tool. List the schema to discover "
                + "the specific chart types available and the column arguments each takes.");
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
                + "`java.awt` so no display or windowing system is required. Every function "
                + "follows the same table-in / table-out shape: it consumes the input relation as "
                + "a table-valued first argument, accumulates the rows, draws the chart, and "
                + "rasterizes it to PNG bytes. The result comes back as a single `(png BLOB)` "
                + "row, so the rendered image flows through the rest of your SQL like any other "
                + "value and can be saved with DuckDB's `COPY`/file functions or returned "
                + "directly to a caller.\n\n"
                + "The worker covers the everyday visualization needs — trends and relationships "
                + "between numeric variables, comparisons of a measure across discrete "
                + "categories, and the distribution of a single column — with a shared set of "
                + "optional title, width, and height controls over the rendered output. List the "
                + "schema to see the exact chart functions available and the column arguments "
                + "each one takes.\n\n"
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
        // VGI152: a fixed analyst task suite so `vgi-lint simulate` can grade how
        // well an agent actually discovers and uses the worker.
        t.put("vgi.agent_test_tasks", AGENT_TEST_TASKS_JSON);
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
                "Chart-rendering table-in-out functions that turn a relation into a PNG image "
                + "BLOB. Each function takes the input relation as a table-valued first argument "
                + "plus named column arguments identifying what to plot, renders the chart "
                + "headless with JFreeChart, and returns a single `(png BLOB)` row. The functions "
                + "cover trends and relationships between numeric variables, comparisons across "
                + "discrete categories, and the distribution of a single numeric column; list "
                + "the schema to see each one and its arguments.");
        t.put("vgi.doc_md",
                "## Chart functions\n\n"
                + "Render a query result to a PNG image BLOB via JFreeChart. Each function is a "
                + "table-in / table-out function: it consumes the input relation as a "
                + "table-valued first argument, reads the columns you name, and emits a single "
                + "`(png BLOB)` row holding the rendered image.\n\n"
                + "The functions span the everyday visualization needs — trends and "
                + "relationships between numeric variables, comparisons of a measure across "
                + "discrete categories, and the shape of a single column's distribution — and "
                + "share a common set of optional title, width, and height controls.\n\n"
                + "Charts render fully headless through `java.awt`, so no display is required, "
                + "and the returned PNG can be written to disk, embedded in a document, or "
                + "returned straight to a caller.");
        // VGI413: an ordered category registry for this schema. Each chart function
        // carries a `vgi.category` naming one of these; categories drive the
        // worker's navigation, listing sections, and SEO descriptions.
        t.put("vgi.categories",
                "["
                + "{\"name\":\"trends-and-relationships\","
                + "\"title\":\"Trends & relationships\","
                + "\"description\":\"Charts for ordered, continuous, or paired numeric data — "
                + "trends over an axis and correlations between two variables.\"},"
                + "{\"name\":\"categorical-comparisons\","
                + "\"title\":\"Categorical comparisons\","
                + "\"description\":\"Charts that compare a numeric measure across discrete "
                + "categories or show the composition of a whole.\"},"
                + "{\"name\":\"distributions\","
                + "\"title\":\"Distributions\","
                + "\"description\":\"Charts that summarize the shape and spread of a single "
                + "numeric column by binning it into buckets.\"},"
                + "{\"name\":\"reference\","
                + "\"title\":\"Reference\","
                + "\"description\":\"Browsable reference tables describing the worker's own "
                + "chart types and the column arguments each one takes.\"}"
                + "]");
        t.put("vgi.example_queries", EXAMPLE_QUERIES_JSON);
        return t;
    }

    /**
     * The browsable {@code chart_types} reference table (VGI146/VGI311): scans the
     * parameterless {@code chart_types} table function so consumers can write
     * {@code SELECT * FROM chart.main.chart_types} (no parentheses). Shares the
     * function's discovery tags and declared static result schema.
     */
    static CatalogTable chartTypesTable() {
        return CatalogTable.builder(
                        "main", "chart_types",
                        SchemaUtil.serializeSchema(ChartSchemas.CHART_TYPES_SCHEMA))
                .comment("One row per chart function this worker exposes, with its category, "
                        + "a one-line summary, and the column arguments it takes.")
                .tags(ChartTypesFunction.tags())
                .scanFunction("chart_types")
                .cardinality(ChartTypesFunction.ROWS.length, ChartTypesFunction.ROWS.length)
                // VGI807/VGI806: chart_type uniquely identifies each reference row.
                .primaryKey(java.util.List.of(java.util.List.of(0)))
                .build();
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
                .registerTableInOut(new ChartHistogramFunction())
                // A parameterless reference table function listing the chart types...
                .registerTable(new ChartTypesFunction())
                // ...also exposed as a browsable table so an agent can SELECT * FROM
                // chart.main.chart_types to discover the chart types before calling one
                // (VGI146/VGI311).
                .registerCatalogTable(chartTypesTable());
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
