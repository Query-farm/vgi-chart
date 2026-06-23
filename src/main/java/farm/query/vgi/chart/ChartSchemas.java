package farm.query.vgi.chart;

import farm.query.vgi.types.Schemas;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.util.List;
import java.util.Map;

/** Shared Arrow output schema for the chart table-in-out functions. */
public final class ChartSchemas {

    private ChartSchemas() {}

    /** Every chart function emits a single BLOB row carrying the rendered PNG. */
    public static final Schema PNG_SCHEMA = new Schema(List.of(
            commented("png", Schemas.BINARY, "The rendered chart as a PNG image BLOB.")));

    static Field commented(String name, ArrowType type, String comment) {
        return new Field(name, new FieldType(true, type, null, Map.of("comment", comment)), null);
    }
}
