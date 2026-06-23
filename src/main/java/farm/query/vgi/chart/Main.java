package farm.query.vgi.chart;

import farm.query.vgi.Worker;

/**
 * VGI worker entry point: render charts from a query result into PNG image BLOBs
 * via JFreeChart, as DuckDB table-in-out SQL functions. Catalog name: {@code chart}.
 *
 * <p>Attach from DuckDB with:
 * <pre>{@code
 * ATTACH 'chart' (TYPE vgi, LOCATION 'java -jar vgi-chart-all.jar');
 * SELECT length(png) FROM chart.chart_line((SELECT x, y FROM points), x := 'x', y := 'y');
 * }</pre>
 */
public final class Main {

    private Main() {}

    public static final String GIT_COMMIT =
            System.getenv("VGI_CHART_GIT_COMMIT") != null
                    ? System.getenv("VGI_CHART_GIT_COMMIT") : "unknown";

    public static Worker buildWorker() {
        // Charts are rasterized through java.awt; force headless so the worker
        // never needs a display (also set by the build's JVM args / manifest).
        System.setProperty("java.awt.headless", "true");
        return Worker.builder()
                .catalogName("chart")
                .implementationVersion(GIT_COMMIT)
                .catalogComment("Render charts (line/bar/pie/scatter/histogram) from a query result "
                        + "into PNG image BLOBs via JFreeChart")
                .registerTableInOut(new ChartLineFunction())
                .registerTableInOut(new ChartBarFunction())
                .registerTableInOut(new ChartScatterFunction())
                .registerTableInOut(new ChartPieFunction())
                .registerTableInOut(new ChartHistogramFunction());
    }

    public static void main(String[] args) {
        String stderrPath = System.getenv("VGI_WORKER_STDERR");
        if (stderrPath != null && !stderrPath.isEmpty()) {
            try {
                java.io.PrintStream ps = new java.io.PrintStream(
                        new java.io.FileOutputStream(stderrPath, true), true);
                System.setErr(ps);
            } catch (Exception ignore) {
                // best-effort stderr redirect
            }
        }
        buildWorker().runFromArgs(args);
    }
}
