package farm.query.vgi.chart;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.table.TableBindParams;
import farm.query.vgi.table.TableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.VectorSchemaRoot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code chart.main.chart_types()} — a parameterless reference table listing every
 * chart function this worker exposes, with its category, a one-line summary, and
 * the named column arguments it takes. It gives an agent a browsable catalog table
 * (also registered as {@code chart.main.chart_types}, no parentheses) so it can
 * discover the available chart types and their inputs before calling one, rather
 * than guessing arguments blind (VGI146/VGI311).
 */
public final class ChartTypesFunction implements TableFunction {

    /** One row per chart function: {name, category, summary, column arguments}. */
    static final String[][] ROWS = {
        {"chart_line", "trends-and-relationships",
            "Line chart of a measure over an ordered or time-like x axis; "
            + "one line per optional series.",
            "x, y, series"},
        {"chart_bar", "categorical-comparisons",
            "Vertical bar chart comparing a numeric measure across discrete "
            + "categories; optionally grouped by a series.",
            "category, value, series"},
        {"chart_pie", "categorical-comparisons",
            "Pie chart showing each label's share of a total.",
            "label, value"},
        {"chart_scatter", "trends-and-relationships",
            "Scatter plot of numeric x/y points to reveal correlation or "
            + "clusters; one point series per optional group.",
            "x, y, series"},
        {"chart_histogram", "distributions",
            "Histogram of a single numeric column binned into equal-width "
            + "buckets to show its distribution.",
            "value, bins"},
    };

    /** Structured static result schema for VGI307 (matches CHART_TYPES_SCHEMA). */
    static final String RESULT_COLUMNS_SCHEMA =
            "["
            + "{\"name\":\"chart_type\",\"type\":\"VARCHAR\",\"description\":"
            + "\"The chart function's SQL name, e.g. chart_line.\"},"
            + "{\"name\":\"category\",\"type\":\"VARCHAR\",\"description\":"
            + "\"The chart's category in the schema's category registry.\"},"
            + "{\"name\":\"summary\",\"type\":\"VARCHAR\",\"description\":"
            + "\"One-line description of what the chart visualizes.\"},"
            + "{\"name\":\"column_arguments\",\"type\":\"VARCHAR\",\"description\":"
            + "\"The named column arguments that select the data to plot, in call order.\"}"
            + "]";

    static final String EXAMPLE_QUERIES =
            "["
            + "{\"description\":\"List every chart type this worker offers with its "
            + "category and the column arguments it takes.\","
            + "\"sql\":\"SELECT chart_type, category, column_arguments "
            + "FROM chart.main.chart_types() ORDER BY chart_type\"},"
            + "{\"description\":\"Find the chart types that compare a measure across "
            + "discrete categories.\","
            + "\"sql\":\"SELECT chart_type, summary FROM chart.main.chart_types() "
            + "WHERE category = 'categorical-comparisons' ORDER BY chart_type\"}"
            + "]";

    static final String EXECUTABLE_EXAMPLES =
            "["
            + "{\"description\":\"List every chart type with its category, ordered by "
            + "name.\","
            + "\"sql\":\"SELECT chart_type, category FROM chart.main.chart_types() "
            + "ORDER BY chart_type\"}"
            + "]";

    @Override public String name() { return "chart_types"; }

    /** Per-object discovery/description tags shared by the function and its table. */
    static Map<String, String> tags() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.title", "Chart Type Catalog");
        t.put("vgi.doc_llm",
                "List every chart function this worker exposes — `chart_line`, "
                + "`chart_bar`, `chart_pie`, `chart_scatter`, and `chart_histogram` — "
                + "each with its category, a one-line summary of what it visualizes, and "
                + "the named column arguments it takes. Query it to discover which chart "
                + "type fits your data and what columns to name before calling a chart "
                + "function, or to drive a chart-type picker. Takes no arguments and "
                + "always returns one row per chart type.");
        t.put("vgi.doc_md",
                "## chart_types\n\n"
                + "A reference table with one row per chart function this worker "
                + "exposes, so you can discover the available chart types and their "
                + "inputs before calling one.\n\n"
                + "Each row gives the function's SQL name, its category, a one-line "
                + "summary of what it visualizes, and the named column arguments it "
                + "accepts. Takes no arguments and always returns rows.\n\n"
                + "See this object's example queries for complete, runnable calls.");
        t.put("vgi.keywords", ChartFunction.keywordsJson(
                "chart types, catalog, reference, discovery, list charts, available charts, "
                + "chart_line, chart_bar, chart_pie, chart_scatter, chart_histogram"));
        t.put("vgi.result_columns_schema", RESULT_COLUMNS_SCHEMA);
        // VGI123 classifying tags use BARE keys (not vgi.-namespaced).
        t.put("domain", "data-visualization");
        t.put("category", "charting");
        t.put("topic", "chart-rendering");
        // VGI409/VGI411: primary category from the schema's vgi.categories registry.
        t.put("vgi.category", "reference");
        t.put("vgi.example_queries", EXAMPLE_QUERIES);
        t.put("vgi.executable_examples", EXECUTABLE_EXAMPLES);
        return t;
    }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(
                        "List every chart function this worker exposes, with its category, a "
                        + "one-line summary, and the named column arguments it takes.")
                .withCategories("chart", "reference", "discovery")
                .withTags(tags());
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of();
    }

    @Override public BindResponse onBind(TableBindParams p) {
        return BindResponse.forSchema(SchemaUtil.serializeSchema(ChartSchemas.CHART_TYPES_SCHEMA));
    }

    @Override public long cardinality(TableBindParams p) {
        return ROWS.length;
    }

    @Override public TableProducerState createProducer(TableInitParams params) {
        return new State();
    }

    /** Emits the static chart-type rows once, then finishes. */
    public static final class State extends TableProducerState {
        public boolean done;

        public State() {}

        @Override public void produceTick(OutputCollector out, CallContext ctx) {
            if (done) { out.finish(); return; }
            done = true;

            VectorSchemaRoot root = VectorSchemaRoot.create(
                    ChartSchemas.CHART_TYPES_SCHEMA, Allocators.root());
            root.allocateNew();
            for (int i = 0; i < ROWS.length; i++) {
                ChartSchemas.setUtf8(root, "chart_type", i, ROWS[i][0]);
                ChartSchemas.setUtf8(root, "category", i, ROWS[i][1]);
                ChartSchemas.setUtf8(root, "summary", i, ROWS[i][2]);
                ChartSchemas.setUtf8(root, "column_arguments", i, ROWS[i][3]);
            }
            root.setRowCount(ROWS.length);
            out.emit(root);
            out.finish();
        }
    }
}
