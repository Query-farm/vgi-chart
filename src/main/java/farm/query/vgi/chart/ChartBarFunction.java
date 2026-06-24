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
        return FunctionMetadata.describe(
                        "Render a bar chart from an input relation to a PNG image BLOB (JFreeChart). "
                                + "With a series column, bars are grouped per series value.")
                .withCategories("chart", "visualization", "jfreechart")
                .withTag("vgi.columns_md", COLUMNS_MD)
                .withTag("vgi.example_queries", exampleQueriesTag(
                        "SELECT octet_length(png) AS bytes\n"
                                + "FROM chart.main.chart_bar(\n"
                                + "  (SELECT * FROM (VALUES ('A', 30), ('B', 45), ('C', 12)) AS t(category, value)),\n"
                                + "  category := 'category', value := 'value', title := 'Counts');",
                        "Render one bar per category and report the PNG byte size.",
                        "SELECT png\n"
                                + "FROM chart.main.chart_bar(\n"
                                + "  (SELECT quarter, units, product FROM sales),\n"
                                + "  category := 'quarter', value := 'units', series := 'product',\n"
                                + "  title := 'Units per quarter by product');",
                        "Render grouped bars (one group per product series) of units by quarter."));
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                ArgSpec.table("input", 0),
                ArgSpec.named("category", Schemas.UTF8, "category"),
                ArgSpec.named("value", Schemas.UTF8, "value"),
                ArgSpec.named("series", Schemas.UTF8, ""),
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
