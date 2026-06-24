package farm.query.vgi.chart;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.tableinout.TableInOutBindParams;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutFunction;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.jfree.chart.JFreeChart;

/**
 * Base for the buffering chart TABLE-IN-OUT functions. Each consumes the streamed
 * input relation and emits a single {@code (png BLOB)} row containing the chart
 * rendered from the rows seen so far.
 *
 * <p><b>Streaming model (mirrors vgi-poi's write_xlsx).</b> The VGI table-in-out
 * driver calls {@link TableInOutExchangeState#onInputBatch} once per input batch
 * and then stops when the input stream closes — there is no separate finalize
 * callback and (as DuckDB's vgi extension delivers it) no guaranteed terminal
 * empty batch. So each subclass buffers every batch's rows and re-renders the
 * complete chart after each input batch. For the common single-batch input
 * (≤~2048 rows) that is exactly one PNG row of the whole relation; an input large
 * enough to span multiple Arrow batches yields one row per batch, the last of
 * which is the complete chart (a documented simplification). A zero-row terminal
 * batch (some plans send one) flushes the chart and finishes. A NULL / empty
 * relation renders a blank chart PNG.
 */
public abstract class ChartFunction implements TableInOutFunction {

    /**
     * Markdown table describing the (static) returned columns, shared by every
     * chart function — each emits a single {@code (png BLOB)} row. Advertised via
     * the {@code vgi.columns_md} function tag.
     */
    protected static final String COLUMNS_MD =
            "| column | type | description |\n"
            + "|---|---|---|\n"
            + "| `png` | BLOB | The rendered chart as a PNG image. |";

    /**
     * Encode example queries as the reserved {@code vgi.example_queries} tag — a
     * JSON array of {@code {"sql","description"}} objects. The vgi extension does
     * not surface a table-in-out function's native {@code Meta.examples} into
     * {@code duckdb_functions().examples}, so this tag is the carrier the metadata
     * linter (and agents) read. {@code pairs} is [sql0, desc0, sql1, desc1, ...].
     */
    protected static String exampleQueriesTag(String... pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"sql\":").append(jsonString(pairs[i]))
              .append(",\"description\":").append(jsonString(pairs[i + 1])).append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Standard width/height named args shared by every chart type. */
    protected static ArgSpec widthArg() {
        return ArgSpec.named("width", farm.query.vgi.types.Schemas.INT64, "800");
    }

    protected static ArgSpec heightArg() {
        return ArgSpec.named("height", farm.query.vgi.types.Schemas.INT64, "600");
    }

    protected static ArgSpec titleArg() {
        return ArgSpec.named("title", farm.query.vgi.types.Schemas.UTF8, "");
    }

    @Override public BindResponse onBind(TableInOutBindParams p) {
        return BindResponse.forSchema(SchemaUtil.serializeSchema(ChartSchemas.PNG_SCHEMA));
    }

    /** Build the per-call accumulator that buffers rows and renders the chart. */
    @Override public abstract TableInOutExchangeState createExchange(TableInOutInitParams params);

    /**
     * Shared buffering exchange-state base: collects input batches, then after
     * each batch renders the chart-so-far and emits it as a single PNG row.
     */
    public abstract static class ChartState extends TableInOutExchangeState {
        protected final int width;
        protected final int height;
        protected final String title;
        private boolean sawAnyRow;

        protected ChartState(int width, int height, String title) {
            this.width = width <= 0 ? 800 : width;
            this.height = height <= 0 ? 600 : height;
            this.title = title == null ? "" : title;
        }

        /** Buffer one input batch's rows. Implemented per chart type. */
        protected abstract void accumulate(VectorSchemaRoot in);

        /**
         * Render the chart from everything buffered so far. Return {@code null}
         * to fall back to a blank chart (used for an empty relation).
         */
        protected abstract JFreeChart render();

        @Override
        public final void onInputBatch(AnnotatedBatch batch, OutputCollector out, CallContext ctx) {
            VectorSchemaRoot in = batch.root();
            int n = in.getRowCount();

            // A zero-row batch (some plans send one as a terminal flush signal):
            // emit the complete chart and finish.
            if (n == 0) {
                emit(out);
                out.finish();
                return;
            }

            sawAnyRow = true;
            accumulate(in);

            // No finalize callback / terminal empty batch is guaranteed, so emit
            // the chart built from all rows seen SO FAR after each input batch.
            // Single-batch input -> exactly one PNG of the whole relation.
            emit(out);
        }

        private void emit(OutputCollector out) {
            byte[] png;
            try {
                JFreeChart chart = sawAnyRow ? render() : null;
                png = chart != null
                        ? ChartRenderer.toPng(chart, width, height)
                        : ChartRenderer.blankPng(title, width, height);
            } catch (RuntimeException e) {
                // Coercion / build errors (e.g. non-numeric where numeric needed)
                // must fail the query with a clear message, not a worker crash.
                throw e;
            }

            VectorSchemaRoot root = VectorSchemaRoot.create(ChartSchemas.PNG_SCHEMA, Allocators.root());
            root.allocateNew();
            VarBinaryVector png_ = (VarBinaryVector) root.getVector("png");
            png_.setSafe(0, png);
            root.setRowCount(1);
            out.emit(root);
        }
    }
}
