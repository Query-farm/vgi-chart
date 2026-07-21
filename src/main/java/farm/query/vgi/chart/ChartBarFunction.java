package farm.query.vgi.chart;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgi.types.Schemas;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.jfree.chart.JFreeChart;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code chart_bar(TABLE, category := 'category', value := 'value', series := NULL,
 * title := NULL, width := 800, height := 600) -> (png BLOB)} — a vertical bar
 * chart. With a {@code series} column, bars are grouped per series value.
 */
public final class ChartBarFunction extends ChartFunction {

    @Override public String name() { return "chart_bar"; }

    @Override public FunctionMetadata metadata() {
        java.util.Map<String, String> tags = objectTags(
                        "Bar Chart Renderer",
                        "Render a **bar chart** as a PNG image `BLOB` from a DuckDB relation. Name "
                                + "the `category` column (the discrete x-axis groups) and the "
                                + "numeric `value` column; supply an optional `series` column to "
                                + "draw grouped bars (one colored bar per series value within each "
                                + "category), plus optional `title`, `width`, and `height`.\n\n"
                                + "Use it to compare a numeric measure across discrete categories — "
                                + "for example counts per status, revenue per quarter, or units per "
                                + "product. Rows with a NULL category or value are skipped. Returns "
                                + "a single `(png BLOB)` row holding the rendered PNG.",
                        "## chart_bar\n\n"
                                + "Render a **vertical bar chart** from a query result to a PNG "
                                + "image `BLOB`.\n\n"
                                + "### Usage\n\n"
                                + "Pass the relation as the table argument, name the `category` "
                                + "and `value` columns, and optionally add a `series` column plus "
                                + "`title`, `width`, and `height`. See this function's example "
                                + "queries for complete, runnable calls.\n\n"
                                + "### Notes\n\n"
                                + "- One bar per category; with `series`, bars are grouped per "
                                + "series value.\n"
                                + "- The `value` column must be numeric; NULL category/value rows "
                                + "are ignored.",
                        "bar chart, bar graph, column chart, categories, grouped bars, comparison, "
                                + "png, jfreechart, chart, visualization",
                        "ChartBarFunction.java");
        tags.put("vgi.example_queries", exampleQueriesTag(
                "Render one bar per category and report the PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_bar("
                        + "(SELECT * FROM (VALUES ('A', 30), ('B', 45), ('C', 12)) AS t(category, value)), "
                        + "category := 'category', value := 'value', title := 'Counts')",
                "Render grouped bars (one group per product series) of units by quarter.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_bar("
                        + "(SELECT * FROM (VALUES ('Q1', 30, 'Widget'), ('Q2', 42, 'Widget'), "
                        + "('Q1', 18, 'Gadget'), ('Q2', 25, 'Gadget')) AS t(quarter, units, product)), "
                        + "category := 'quarter', value := 'units', series := 'product', "
                        + "title := 'Units per quarter by product')"));
        // VGI411: assign a category from the schema's vgi.categories registry.
        tags.put("vgi.category", "categorical-comparisons");
        return baseMetadata(
                "Render a bar chart from an input relation to a PNG image BLOB (JFreeChart). "
                        + "With a series column, bars are grouped per series value.",
                tags)
                .withCategories("chart", "visualization", "jfreechart");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                tableArg("input", 0,
                        "The input relation to plot. Its rows supply the bars; name the "
                        + "columns to use with the `category`, `value`, and optional `series` "
                        + "arguments."),
                namedArg("category", Schemas.UTF8, "category",
                        "Name of the column holding the discrete category for each bar (the "
                        + "x-axis groups)."),
                namedArg("value", Schemas.UTF8, "value",
                        "Name of the column giving each bar's height (the measure being "
                        + "compared across categories)."),
                namedArg("series", Schemas.UTF8, "",
                        "Optional name of a grouping column; bars are grouped with one "
                        + "colored bar per distinct value within each category. Empty (the "
                        + "default) draws a single bar per category."),
                titleArg(), widthArg(), heightArg());
    }

    @Override public TableInOutExchangeState createExchange(TableInOutInitParams params) {
        Arguments a = params.arguments();
        return new State(
                a.namedString("category", "category"),
                a.namedString("value", "value"),
                a.namedString("series", ""),
                a.namedString("title", ""),
                (int) a.namedLong("width", 800),
                (int) a.namedLong("height", 600));
    }

    private static final class State extends ChartState {
        private final String catCol;
        private final String valCol;
        private final String seriesCol;

        private final List<String> categories = new ArrayList<>();
        private final List<Double> values = new ArrayList<>();
        private final List<String> seriesKeys = new ArrayList<>();

        State(String catCol, String valCol, String seriesCol, String title, int width, int height) {
            super(width, height, title);
            this.catCol = catCol;
            this.valCol = valCol;
            this.seriesCol = seriesCol;
        }

        @Override protected void accumulate(VectorSchemaRoot in) {
            FieldVector cv = Columns.require(in, catCol, "category");
            FieldVector vv = Columns.require(in, valCol, "value");
            FieldVector sv = Columns.optional(in, seriesCol);

            int n = in.getRowCount();
            for (int r = 0; r < n; r++) {
                if (Columns.isNull(cv, r) || Columns.isNull(vv, r)) continue;
                categories.add(Columns.asString(cv, r));
                values.add(Columns.asNumeric(vv, r, valCol, "value"));
                seriesKeys.add(sv != null && !Columns.isNull(sv, r)
                        ? Columns.asString(sv, r) : "value");
            }
        }

        @Override protected JFreeChart render() {
            if (categories.isEmpty()) return null;
            return ChartRenderer.bar(categories, values, seriesKeys, title, catCol, valCol);
        }
    }
}
