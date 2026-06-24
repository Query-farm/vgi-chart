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
        return FunctionMetadata.describe(
                        "Render a line chart from an input relation to a PNG image BLOB (JFreeChart). "
                                + "With a series column, one line per series value.")
                .withCategories("chart", "visualization", "jfreechart")
                .withTag("vgi.columns_md", COLUMNS_MD)
                .withTag("vgi.example_queries", exampleQueriesTag(
                        "SELECT octet_length(png) AS bytes\n"
                                + "FROM chart.main.chart_line(\n"
                                + "  (SELECT * FROM (VALUES (1, 10), (2, 25), (3, 18), (4, 30)) AS t(x, y)),\n"
                                + "  x := 'x', y := 'y', title := 'Trend');",
                        "Render a single line over numeric x/y points and report the PNG byte size.",
                        "SELECT png\n"
                                + "FROM chart.main.chart_line(\n"
                                + "  (SELECT month, revenue, region FROM monthly_sales),\n"
                                + "  x := 'month', y := 'revenue', series := 'region',\n"
                                + "  title := 'Revenue by region', width := 1000, height := 600);",
                        "Render one line per region (series) of revenue over months as a 1000x600 PNG."));
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                ArgSpec.table("input", 0),
                ArgSpec.named("x", Schemas.UTF8, "x"),
                ArgSpec.named("y", Schemas.UTF8, "y"),
                ArgSpec.named("series", Schemas.UTF8, ""),
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
