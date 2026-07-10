package farm.query.vgi.chart;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.FunctionMetadata;
import org.apache.arrow.vector.types.pojo.ArrowType;
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

import java.util.LinkedHashMap;
import java.util.Map;

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
     * The (static) returned result schema, shared by every chart function — each
     * emits a single {@code (png BLOB)} row. Advertised via the structured
     * {@code vgi.result_columns_schema} function tag (VGI307/VGI321): a JSON array
     * of {@code {name, type, description}} objects, one per returned column.
     */
    protected static final String RESULT_COLUMNS_SCHEMA =
            "[{\"name\":\"png\",\"type\":\"BLOB\",\"description\":"
            + "\"The rendered chart as a PNG image (raw bytes), ready to write to a "
            + ".png file with DuckDB's COPY, embed in HTML, or hand to an image viewer.\"}]";

    /**
     * Encode a comma-separated keyword list as a JSON array of strings, the form
     * {@code vgi.keywords} requires (VGI138). Each keyword is trimmed; blanks are
     * dropped. E.g. {@code "a, b"} becomes {@code ["a","b"]}.
     */
    static String keywordsJson(String csv) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String kw : csv.split(",")) {
            String k = kw.trim();
            if (k.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append(jsonString(k));
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * Build the standard per-object discovery/description tags every chart
     * function carries: {@code vgi.title} (VGI124), {@code vgi.doc_llm} (VGI112),
     * {@code vgi.doc_md} (VGI113), {@code vgi.keywords} (VGI126, as a JSON array
     * per VGI138), and {@code vgi.result_columns_schema} (VGI307). The title MUST NOT
     * normalize-equal the machine name (VGI125), so each caller passes a
     * multi-word display name. Per-object {@code vgi.source_url} is intentionally
     * NOT set (VGI139): the source URL lives only on the catalog object.
     *
     * @param fileName the source file (unused for tags; kept for caller context)
     */
    protected static Map<String, String> objectTags(
            String title, String docLlm, String docMd, String keywords, String fileName) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("vgi.title", title);
        t.put("vgi.doc_llm", docLlm);
        t.put("vgi.doc_md", docMd);
        t.put("vgi.keywords", keywordsJson(keywords));
        t.put("vgi.result_columns_schema", RESULT_COLUMNS_SCHEMA);
        return t;
    }

    /**
    /**
     * Convenience: produce a base {@link FunctionMetadata} carrying the standard
     * per-object tags, so subclasses just add categories. Examples are carried by
     * the {@code vgi.example_queries} tag (the linter does not surface a
     * table-in-out function's native {@code Meta.examples}).
     */
    protected static FunctionMetadata baseMetadata(String description, Map<String, String> tags) {
        return FunctionMetadata.describe(description).withTags(tags);
    }

    /**
     * Encode examples as a JSON array of {@code {"description","sql"}} objects for
     * the {@code vgi.example_queries} / {@code vgi.executable_examples} tags. Each
     * SQL is self-contained and catalog-qualified. {@code pairs} is
     * [desc0, sql0, desc1, sql1, ...]; {@code expected_result} is omitted.
     */
    protected static String exampleQueriesTag(String... pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"description\":").append(jsonString(pairs[i]))
              .append(",\"sql\":").append(jsonString(pairs[i + 1])).append('}');
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

    /**
     * Build a named (keyword) argument carrying a per-argument {@code doc}
     * description (VGI312). The vgi {@link ArgSpec#named} factory leaves
     * {@code doc} empty, so we go through the canonical constructor to attach it.
     * Mirrors {@code named}: {@code position = -1}, no const, has a default value.
     *
     * @param name the keyword argument name (used as {@code name := value} in SQL)
     * @param type the Arrow type the argument is coerced to
     * @param defaultValue the SQL default applied when the argument is omitted
     * @param doc a human/LLM-facing description of what the argument controls
     */
    protected static ArgSpec namedArg(String name, ArrowType type, String defaultValue, String doc) {
        return new ArgSpec(
                name, -1, type, doc,
                /* isConst */ false, /* hasDefault */ true, defaultValue,
                java.util.List.of(), /* varargs */ false, /* anyType */ false,
                /* tableInput */ false);
    }

    /**
     * Build the table-valued input argument carrying a per-argument {@code doc}
     * description (VGI312). Mirrors the vgi {@link ArgSpec#table} factory (Null
     * Arrow type, {@code tableInput = true}) but attaches a documentation string.
     *
     * @param name the argument name for the input relation
     * @param position the positional index of the table argument (0 = first)
     * @param doc a human/LLM-facing description of the relation to be charted
     */
    protected static ArgSpec tableArg(String name, int position, String doc) {
        return new ArgSpec(
                name, position, new ArrowType.Null(), doc,
                /* isConst */ false, /* hasDefault */ false, "",
                java.util.List.of(), /* varargs */ false, /* anyType */ false,
                /* tableInput */ true);
    }

    /**
     * Standard {@code width} named arg (pixels, default 800) shared by every chart
     * type, with its per-argument doc (VGI312).
     */
    protected static ArgSpec widthArg() {
        return namedArg("width", farm.query.vgi.types.Schemas.INT64, "800",
                "Width of the rendered PNG image in pixels (default 800). Larger "
                + "values produce a higher-resolution chart.");
    }

    /**
     * Standard {@code height} named arg (pixels, default 600) shared by every chart
     * type, with its per-argument doc (VGI312).
     */
    protected static ArgSpec heightArg() {
        return namedArg("height", farm.query.vgi.types.Schemas.INT64, "600",
                "Height of the rendered PNG image in pixels (default 600). Larger "
                + "values produce a higher-resolution chart.");
    }

    /**
     * Standard {@code title} named arg (default empty) shared by every chart type,
     * with its per-argument doc (VGI312).
     */
    protected static ArgSpec titleArg() {
        return namedArg("title", farm.query.vgi.types.Schemas.UTF8, "",
                "Optional chart title drawn above the plot. Defaults to empty (no "
                + "title).");
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
