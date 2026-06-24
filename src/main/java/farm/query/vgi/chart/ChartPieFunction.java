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
 * {@code chart_pie(TABLE, label := 'label', value := 'value', title := NULL,
 * width := 800, height := 600) -> (png BLOB)} — a pie chart, one slice per
 * distinct label (duplicate labels are summed).
 */
public final class ChartPieFunction extends ChartFunction {

    @Override public String name() { return "chart_pie"; }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(
                        "Render a pie chart from an input relation to a PNG image BLOB (JFreeChart). "
                                + "One slice per distinct label; duplicate labels are summed.")
                .withCategories("chart", "visualization", "jfreechart")
                .withTag("vgi.columns_md", COLUMNS_MD)
                .withTag("vgi.example_queries", exampleQueriesTag(
                        "SELECT octet_length(png) AS bytes\n"
                                + "FROM chart.main.chart_pie(\n"
                                + "  (SELECT * FROM (VALUES ('Chrome', 65), ('Safari', 19), ('Firefox', 16)) AS t(label, value)),\n"
                                + "  label := 'label', value := 'value', title := 'Browser share');",
                        "Render a pie with one slice per label and report the PNG byte size.",
                        "SELECT png\n"
                                + "FROM chart.main.chart_pie(\n"
                                + "  (SELECT category, amount FROM expenses),\n"
                                + "  label := 'category', value := 'amount',\n"
                                + "  title := 'Spend by category', width := 700, height := 700);",
                        "Render spend per category as a 700x700 pie (duplicate categories are summed)."));
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                ArgSpec.table("input", 0),
                ArgSpec.named("label", Schemas.UTF8, "label"),
                ArgSpec.named("value", Schemas.UTF8, "value"),
                titleArg(), widthArg(), heightArg());
    }

    @Override public TableInOutExchangeState createExchange(TableInOutInitParams params) {
        Arguments a = params.arguments();
        return new State(
                a.namedString("label", "label"),
                a.namedString("value", "value"),
                a.namedString("title", ""),
                (int) a.namedLong("width", 800),
                (int) a.namedLong("height", 600));
    }

    private static final class State extends ChartState {
        private final String labelCol;
        private final String valCol;

        private final List<String> labels = new ArrayList<>();
        private final List<Double> values = new ArrayList<>();

        State(String labelCol, String valCol, String title, int width, int height) {
            super(width, height, title);
            this.labelCol = labelCol;
            this.valCol = valCol;
        }

        @Override protected void accumulate(VectorSchemaRoot in) {
            FieldVector lv = Columns.require(in, labelCol, "label");
            FieldVector vv = Columns.require(in, valCol, "value");

            int n = in.getRowCount();
            for (int r = 0; r < n; r++) {
                if (Columns.isNull(lv, r) || Columns.isNull(vv, r)) continue;
                labels.add(Columns.asString(lv, r));
                values.add(Columns.asNumeric(vv, r, valCol, "value"));
            }
        }

        @Override protected JFreeChart render() {
            if (labels.isEmpty()) return null;
            return ChartRenderer.pie(labels, values, title);
        }
    }
}
