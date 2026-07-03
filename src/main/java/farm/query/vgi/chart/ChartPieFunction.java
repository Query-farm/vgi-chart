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
        java.util.Map<String, String> tags = objectTags(
                        "Pie Chart Renderer",
                        "Render a **pie chart** as a PNG image BLOB from a DuckDB relation. Name "
                                + "the `label` column (slice names) and the numeric `value` column "
                                + "(slice sizes); supply optional `title`, `width`, and `height`.\n\n"
                                + "Use it to show the composition of a whole — market share, budget "
                                + "split, or category proportions. There is one slice per distinct "
                                + "label, and rows that share a label are summed into a single "
                                + "slice. Rows with a NULL label or value are skipped. Returns a "
                                + "single `(png BLOB)` row holding the rendered PNG.",
                        "## chart_pie\n\n"
                                + "Render a **pie chart** from a query result to a PNG image BLOB.\n\n"
                                + "### Usage\n\n"
                                + "```sql\n"
                                + "SELECT png FROM chart.main.chart_pie(\n"
                                + "  (SELECT label, value FROM shares),\n"
                                + "  label := 'label', value := 'value', title := 'Share');\n"
                                + "```\n\n"
                                + "### Notes\n\n"
                                + "- One slice per distinct label; duplicate labels are summed.\n"
                                + "- `value` must be numeric; NULL label/value rows are ignored.",
                        "pie chart, donut, proportion, share, composition, percentage, slices, "
                                + "png, jfreechart, chart, visualization",
                        "ChartPieFunction.java");
        tags.put("vgi.example_queries", exampleQueriesTag(
                "Render a pie with one slice per label and report the PNG byte size.",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_pie("
                        + "(SELECT * FROM (VALUES ('Chrome', 65), ('Safari', 19), ('Firefox', 16)) AS t(label, value)), "
                        + "label := 'label', value := 'value', title := 'Browser share')",
                "Render spend per category as a 700x700 pie (duplicate categories are summed).",
                "SELECT octet_length(png) AS bytes FROM chart.main.chart_pie("
                        + "(SELECT * FROM (VALUES ('Rent', 1200), ('Food', 450), ('Rent', 100), "
                        + "('Travel', 300)) AS t(category, amount)), "
                        + "label := 'category', value := 'amount', "
                        + "title := 'Spend by category', width := 700, height := 700)"));
        // VGI411: assign a category from the schema's vgi.categories registry.
        tags.put("vgi.category", "categorical-comparisons");
        return baseMetadata(
                "Render a pie chart from an input relation to a PNG image BLOB (JFreeChart). "
                        + "One slice per distinct label; duplicate labels are summed.",
                tags)
                .withCategories("chart", "visualization", "jfreechart");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                tableArg("input", 0,
                        "The input relation to plot. Its rows supply the pie slices; name "
                        + "the columns to use with the `label` and `value` arguments."),
                namedArg("label", Schemas.UTF8, "label",
                        "Name of the column holding each slice's label. Rows that share a "
                        + "label are summed into a single slice."),
                namedArg("value", Schemas.UTF8, "value",
                        "Name of the column giving each slice's size (summed per distinct "
                        + "label)."),
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
