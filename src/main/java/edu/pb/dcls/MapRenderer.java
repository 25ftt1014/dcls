package edu.pb.dcls;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

import static edu.pb.dcls.Domain.Stop;
import static edu.pb.dcls.Domain.TrackingPoint;

/**
 * Stateless painting of the simulated Brunei-Muara maps and the small dashboard chart.
 * Every method takes the {@link Canvas} (or its {@link GraphicsContext}) to draw onto,
 * so there is no shared state between screens.
 */
final class MapRenderer {
    private MapRenderer() { }

    static Canvas mapCanvas(double width, double height) {
        Canvas canvas = new Canvas(width, height);
        canvas.getStyleClass().add("map-canvas");
        prepareMap(canvas);
        return canvas;
    }

    static GraphicsContext prepareMap(Canvas canvas) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.web("#eef3f6")); g.fillRect(0, 0, w, h);
        g.setFill(Color.web("#e3eee3")); g.fillOval(w * .70, h * .08, w * .25, h * .2); g.fillOval(w * .02, h * .66, w * .18, h * .22);
        g.setStroke(Color.web("#d7e1e5")); g.setLineWidth(18);
        g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
        g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
        g.setStroke(Color.web("#ffffff")); g.setLineWidth(2.5);
        g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
        g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
        g.setFill(Color.web("#657487")); g.setFont(Font.font("Segoe UI", 11));
        g.fillText("Brunei-Muara  ·  simulated route map", 14, 20);
        return g;
    }

    static void drawDashboardRoads(Canvas canvas) {
        GraphicsContext g = prepareMap(canvas); double w = canvas.getWidth(), h = canvas.getHeight();
        g.setStroke(Color.web("#d6e0e8")); g.setLineWidth(17);
        g.beginPath(); g.moveTo(w*.04,h*.25); g.bezierCurveTo(w*.30,h*.02,w*.50,h*.58,w*.70,h*.25); g.bezierCurveTo(w*.82,h*.12,w*.91,h*.32,w*.98,h*.50); g.stroke();
        g.setStroke(Color.WHITE); g.setLineWidth(6);
        g.beginPath(); g.moveTo(w*.05,h*.62); g.bezierCurveTo(w*.32,h*.37,w*.50,h*.76,w*.75,h*.46); g.bezierCurveTo(w*.88,h*.34,w*.92,h*.54,w*.98,h*.70); g.stroke();
        g.setStroke(Color.web("#dce9dc")); g.setLineWidth(18);
        g.beginPath(); g.moveTo(w*.02,h*.78); g.bezierCurveTo(w*.25,h*.52,w*.55,h*.95,w*.75,h*.68); g.bezierCurveTo(w*.83,h*.58,w*.92,h*.75,w*.98,h*.89); g.stroke();
        g.setStroke(Color.web("#176df6")); g.setLineWidth(3);
        g.beginPath(); g.moveTo(w*.08,h*.82); g.bezierCurveTo(w*.28,h*.71,w*.37,h*.48,w*.55,h*.40); g.bezierCurveTo(w*.73,h*.32,w*.81,h*.24,w*.94,h*.16); g.stroke();
        for(double[] p:new double[][]{{.08,.82},{.55,.40},{.94,.16}}){g.setFill(Color.web("#176df6"));g.fillOval(p[0]*w-6,p[1]*h-6,12,12);g.setStroke(Color.WHITE);g.setLineWidth(2);g.strokeOval(p[0]*w-6,p[1]*h-6,12,12);}
    }

    static Canvas activityChart() {
        Canvas canvas = new Canvas(224, 74); GraphicsContext g = canvas.getGraphicsContext2D(); double[] values = {.34,.43,.39,.55,.74,.86,.69,.92,.84,.94,.82,1};
        for(int i=0;i<values.length;i++){double x=4+i*18,bar=values[i]*56;g.setFill(i==values.length-1?Color.web("#315cf5"):Color.web("#e5ebff"));g.fillRoundRect(x,58-bar,14,bar,4,4);} return canvas;
    }

    static void drawRoute(Canvas canvas, List<Stop> stops, TrackingPoint moving) {
        GraphicsContext g = prepareMap(canvas); drawRouteOn(g, canvas.getWidth(), canvas.getHeight(), stops, moving);
    }

    static void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving) {
        if (stops == null || stops.isEmpty()) return;
        g.setStroke(Color.web("#176df6")); g.setLineWidth(4);
        for (int i = 1; i < stops.size(); i++) {
            Stop a = stops.get(i - 1), b = stops.get(i);
            g.strokeLine(a.x() * width, a.y() * height, b.x() * width, b.y() * height);
        }
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i); double x = stop.x() * width, y = stop.y() * height;
            g.setFill(i == 0 ? Color.web("#17613f") : Color.web("#ffffff"));
            g.fillOval(x - 7, y - 7, 14, 14); g.setStroke(Color.web(i == 0 ? "#17613f" : "#176df6")); g.setLineWidth(3); g.strokeOval(x - 7, y - 7, 14, 14);
            g.setFill(Color.web("#172033")); g.fillText(stop.name(), x + 9, y - 8);
        }
        if (moving != null) drawVehicleMarker(g, width, height, moving.x(), moving.y(), "Vehicle");
    }

    static void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label) {
        double px = x * width, py = y * height;
        g.setFill(Color.web("#1457c5")); g.fillOval(px - 9, py - 9, 18, 18);
        g.setStroke(Color.WHITE); g.setLineWidth(2); g.strokeOval(px - 9, py - 9, 18, 18);
        g.setFill(Color.web("#172033")); g.fillText(label, px + 12, py + 4);
    }
}
