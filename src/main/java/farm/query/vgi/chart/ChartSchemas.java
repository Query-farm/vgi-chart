package farm.query.vgi.chart;

import farm.query.vgi.types.Schemas;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.util.List;
import java.util.Map;

/** Shared Arrow output schema for the chart table-in-out functions. */
public final class ChartSchemas {

    private ChartSchemas() {}

    /** Every chart function emits a single BLOB row carrying the rendered PNG. */
    public static final Schema PNG_SCHEMA = new Schema(List.of(
            commented("png", Schemas.BINARY, "The rendered chart as a PNG image BLOB.")));

    /**
     * Schema of the browsable {@code chart_types} reference table — one row per
     * chart function so an agent can discover the available chart types and the
     * column arguments each takes before calling one.
     */
    public static final Schema CHART_TYPES_SCHEMA = new Schema(List.of(
            commentedNotNull("chart_type", Schemas.UTF8,
                    "The chart function's SQL name, e.g. chart_line."),
            commentedNotNull("category", Schemas.UTF8,
                    "The chart's category in the schema's category registry."),
            commentedNotNull("summary", Schemas.UTF8,
                    "One-line description of what the chart visualizes."),
            commentedNotNull("column_arguments", Schemas.UTF8,
                    "The named column arguments that select the data to plot, "
                    + "in call order.")));

    static Field commented(String name, ArrowType type, String comment) {
        return new Field(name, new FieldType(true, type, null, Map.of("comment", comment)), null);
    }

    /**
     * Like {@link #commented} but marks the column NOT NULL. The chart_types
     * reference table is fully populated static data, so every column is
     * non-nullable (VGI804).
     */
    static Field commentedNotNull(String name, ArrowType type, String comment) {
        return new Field(name, new FieldType(false, type, null, Map.of("comment", comment)), null);
    }

    static void setUtf8(VectorSchemaRoot root, String col, int row, String val) {
        ((VarCharVector) root.getVector(col)).setSafe(row, new Text(val));
    }
}
