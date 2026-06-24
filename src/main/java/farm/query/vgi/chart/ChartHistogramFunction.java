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
        return FunctionMetadata.describe(
                        "Render a histogram of a numeric column to a PNG image BLOB (JFreeChart), "
                                + "binning the values into `bins` equal-width buckets.")
                .withCategories("chart", "visualization", "jfreechart")
                .withTag("vgi.columns_md", COLUMNS_MD)
                .withTag("vgi.example_queries", exampleQueriesTag(
                        "SELECT octet_length(png) AS bytes\n"
                                + "FROM chart.main.chart_histogram(\n"
                                + "  (SELECT * FROM (VALUES (1.0), (1.5), (2.0), (2.0), (3.5)) AS t(value)),\n"
                                + "  value := 'value', bins := 5, title := 'Distribution');",
                        "Bin five values into 5 equal-width buckets and report the PNG byte size.",
                        "SELECT png\n"
                                + "FROM chart.main.chart_histogram(\n"
                                + "  (SELECT latency_ms FROM requests),\n"
                                + "  value := 'latency_ms', bins := 30,\n"
                                + "  title := 'Latency distribution', width := 900, height := 500);",
                        "Render a 30-bucket histogram of request latency as a 900x500 PNG."));
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                ArgSpec.table("input", 0),
                ArgSpec.named("value", Schemas.UTF8, "value"),
                ArgSpec.named("bins", Schemas.INT64, "20"),
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
