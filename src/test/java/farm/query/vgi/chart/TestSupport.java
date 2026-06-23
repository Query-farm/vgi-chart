package farm.query.vgi.chart;

import farm.query.vgi.function.Arguments;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-process table-in-out driver mirroring vgi-poi's WriteXlsxTest.runWrite:
 * feed one (or more) input batches, return the emitted PNG BLOB.
 */
final class TestSupport {

    private TestSupport() {}

    static Field f(String name, ArrowType type) {
        return new Field(name, new FieldType(true, type, null), null);
    }

    static ArrowType f64() {
        return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }

    static ArrowType i64() {
        return new ArrowType.Int(64, true);
    }

    static ArrowType i32() {
        return new ArrowType.Int(32, true);
    }

    static ArrowType utf8() {
        return new ArrowType.Utf8();
    }

    /** A single column builder used by the test fixtures. */
    interface Filler {
        void fill(VectorSchemaRoot root, int rows);
    }

    /**
     * Run a chart function over one data batch of {@code rows} rows and return the
     * emitted PNG bytes (the last emitted BLOB row). A {@code rows == 0} call still
     * sends one zero-row batch (the terminal flush) so empty-relation behaviour is
     * exercised.
     */
    static byte[] run(ChartFunction fn, Schema inputSchema, Map<String, Object> named,
                      int rows, Filler filler) {
        Arguments args = new Arguments(List.of(), named, List.of());
        TableInOutInitParams init = new TableInOutInitParams(
                fn.name(), args, inputSchema, ChartSchemas.PNG_SCHEMA,
                Map.of(), Allocators.root(), null);
        TableInOutExchangeState state = fn.createExchange(init);
        OutputCollector collector = new OutputCollector(ChartSchemas.PNG_SCHEMA, "test", true);

        try (VectorSchemaRoot data = VectorSchemaRoot.create(inputSchema, Allocators.root())) {
            data.allocateNew();
            if (rows > 0 && filler != null) filler.fill(data, rows);
            data.setRowCount(rows);
            try (AnnotatedBatch b = new AnnotatedBatch(data, Map.of())) {
                state.onInputBatch(b, collector, null);
            }
        }

        byte[] result = null;
        for (OutputCollector.Entry e : collector.entries()) {
            if (e.isData()) {
                VectorSchemaRoot r = e.root();
                if (r.getRowCount() > 0) {
                    VarBinaryVector v = (VarBinaryVector) r.getVector("png");
                    result = v.get(0);
                }
                r.close();
            }
        }
        return result;
    }

    // ---- vector setters ----------------------------------------------------

    static void setF64(VectorSchemaRoot root, String col, int row, double val) {
        ((Float8Vector) root.getVector(col)).setSafe(row, val);
    }

    static void setI64(VectorSchemaRoot root, String col, int row, long val) {
        ((BigIntVector) root.getVector(col)).setSafe(row, val);
    }

    static void setI32(VectorSchemaRoot root, String col, int row, int val) {
        ((IntVector) root.getVector(col)).setSafe(row, val);
    }

    static void setUtf8(VectorSchemaRoot root, String col, int row, String val) {
        ((VarCharVector) root.getVector(col)).setSafe(row, new Text(val));
    }
}
