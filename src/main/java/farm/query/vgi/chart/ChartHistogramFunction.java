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
 * {@code chart_histogram(TABLE, value := 'value', bins := 20, title := NULL,
 * width := 800, height := 600) -> (png BLOB)} — a histogram of a numeric column
 * over {@code bins} equal-width buckets.
 */
public final class ChartHistogramFunction extends ChartFunction {

    @Override public String name() { return "chart_histogram"; }

    @Override public FunctionMetadata metadata() {
        java.util.Map<String, String> tags = objectTags(
                        "Histogram Chart Renderer",
                        "Render a **histogram** as a PNG image BLOB from a DuckDB relation. Name "
                                + "the numeric `value` column and optionally set `bins` (the number "
                                + "of equal-width buckets, default 20), plus optional `title`, "
                                + "`width`, and `height`.\n\n"
                                + "Use it to visualize the distribution of a single numeric variable "
                                + "— latencies, prices, measurement spreads — by counting how many "
                                + "values fall into each bucket between the observed min and max. "
                                + "Rows with a NULL value are skipped; `bins` <= 0 falls back to 20. "
                                + "Returns a single `(png BLOB)` row holding the rendered PNG.",
                        "## chart_histogram\n\n"
                                + "Render a **histogram** of a numeric column to a PNG image BLOB.\n\n"
                                + "### Usage\n\n"
                                + "```sql\n"
                                + "SELECT png FROM chart.main.chart_histogram(\n"
                                + "  (SELECT value FROM samples),\n"
                                + "  value := 'value', bins := 20, title := 'Distribution');\n"
                                + "```\n\n"
                                + "### Notes\n\n"
                                + "- Values are binned into `bins` equal-width buckets across the "
                                + "observed range.\n"
                                + "- `value` must be numeric; NULL values are ignored and "
                                + "`bins <= 0` defaults to 20.",
                        "histogram, distribution, frequency, bins, buckets, density, spread, "
                                + "png, jfreechart, chart, visualization",
                        "ChartHistogramFunction.java");
        tags.put("vgi.example_queries", exampleQueriesTag(
                "Bin five values into 5 equal-width buckets and report the PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_histogram("
                        + "(SELECT * FROM (VALUES (1.0), (1.5), (2.0), (2.0), (3.5)) AS t(value)), "
                        + "value := 'value', bins := 5, title := 'Distribution')",
                "Render a 30-bucket histogram of request latency as a 900x500 PNG.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_histogram("
                        + "(SELECT * FROM (VALUES (12.0), (15.0), (15.0), (22.0), (30.0), (45.0), (9.0)) "
                        + "AS t(latency_ms)), value := 'latency_ms', bins := 30, "
                        + "title := 'Latency distribution', width := 900, height := 500)"));
        // VGI411: assign a category from the schema's vgi.categories registry.
        tags.put("vgi.category", "distributions");
        return baseMetadata(
                "Render a histogram of a numeric column to a PNG image BLOB (JFreeChart), "
                        + "binning the values into `bins` equal-width buckets.",
                tags)
                .withCategories("chart", "visualization", "jfreechart");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                tableArg("input", 0,
                        "The input relation to plot. Its rows supply the samples; name the "
                        + "column to bin with the `value` argument."),
                namedArg("value", Schemas.UTF8, "value",
                        "Name of the column whose value distribution is binned and counted."),
                namedArg("bins", Schemas.INT64, "20",
                        "Number of equal-width buckets to divide the observed value range "
                        + "into (default 20). Values of 0 or less fall back to 20."),
                titleArg(), widthArg(), heightArg());
    }

    @Override public TableInOutExchangeState createExchange(TableInOutInitParams params) {
        Arguments a = params.arguments();
        return new State(
                a.namedString("value", "value"),
                (int) a.namedLong("bins", 20),
                a.namedString("title", ""),
                (int) a.namedLong("width", 800),
                (int) a.namedLong("height", 600));
    }

    private static final class State extends ChartState {
        private final String valCol;
        private final int bins;
        private final List<Double> values = new ArrayList<>();

        State(String valCol, int bins, String title, int width, int height) {
            super(width, height, title);
            this.valCol = valCol;
            this.bins = bins <= 0 ? 20 : bins;
        }

        @Override protected void accumulate(VectorSchemaRoot in) {
            FieldVector vv = Columns.require(in, valCol, "value");
            int n = in.getRowCount();
            for (int r = 0; r < n; r++) {
                if (Columns.isNull(vv, r)) continue;
                values.add(Columns.asNumeric(vv, r, valCol, "value"));
            }
        }

        @Override protected JFreeChart render() {
            if (values.isEmpty()) return null;
            double[] arr = new double[values.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
            return ChartRenderer.histogram(arr, bins, title, valCol);
        }
    }
}
