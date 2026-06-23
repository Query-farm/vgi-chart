package farm.query.vgi.chart;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders each chart type from a small relation and asserts the BLOB starts with
 * the PNG magic bytes and decodes via ImageIO to the requested width×height.
 * Rendering is not byte-stable across platforms, so we assert PNG validity +
 * dimensions, not exact bytes.
 */
class ChartFunctionsTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private static void assertPng(byte[] png, int w, int h) throws Exception {
        assertNotNull(png, "expected a PNG BLOB");
        assertTrue(png.length > 100, "PNG should be non-trivial, got " + png.length + " bytes");
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertEquals(PNG_MAGIC[i], png[i], "byte " + i + " of PNG signature");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "ImageIO must decode the PNG");
        assertEquals(w, img.getWidth(), "PNG width");
        assertEquals(h, img.getHeight(), "PNG height");
    }

    @Test void lineChartNumericX() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.f64()),
                TestSupport.f("y", TestSupport.f64())));
        byte[] png = TestSupport.run(new ChartLineFunction(), in,
                Map.of("x", "x", "y", "y", "width", 640L, "height", 480L), 5,
                (root, rows) -> {
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setF64(root, "x", i, i);
                        TestSupport.setF64(root, "y", i, i * i);
                    }
                });
        assertPng(png, 640, 480);
    }

    @Test void lineChartCategoryX() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.utf8()),
                TestSupport.f("y", TestSupport.f64())));
        byte[] png = TestSupport.run(new ChartLineFunction(), in,
                Map.of("x", "x", "y", "y"), 3,
                (root, rows) -> {
                    String[] cats = {"jan", "feb", "mar"};
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setUtf8(root, "x", i, cats[i]);
                        TestSupport.setF64(root, "y", i, i + 1);
                    }
                });
        assertPng(png, 800, 600);
    }

    @Test void lineChartGroupedBySeries() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.f64()),
                TestSupport.f("y", TestSupport.f64()),
                TestSupport.f("g", TestSupport.utf8())));
        byte[] png = TestSupport.run(new ChartLineFunction(), in,
                Map.of("x", "x", "y", "y", "series", "g", "title", "grouped"), 6,
                (root, rows) -> {
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setF64(root, "x", i, i % 3);
                        TestSupport.setF64(root, "y", i, i);
                        TestSupport.setUtf8(root, "g", i, i < 3 ? "a" : "b");
                    }
                });
        assertPng(png, 800, 600);
    }

    @Test void barChart() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("category", TestSupport.utf8()),
                TestSupport.f("value", TestSupport.i64())));
        byte[] png = TestSupport.run(new ChartBarFunction(), in,
                Map.of("category", "category", "value", "value", "width", 500L, "height", 400L), 4,
                (root, rows) -> {
                    String[] cats = {"a", "b", "c", "d"};
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setUtf8(root, "category", i, cats[i]);
                        TestSupport.setI64(root, "value", i, (i + 1) * 10L);
                    }
                });
        assertPng(png, 500, 400);
    }

    @Test void scatterChart() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.f64()),
                TestSupport.f("y", TestSupport.f64())));
        byte[] png = TestSupport.run(new ChartScatterFunction(), in,
                Map.of("x", "x", "y", "y"), 10,
                (root, rows) -> {
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setF64(root, "x", i, i * 1.5);
                        TestSupport.setF64(root, "y", i, (i % 4) * 2.0);
                    }
                });
        assertPng(png, 800, 600);
    }

    @Test void pieChart() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("label", TestSupport.utf8()),
                TestSupport.f("value", TestSupport.f64())));
        byte[] png = TestSupport.run(new ChartPieFunction(), in,
                Map.of("label", "label", "value", "value"), 3,
                (root, rows) -> {
                    String[] labels = {"red", "green", "blue"};
                    double[] vals = {30, 45, 25};
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setUtf8(root, "label", i, labels[i]);
                        TestSupport.setF64(root, "value", i, vals[i]);
                    }
                });
        assertPng(png, 800, 600);
    }

    @Test void histogramChart() throws Exception {
        Schema in = new Schema(List.of(TestSupport.f("value", TestSupport.f64())));
        byte[] png = TestSupport.run(new ChartHistogramFunction(), in,
                Map.of("value", "value", "bins", 10L), 50,
                (root, rows) -> {
                    for (int i = 0; i < rows; i++) {
                        TestSupport.setF64(root, "value", i, (i * 7) % 23);
                    }
                });
        assertPng(png, 800, 600);
    }

    @Test void emptyRelationProducesBlankPng() throws Exception {
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.f64()),
                TestSupport.f("y", TestSupport.f64())));
        // rows == 0: a single zero-row batch (the terminal flush). Must still
        // yield a valid (blank) PNG of the requested dimensions, never crash.
        byte[] png = TestSupport.run(new ChartLineFunction(), in,
                Map.of("x", "x", "y", "y", "width", 320L, "height", 240L), 0, null);
        assertPng(png, 320, 240);
    }

    @Test void nonNumericWhereNumericExpectedErrors() {
        // y is a VARCHAR but chart_line needs a numeric y -> clear, query-failing error.
        Schema in = new Schema(List.of(
                TestSupport.f("x", TestSupport.f64()),
                TestSupport.f("y", TestSupport.utf8())));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                TestSupport.run(new ChartLineFunction(), in,
                        Map.of("x", "x", "y", "y"), 2,
                        (root, rows) -> {
                            for (int i = 0; i < rows; i++) {
                                TestSupport.setF64(root, "x", i, i);
                                TestSupport.setUtf8(root, "y", i, "not-a-number");
                            }
                        }));
        assertTrue(ex.getMessage().contains("numeric"),
                "error should explain a numeric column is required: " + ex.getMessage());
    }

    @Test void missingColumnErrors() {
        Schema in = new Schema(List.of(TestSupport.f("a", TestSupport.f64())));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                TestSupport.run(new ChartLineFunction(), in,
                        Map.of("x", "x", "y", "y"), 1,
                        (root, rows) -> TestSupport.setF64(root, "a", 0, 1.0)));
        assertTrue(ex.getMessage().contains("not found"),
                "error should name the missing column: " + ex.getMessage());
    }
}
