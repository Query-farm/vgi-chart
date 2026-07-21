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
 * {@code chart_scatter(TABLE, x := 'x', y := 'y', series := NULL, title := NULL,
 * width := 800, height := 600) -> (png BLOB)} — a scatter plot. Both x and y must
 * be numeric. With a {@code series} column, one point series per series value.
 */
public final class ChartScatterFunction extends ChartFunction {

    @Override public String name() { return "chart_scatter"; }

    @Override public FunctionMetadata metadata() {
        java.util.Map<String, String> tags = objectTags(
                        "Scatter Plot Renderer",
                        "Render a **scatter plot** as a PNG image `BLOB` from a DuckDB relation. Name "
                                + "the numeric `x` and `y` columns; supply an optional `series` "
                                + "column to draw one colored point series per distinct series "
                                + "value, plus optional `title`, `width`, and `height`.\n\n"
                                + "Use it to inspect the relationship or correlation between two "
                                + "numeric variables, or to compare clusters across groups via the "
                                + "series column. Both x and y must be numeric; rows with NULL x or "
                                + "y are skipped. Returns a single `(png BLOB)` row holding the "
                                + "rendered PNG.",
                        "## chart_scatter\n\n"
                                + "Render a **scatter plot** from a query result to a PNG image "
                                + "`BLOB`.\n\n"
                                + "### Usage\n\n"
                                + "Pass the relation as the table argument, name the numeric `x` "
                                + "and `y` columns, and optionally add a `series` column plus "
                                + "`title`, `width`, and `height`. See this function's example "
                                + "queries for complete, runnable calls.\n\n"
                                + "### Notes\n\n"
                                + "- Both `x` and `y` must be numeric.\n"
                                + "- With `series`, one point series is drawn per distinct value; "
                                + "NULL x/y rows are ignored.",
                        "scatter plot, scatter chart, xy plot, correlation, points, cluster, "
                                + "png, jfreechart, chart, visualization",
                        "ChartScatterFunction.java");
        tags.put("vgi.example_queries", exampleQueriesTag(
                "Render a scatter plot of numeric x/y points and report the PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_scatter("
                        + "(SELECT * FROM (VALUES (1.0, 2.1), (2.0, 3.9), (3.0, 6.2)) AS t(x, y)), "
                        + "x := 'x', y := 'y', title := 'x vs y')",
                "Render one colored point series per cohort of height against weight.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_scatter("
                        + "(SELECT * FROM (VALUES (170.0, 65.0, 'A'), (180.0, 80.0, 'A'), "
                        + "(160.0, 55.0, 'B'), (175.0, 72.0, 'B')) AS t(height_cm, weight_kg, cohort)), "
                        + "x := 'height_cm', y := 'weight_kg', series := 'cohort', "
                        + "title := 'Height vs weight by cohort')"));
        // VGI411: assign a category from the schema's vgi.categories registry.
        tags.put("vgi.category", "trends-and-relationships");
        return baseMetadata(
                "Render a scatter plot from an input relation to a PNG image BLOB (JFreeChart). "
                        + "With a series column, one point series per series value.",
                tags)
                .withCategories("chart", "visualization", "jfreechart");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                tableArg("input", 0,
                        "The input relation to plot. Its rows supply the points; name the "
                        + "columns to use with the `x`, `y`, and optional `series` "
                        + "arguments."),
                namedArg("x", Schemas.UTF8, "x",
                        "Name of the column to use for the x coordinate of each point."),
                namedArg("y", Schemas.UTF8, "y",
                        "Name of the column to use for the y coordinate of each point."),
                namedArg("series", Schemas.UTF8, "",
                        "Optional name of a grouping column; one colored point series is "
                        + "drawn per distinct value. Empty (the default) draws all points in "
                        + "a single series."),
                titleArg(), widthArg(), heightArg());
    }

    @Override public TableInOutExchangeState createExchange(TableInOutInitParams params) {
        Arguments a = params.arguments();
        return new State(
                a.namedString("x", "x"),
                a.namedString("y", "y"),
                a.namedString("series", ""),
                a.namedString("title", ""),
                (int) a.namedLong("width", 800),
                (int) a.namedLong("height", 600));
    }

    private static final class State extends ChartState {
        private final String xCol;
        private final String yCol;
        private final String seriesCol;

        private final List<double[]> points = new ArrayList<>();
        private final List<String> seriesKeys = new ArrayList<>();

        State(String xCol, String yCol, String seriesCol, String title, int width, int height) {
            super(width, height, title);
            this.xCol = xCol;
            this.yCol = yCol;
            this.seriesCol = seriesCol;
        }

        @Override protected void accumulate(VectorSchemaRoot in) {
            FieldVector xv = Columns.require(in, xCol, "x");
            FieldVector yv = Columns.require(in, yCol, "y");
            FieldVector sv = Columns.optional(in, seriesCol);

            int n = in.getRowCount();
            for (int r = 0; r < n; r++) {
                if (Columns.isNull(xv, r) || Columns.isNull(yv, r)) continue;
                points.add(new double[]{
                        Columns.asNumeric(xv, r, xCol, "x"),
                        Columns.asNumeric(yv, r, yCol, "y")});
                seriesKeys.add(sv != null && !Columns.isNull(sv, r)
                        ? Columns.asString(sv, r) : "points");
            }
        }

        @Override protected JFreeChart render() {
            if (points.isEmpty()) return null;
            return ChartRenderer.scatter(points, seriesKeys, title, xCol, yCol);
        }
    }
}
