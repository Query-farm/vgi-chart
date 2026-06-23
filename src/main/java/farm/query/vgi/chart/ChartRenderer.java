package farm.query.vgi.chart;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds JFreeChart charts from buffered relation rows and encodes them to PNG.
 *
 * <p>Headless rendering only ({@code java.awt.headless=true} is set by Main and
 * the build); never opens a display. Each builder produces a {@link JFreeChart}
 * that {@link #toPng} rasterizes to a width×height PNG byte[].
 */
final class ChartRenderer {

    private ChartRenderer() {}

    // ---- PNG encoding ------------------------------------------------------

    /** Rasterize a chart to a PNG byte[] of exactly width×height pixels. */
    static byte[] toPng(JFreeChart chart, int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        BufferedImage img = chart.createBufferedImage(w, h);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            // Should not happen for an in-memory PNG write; surface as an error.
            throw new IllegalStateException("chart: failed to encode PNG: " + e, e);
        }
    }

    /** A small blank chart used for an empty/NULL input relation. */
    static byte[] blankPng(String title, int width, int height) {
        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "x", "y", new XYSeriesCollection(), PlotOrientation.VERTICAL,
                false, false, false);
        return toPng(chart, width, height);
    }

    // ---- line --------------------------------------------------------------

    /**
     * Line chart. With a numeric x it uses an XY plot (one series per {@code series}
     * value, or a single "y" series); the points are sorted by x so the line is
     * monotonic. With a non-numeric x it falls back to a category line chart.
     */
    static JFreeChart line(List<Object[]> xs, List<double[]> ys, List<String> seriesKeys,
                           boolean xNumeric, String title, String xLabel, String yLabel) {
        if (xNumeric) {
            XYSeriesCollection ds = new XYSeriesCollection();
            Map<String, XYSeries> byKey = new LinkedHashMap<>();
            for (int i = 0; i < xs.size(); i++) {
                String key = seriesKeys.get(i);
                XYSeries s = byKey.computeIfAbsent(key, k -> new XYSeries(k, true, true));
                s.add(((Double) xs.get(i)[0]).doubleValue(), ys.get(i)[0]);
            }
            for (XYSeries s : byKey.values()) ds.addSeries(s);
            JFreeChart chart = ChartFactory.createXYLineChart(
                    title, xLabel, yLabel, ds, PlotOrientation.VERTICAL,
                    byKey.size() > 1, true, false);
            XYPlot plot = chart.getXYPlot();
            plot.setRenderer(new XYLineAndShapeRenderer(true, true));
            return chart;
        }
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (int i = 0; i < xs.size(); i++) {
            ds.addValue(ys.get(i)[0], seriesKeys.get(i), (String) xs.get(i)[0]);
        }
        return ChartFactory.createLineChart(
                title, xLabel, yLabel, ds, PlotOrientation.VERTICAL,
                distinct(seriesKeys) > 1, true, false);
    }

    // ---- bar ---------------------------------------------------------------

    static JFreeChart bar(List<String> categories, List<Double> values, List<String> seriesKeys,
                          String title, String categoryLabel, String valueLabel) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (int i = 0; i < categories.size(); i++) {
            ds.addValue(values.get(i), seriesKeys.get(i), categories.get(i));
        }
        return ChartFactory.createBarChart(
                title, categoryLabel, valueLabel, ds, PlotOrientation.VERTICAL,
                distinct(seriesKeys) > 1, true, false);
    }

    // ---- scatter -----------------------------------------------------------

    static JFreeChart scatter(List<double[]> points, List<String> seriesKeys,
                              String title, String xLabel, String yLabel) {
        XYSeriesCollection ds = new XYSeriesCollection();
        Map<String, XYSeries> byKey = new LinkedHashMap<>();
        for (int i = 0; i < points.size(); i++) {
            String key = seriesKeys.get(i);
            // autoSort=false so scatter points keep insertion order; allow dups.
            XYSeries s = byKey.computeIfAbsent(key, k -> new XYSeries(k, false, true));
            s.add(points.get(i)[0], points.get(i)[1]);
        }
        for (XYSeries s : byKey.values()) ds.addSeries(s);
        return ChartFactory.createScatterPlot(
                title, xLabel, yLabel, ds, PlotOrientation.VERTICAL,
                byKey.size() > 1, true, false);
    }

    // ---- pie ---------------------------------------------------------------

    static JFreeChart pie(List<String> labels, List<Double> values, String title) {
        DefaultPieDataset<String> ds = new DefaultPieDataset<>();
        // Sum duplicate labels so the pie has one slice per distinct label.
        Map<String, Double> sums = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            sums.merge(labels.get(i), values.get(i), Double::sum);
        }
        for (var e : sums.entrySet()) ds.setValue(e.getKey(), e.getValue());
        return ChartFactory.createPieChart(title, ds, true, true, false);
    }

    // ---- histogram ---------------------------------------------------------

    static JFreeChart histogram(double[] values, int bins, String title,
                                String valueLabel) {
        HistogramDataset ds = new HistogramDataset();
        if (values.length > 0) {
            ds.addSeries("frequency", values, Math.max(1, bins));
        }
        return ChartFactory.createHistogram(
                title, valueLabel, "frequency", ds, PlotOrientation.VERTICAL,
                false, true, false);
    }

    // ---- util --------------------------------------------------------------

    private static long distinct(List<String> keys) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(keys)).size();
    }
}
