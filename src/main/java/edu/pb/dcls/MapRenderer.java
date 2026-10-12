package edu.pb.dcls;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static edu.pb.dcls.Domain.Stop;
import static edu.pb.dcls.Domain.TrackingPoint;

/**
 * Real-map rendering over the bundled offline OpenStreetMap grids of Brunei Darussalam.
 * Stateless: every method takes what it draws, so there is no shared state between screens.
 * Stop.x()/y() carry longitude/latitude (WGS84 degrees).
 */
final class MapRenderer {
    private MapRenderer() { }

    static final double MAP_LON_MIN = 114.43359375, MAP_LON_MAX = 115.13671875;
    static final double MAP_LAT_MIN = 4.56547355071028, MAP_LAT_MAX = 5.09094417503339;

    static final class TileLevel {
        final Image image;
        final int z, x0, y0, cols, rows;
        TileLevel(Image image, int z, int x0, int y0, int cols, int rows) {
            this.image = image; this.z = z; this.x0 = x0; this.y0 = y0; this.cols = cols; this.rows = rows;
        }
        double lonMin() { return x0 / Math.pow(2, z) * 360 - 180; }
        double lonMax() { return (x0 + cols) / Math.pow(2, z) * 360 - 180; }
        double latAt(int y) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / Math.pow(2, z))))); }
        double latMax() { return latAt(y0); }
        double latMin() { return latAt(y0 + rows); }
        double lonToPixel(double lon) { return ((lon + 180) / 360 * Math.pow(2, z) - x0) * 256; }
        double latToPixel(double lat) {
            double rad = Math.toRadians(lat);
            return ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * Math.pow(2, z) - y0) * 256;
        }
        boolean contains(ViewBounds view) {
            return view.lonMin() >= lonMin() && view.lonMax() <= lonMax()
                    && view.latMin() >= latMin() && view.latMax() <= latMax();
        }
    }

    record ViewBounds(double lonMin, double lonMax, double latMin, double latMax) { }

    static final ViewBounds FULL_MAP = new ViewBounds(MAP_LON_MIN, MAP_LON_MAX, MAP_LAT_MIN, MAP_LAT_MAX);

    static final TileLevel BASE = loadLevel("/edu/pb/dcls/brunei-map.png", 11, 1675, 995, 4, 3);
    static final TileLevel METRO = loadLevel("/edu/pb/dcls/brunei-map-z13.png", 13, 6709, 3982, 5, 4);
    static final TileLevel TUTONG = loadLevel("/edu/pb/dcls/brunei-map-z13-tutong.png", 13, 6704, 3985, 3, 2);
    static final TileLevel MUARA = loadLevel("/edu/pb/dcls/brunei-map-z13-muara.png", 13, 6712, 3981, 4, 3);

    private static TileLevel loadLevel(String resource, int z, int x0, int y0, int cols, int rows) {
        try (InputStream in = MapRenderer.class.getResourceAsStream(resource)) {
            if (in != null) return new TileLevel(new Image(in), z, x0, y0, cols, rows);
        } catch (Exception ignored) { }
        return new TileLevel(null, z, x0, y0, cols, rows);
    }

    /** Sharpest bundled grid covering the view; street-level zooms use z13, wide views z11. */
    static TileLevel levelFor(ViewBounds view) {
        if (view.lonMax() - view.lonMin() < 0.24) {
            for (TileLevel level : List.of(METRO, TUTONG, MUARA)) {
                if (level.image != null && level.contains(view)) return level;
            }
        }
        return BASE;
    }

    /** Zoom-to-content: bounds enclosing the given lon/lat points plus margin, matched to
     *  the canvas aspect, never tighter than a ~12 km window (tile detail limit). */
    static ViewBounds fitBounds(List<double[]> points, double aspect) {
        if (points == null || points.isEmpty()) return FULL_MAP;
        double lonMin = 180, lonMax = -180, latMin = 90, latMax = -90;
        for (double[] p : points) {
            lonMin = Math.min(lonMin, p[0]); lonMax = Math.max(lonMax, p[0]);
            latMin = Math.min(latMin, p[1]); latMax = Math.max(latMax, p[1]);
        }
        double lonPad = Math.max((lonMax - lonMin) * 0.20, 0.07);
        double latPad = Math.max((latMax - latMin) * 0.20, 0.05);
        lonMin -= lonPad; lonMax += lonPad; latMin -= latPad; latMax += latPad;
        double lonSpan = Math.max(lonMax - lonMin, 0.14), latSpan = Math.max(latMax - latMin, 0.10);
        if (lonSpan / latSpan < aspect) lonSpan = latSpan * aspect;
        else latSpan = lonSpan / aspect;
        double lonMid = (lonMin + lonMax) / 2, latMid = (latMin + latMax) / 2;
        lonMin = lonMid - lonSpan / 2; lonMax = lonMid + lonSpan / 2;
        latMin = latMid - latSpan / 2; latMax = latMid + latSpan / 2;
        if (lonMax - lonMin >= MAP_LON_MAX - MAP_LON_MIN || latMax - latMin >= MAP_LAT_MAX - MAP_LAT_MIN) return FULL_MAP;
        if (lonMin < MAP_LON_MIN) { lonMax += MAP_LON_MIN - lonMin; lonMin = MAP_LON_MIN; }
        if (lonMax > MAP_LON_MAX) { lonMin -= lonMax - MAP_LON_MAX; lonMax = MAP_LON_MAX; }
        if (latMin < MAP_LAT_MIN) { latMax += MAP_LAT_MIN - latMin; latMin = MAP_LAT_MIN; }
        if (latMax > MAP_LAT_MAX) { latMin -= latMax - MAP_LAT_MAX; latMax = MAP_LAT_MAX; }
        return new ViewBounds(lonMin, lonMax, latMin, latMax);
    }

    static double mercator(double lat) {
        double rad = Math.toRadians(lat);
        return Math.log(Math.tan(rad) + 1 / Math.cos(rad));
    }

    static double mercToLat(double merc) {
        return Math.toDegrees(Math.atan(Math.sinh(merc)));
    }

    static double[] project(Stop stop, double width, double height, ViewBounds view) {
        return project(stop.x(), stop.y(), width, height, view);
    }

    static double[] project(double lon, double lat, double width, double height, ViewBounds view) {
        double pad = 14;
        double x = pad + (lon - view.lonMin()) / (view.lonMax() - view.lonMin()) * (width - pad * 2);
        double top = mercator(view.latMax()), bottom = mercator(view.latMin());
        double y = pad + (top - mercator(Math.min(Math.max(lat, view.latMin()), view.latMax()))) / (top - bottom) * (height - pad * 2);
        return new double[] {Math.min(Math.max(x, 10), width - 10), Math.min(Math.max(y, 22), height - 10)};
    }

    static double[] unproject(double px, double py, double width, double height, ViewBounds view) {
        double pad = 14;
        double lon = view.lonMin() + (px - pad) / (width - pad * 2) * (view.lonMax() - view.lonMin());
        double mercTop = mercator(view.latMax()), mercBottom = mercator(view.latMin());
        double lat = mercToLat(mercTop - (py - pad) / (height - pad * 2) * (mercTop - mercBottom));
        return new double[] {
            Math.min(Math.max(lon, MAP_LON_MIN), MAP_LON_MAX),
            Math.min(Math.max(lat, MAP_LAT_MIN), MAP_LAT_MAX)};
    }

    static Canvas mapCanvas(double width, double height) {
        Canvas canvas = new Canvas(width, height);
        canvas.getStyleClass().add("map-canvas");
        prepareMap(canvas, FULL_MAP);
        return canvas;
    }

    static GraphicsContext prepareMap(Canvas canvas, ViewBounds view) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        TileLevel level = levelFor(view);
        if (level.image != null) {
            // NOTE: Canvas drawImage with an explicit source rect renders blank on this
            // JavaFX version, so zoom by drawing the whole grid through a dest transform.
            double sx = level.lonToPixel(view.lonMin()), ex = level.lonToPixel(view.lonMax());
            double sy = level.latToPixel(view.latMax()), ey = level.latToPixel(view.latMin());
            double xScale = w / (ex - sx), yScale = h / (ey - sy);
            g.save();
            g.beginPath(); g.rect(0, 0, w, h); g.clip();
            g.drawImage(level.image, -sx * xScale, -sy * yScale,
                    level.image.getWidth() * xScale, level.image.getHeight() * yScale);
            g.restore();
        } else {
            g.setFill(Color.web("#eef3f6")); g.fillRect(0, 0, w, h);
        }
        g.setFill(Color.web("#657487")); g.setFont(Font.font("Segoe UI", 11));
        g.fillText("Brunei Darussalam  ·  simulated phone GPS @ 35 km/h", 14, 20);
        g.setFont(Font.font("Segoe UI", 9));
        g.fillText("© OpenStreetMap contributors", w - 178, h - 8);
        drawScaleBar(g, w, h, view);
        return g;
    }

    /** Scale bar from the live view span (111.32 km per lon-degree, adjusted for latitude). */
    static void drawScaleBar(GraphicsContext g, double w, double h, ViewBounds view) {
        double latMid = (view.latMin() + view.latMax()) / 2;
        double metersPerPixel = (view.lonMax() - view.lonMin()) * 111320 * Math.cos(Math.toRadians(latMid)) / w;
        int[] steps = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        int choice = steps[0];
        for (int step : steps) if (step / metersPerPixel <= 130) choice = step;
        double bar = choice / metersPerPixel;
        double x = 14, y = h - 14;
        g.setStroke(Color.WHITE); g.setLineWidth(5);
        g.strokeLine(x, y, x + bar, y);
        g.setStroke(Color.web("#172033")); g.setLineWidth(2.5);
        g.strokeLine(x, y, x + bar, y);
        g.setFill(Color.web("#172033"));
        g.setFont(Font.font("Segoe UI", 9));
        g.fillText(choice >= 1000 ? (choice / 1000) + " km" : choice + " m", x + bar + 6, y + 3);
    }

    static void drawRoute(Canvas canvas, List<Stop> stops, TrackingPoint moving) {
        if (stops == null || stops.isEmpty()) return;
        List<double[]> points = new ArrayList<>();
        for (Stop stop : stops) points.add(new double[] {stop.x(), stop.y()});
        if (moving != null) points.add(new double[] {moving.x(), moving.y()});
        ViewBounds view = fitBounds(points, canvas.getWidth() / canvas.getHeight());
        GraphicsContext g = prepareMap(canvas, view);
        drawRouteOn(g, canvas.getWidth(), canvas.getHeight(), stops, moving, "#176df6", view);
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving) {
        drawRouteOn(g, width, height, stops, moving, "#176df6", autoView(stops, moving, width / height));
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving, String color, ViewBounds view) {
        if (stops == null || stops.isEmpty()) return;
        g.setStroke(Color.web("#ffffff", 0.85)); g.setLineWidth(6);
        strokeRoadPath(g, width, height, stops, view);
        g.setStroke(Color.web(color)); g.setLineWidth(3.5);
        strokeRoadPath(g, width, height, stops, view);
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i); double[] p = project(stop, width, height, view); double x = p[0], y = p[1];
            if (stop.id().startsWith("custom-")) {
                g.setFill(Color.web("#ffffff"));
                g.fillPolygon(new double[] {x, x + 8, x, x - 8}, new double[] {y - 9, y, y + 9, y}, 4);
                g.setStroke(Color.web("#7c3aed")); g.setLineWidth(3);
                g.strokePolygon(new double[] {x, x + 8, x, x - 8}, new double[] {y - 9, y, y + 9, y}, 4);
            } else {
                g.setFill(i == 0 ? Color.web("#17613f") : Color.web("#ffffff"));
                g.fillOval(x - 7, y - 7, 14, 14); g.setStroke(Color.web(i == 0 ? "#17613f" : "#176df6")); g.setLineWidth(3); g.strokeOval(x - 7, y - 7, 14, 14);
            }
            mapLabel(g, stop.name(), x + 9, y - 8);
        }
        if (moving != null) drawVehicleMarker(g, width, height, moving.x(), moving.y(), "Vehicle", "#1457c5", false, view);
    }

    /** Stroke every leg along its real road polyline (straight fallback when unknown). */
    static void strokeRoadPath(GraphicsContext g, double width, double height, List<Stop> stops, ViewBounds view) {
        for (int i = 1; i < stops.size(); i++) {
            List<double[]> path = AppServices.RouteService.legPath(stops.get(i - 1), stops.get(i));
            for (int j = 1; j < path.size(); j++) {
                double[] a = project(path.get(j - 1)[0], path.get(j - 1)[1], width, height, view);
                double[] b = project(path.get(j)[0], path.get(j)[1], width, height, view);
                g.strokeLine(a[0], a[1], b[0], b[1]);
            }
        }
    }

    static void mapLabel(GraphicsContext g, String text, double x, double y) {
        g.setStroke(Color.WHITE); g.setLineWidth(3);
        g.strokeText(text, x, y);
        g.setFill(Color.web("#172033")); g.fillText(text, x, y);
    }

    static void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label) {
        drawVehicleMarker(g, width, height, x, y, label, "#1457c5", false, FULL_MAP);
    }

    static void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label, String color, boolean stale, ViewBounds view) {
        double[] p = project(x, y, width, height, view); double px = p[0], py = p[1];
        g.setFill(Color.web(color)); g.fillOval(px - 9, py - 9, 18, 18);
        g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeOval(px - 9, py - 9, 18, 18);
        mapLabel(g, label + (stale ? " · STALE" : ""), px + 12, py + 4);
    }

    static void drawDashboardRoads(Canvas canvas) {
        prepareMap(canvas, FULL_MAP);
    }

    static Canvas activityChart() {
        Canvas canvas = new Canvas(224, 74); GraphicsContext g = canvas.getGraphicsContext2D(); double[] values = {.34,.43,.39,.55,.74,.86,.69,.92,.84,.94,.82,1};
        for(int i=0;i<values.length;i++){double x=4+i*18,bar=values[i]*56;g.setFill(i==values.length-1?Color.web("#315cf5"):Color.web("#e5ebff"));g.fillRoundRect(x,58-bar,14,bar,4,4);} return canvas;
    }

    private static ViewBounds autoView(List<Stop> stops, TrackingPoint moving, double aspect) {
        List<double[]> points = new ArrayList<>();
        if (stops != null) for (Stop stop : stops) points.add(new double[] {stop.x(), stop.y()});
        if (moving != null) points.add(new double[] {moving.x(), moving.y()});
        return fitBounds(points, aspect);
    }
}
