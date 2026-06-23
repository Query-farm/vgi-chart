package farm.query.vgi.chart;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.Text;

/**
 * Helpers to resolve a named input column and coerce its cells to the numeric
 * (double) or text values the chart datasets need. A request for a numeric value
 * from a non-numeric column raises a clear, query-failing error.
 */
final class Columns {

    private Columns() {}

    /** Locate a column by name, failing with a clear error if it is absent. */
    static FieldVector require(VectorSchemaRoot root, String column, String role) {
        FieldVector v = root.getVector(column);
        if (v == null) {
            throw new IllegalArgumentException(
                    "chart: " + role + " column '" + column + "' not found in input relation; "
                            + "available columns: " + columnNames(root));
        }
        return v;
    }

    /** Optional column lookup — returns null if the name is null/blank or absent. */
    static FieldVector optional(VectorSchemaRoot root, String column) {
        if (column == null || column.isEmpty()) return null;
        return root.getVector(column);
    }

    static String columnNames(VectorSchemaRoot root) {
        StringBuilder sb = new StringBuilder("[");
        var fields = root.getSchema().getFields();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fields.get(i).getName());
        }
        return sb.append(']').toString();
    }

    /** True for null cells. */
    static boolean isNull(FieldVector v, int row) {
        return v.isNull(row);
    }

    /**
     * Coerce a numeric Arrow cell to a double. Date/timestamp columns map to
     * their epoch-day / epoch-milli numeric position so a time axis still plots.
     * A non-numeric column (e.g. VARCHAR) raises a clear error naming the column.
     */
    static double asNumeric(FieldVector v, int row, String column, String role) {
        if (v instanceof Float8Vector d) return d.get(row);
        if (v instanceof Float4Vector f) return f.get(row);
        if (v instanceof IntVector iv) return iv.get(row);
        if (v instanceof BigIntVector bv) return bv.get(row);
        if (v instanceof SmallIntVector sv) return sv.get(row);
        if (v instanceof TinyIntVector tv) return tv.get(row);
        if (v instanceof UInt1Vector uv) return uv.get(row) & 0xFFL;
        if (v instanceof UInt2Vector uv) return uv.get(row) & 0xFFFFL;
        if (v instanceof UInt4Vector uv) return uv.get(row) & 0xFFFFFFFFL;
        if (v instanceof UInt8Vector uv) return uv.get(row);
        if (v instanceof DecimalVector dec) return dec.getObject(row).doubleValue();
        if (v instanceof Decimal256Vector dec) return dec.getObject(row).doubleValue();
        if (v instanceof BitVector bit) return bit.get(row);
        if (v instanceof DateDayVector dd) return dd.get(row);
        if (v instanceof DateMilliVector dm) return dm.get(row);
        if (v instanceof TimeStampVector ts) return ts.get(row);
        throw new IllegalArgumentException(
                "chart: " + role + " column '" + column + "' is "
                        + v.getField().getType()
                        + " but a numeric column is required for this chart axis");
    }

    /** Read any cell as a display string (used for category / label / series). */
    static String asString(FieldVector v, int row) {
        Object o = v.getObject(row);
        if (o == null) return "";
        if (o instanceof Text t) return t.toString();
        if (v instanceof VarCharVector s) {
            Object so = s.getObject(row);
            return so == null ? "" : so.toString();
        }
        // Normalise whole-number doubles to drop a trailing ".0" for nicer axes.
        if (o instanceof Double dv && dv == Math.floor(dv) && !dv.isInfinite()) {
            return String.valueOf(dv.longValue());
        }
        return o.toString();
    }
}
