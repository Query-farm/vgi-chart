package farm.query.vgi.chart;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgi.types.Schemas;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.jfree.chart.JFreeChart;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code chart_line(TABLE, x := 'x', y := 'y', series := NULL, title := NULL,
 * width := 800, height := 600) -> (png BLOB)} — a line chart. With a {@code series}
 * column, one line per distinct series value; the x axis is numeric when the x
 * column is numeric, otherwise a category axis.
 */
public final class ChartLineFunction extends ChartFunction {

    @Override public String name() { return "chart_line"; }

    @Override public FunctionMetadata metadata() {
        java.util.Map<String, String> tags = objectTags(
                        "Line Chart Renderer",
                        "Render a **line chart** as a PNG image BLOB from a DuckDB relation. "
                                + "Pass the relation as the table argument and name the `x` and `y` "
                                + "columns; supply an optional `series` column to draw one line per "
                                + "distinct series value, and optional `title`, `width`, and "
                                + "`height`.\n\n"
                                + "Use it when you have an ordered or time-like x axis and want to "
                                + "show a trend or compare several series over a common x. The x "
                                + "axis is numeric when the x column is numeric (including DATE / "
                                + "TIMESTAMP / DECIMAL), otherwise it becomes a category axis. "
                                + "Rows where x or y is NULL are skipped. Returns a single "
                                + "`(png BLOB)` row holding the rendered PNG.",
                        "## chart_line\n\n"
                                + "Render a **line chart** from a query result to a PNG image BLOB.\n\n"
                                + "### Usage\n\n"
                                + "```sql\n"
                                + "SELECT png FROM chart.main.chart_line(\n"
                                + "  (SELECT x, y FROM points),\n"
                                + "  x := 'x', y := 'y', series := 'group', title := 'Trend');\n"
                                + "```\n\n"
                                + "### Notes\n\n"
                                + "- A numeric x column yields a numeric axis; a text x column "
                                + "yields a category axis.\n"
                                + "- With `series`, one line is drawn per distinct series value.\n"
                                + "- Rows with NULL x or y are ignored; an empty relation yields no "
                                + "rows.",
                        "line chart, line graph, trend, time series, series, plot, visualization, "
                                + "png, jfreechart, chart",
                        "ChartLineFunction.java");
        String examplesJson = exampleQueriesTag(
                "Render a single line over numeric x/y points and report the PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_line("
                        + "(SELECT * FROM (VALUES (1, 10), (2, 25), (3, 18), (4, 30)) AS t(x, y)), "
                        + "x := 'x', y := 'y', title := 'Trend')",
                "Render one line per region (series) of revenue over months and report its PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_line("
                        + "(SELECT * FROM (VALUES ('Jan', 100, 'East'), ('Feb', 140, 'East'), "
                        + "('Jan', 80, 'West'), ('Feb', 95, 'West')) AS t(month, revenue, region)), "
                        + "x := 'month', y := 'revenue', series := 'region', title := 'Revenue by region')");
        tags.put("vgi.example_queries", examplesJson);
        // VGI509: ship at least one guaranteed-runnable executable example.
        tags.put("vgi.executable_examples", examplesJson);
        // VGI411: assign a category from the schema's vgi.categories registry.
        tags.put("vgi.category", "trends-and-relationships");
        return baseMetadata(
                "Render a line chart from an input relation to a PNG image BLOB (JFreeChart). "
                        + "With a series column, one line per series value.",
                tags)
                .withCategories("chart", "visualization", "jfreechart");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                tableArg("input", 0,
                        "The input relation to plot. Its rows supply the data points; name "
                        + "the columns to use with the `x`, `y`, and optional `series` "
                        + "arguments."),
                namedArg("x", Schemas.UTF8, "x",
                        "Name of the column to use for the x axis. An ordered or time-like "
                        + "column yields a continuous axis; a label column yields a category "
                        + "axis."),
                namedArg("y", Schemas.UTF8, "y",
                        "Name of the column whose measure is plotted on the y axis for each "
                        + "x position."),
                namedArg("series", Schemas.UTF8, "",
                        "Optional name of a grouping column; one line is drawn per distinct "
                        + "value in this column. Empty (the default) draws a single line."),
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

    static boolean isNumericVector(FieldVector v) {
        return v instanceof Float8Vector || v instanceof Float4Vector
                || v instanceof IntVector || v instanceof BigIntVector
                || v instanceof SmallIntVector || v instanceof TinyIntVector
                || v instanceof DecimalVector || v instanceof Decimal256Vector
                || v instanceof DateDayVector || v instanceof DateMilliVector
                || v instanceof TimeStampVector;
    }

    private static final class State extends ChartState {
        private final String xCol;
        private final String yCol;
        private final String seriesCol;

        private final List<Object[]> xs = new ArrayList<>();   // each {Double} or {String}
        private final List<double[]> ys = new ArrayList<>();    // each {y}
        private final List<String> seriesKeys = new ArrayList<>();
        private Boolean xNumeric;

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
            if (xNumeric == null) xNumeric = isNumericVector(xv);

            int n = in.getRowCount();
            for (int r = 0; r < n; r++) {
                if (Columns.isNull(xv, r) || Columns.isNull(yv, r)) continue;
                double y = Columns.asNumeric(yv, r, yCol, "y");
                if (xNumeric) {
                    xs.add(new Object[]{Columns.asNumeric(xv, r, xCol, "x")});
                } else {
                    xs.add(new Object[]{Columns.asString(xv, r)});
                }
                ys.add(new double[]{y});
                seriesKeys.add(sv != null && !Columns.isNull(sv, r)
                        ? Columns.asString(sv, r) : "y");
            }
        }

        @Override protected JFreeChart render() {
            if (xs.isEmpty()) return null;
            return ChartRenderer.line(xs, ys, seriesKeys,
                    Boolean.TRUE.equals(xNumeric), title, xCol, yCol);
        }
    }
}
