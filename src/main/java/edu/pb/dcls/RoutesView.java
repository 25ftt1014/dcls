package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.Domain.RoutePlan;
import static edu.pb.dcls.Domain.Stop;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.MapRenderer.drawRoute;
import static edu.pb.dcls.MapRenderer.mapCanvas;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Route planning screen: build a multi-stop route, optimise it, and review route history. */
final class RoutesView {
    private final DclsApp app;

    RoutesView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Route planning", "Build a multi-stop route from local map points and estimate travel time.");
        HBox builder = new HBox(12);
        VBox controls = panel(new Label("Create a route"));
        ((Label) controls.getChildren().get(0)).getStyleClass().add("section-title");
        TextField name = new TextField(); name.setPromptText("Route name");
        ComboBox<String> place = new ComboBox<>(FXCollections.observableArrayList(app.routeService.placeNames())); place.setPromptText("Choose stop"); place.setMaxWidth(Double.MAX_VALUE);
        Button addStop = button("Add stop", "secondary-button");
        DclsApp.ListViewWithRemove draft = new DclsApp.ListViewWithRemove(app.routeStopsDraft);
        addStop.setOnAction(event -> {
            if (place.getValue() == null) return;
            Stop stop = app.routeService.place(place.getValue());
            if (stop != null && app.routeStopsDraft.stream().noneMatch(s -> s.name().equals(stop.name()))) app.routeStopsDraft.add(stop);
        });
        if (app.routeStopsDraft.isEmpty()) app.routeStopsDraft.add(AppServices.BruneiPlaces.DEPOT);
        ComboBox<User> driver = app.driverCombo(); driver.setPromptText("Assign driver (optional)");
        Button optimize = button("Optimize route", "secondary-button");
        Button saveAssign = button("Save & assign", "primary-button");
        Button clickToggle = button("Map clicks: add stops ON", "secondary-button");
        Label routeResult = new Label("Add at least one delivery stop after the depot."); routeResult.getStyleClass().add("muted"); routeResult.setWrapText(true);
        VBox etaBreakdown = new VBox(2);
        DclsApp.MapController previewMap = app.new MapController(430, 330);
        previewMap.content = () -> app.previewStops.stream().map(s -> new double[] {s.x(), s.y()}).toList();
        previewMap.painter = (g, v) -> {
            if (!app.previewStops.isEmpty()) MapRenderer.drawRouteOn(g, previewMap.canvas.getWidth(), previewMap.canvas.getHeight(), app.previewStops, null, "#176df6", v);
        };
        final Runnable[] optimizeRouteRef = new Runnable[1];
        previewMap.clickAction = lonLat -> {
            Stop pin = new Stop("custom-" + AppServices.id(), "Custom pin " + (++app.customPinCount), lonLat[0], lonLat[1]);
            app.routeStopsDraft.add(pin);
            if (optimizeRouteRef[0] != null) optimizeRouteRef[0].run();
        };
        final RoutePlan[] planned = {null};
        final boolean[] saved = {false};
        Runnable optimizeRoute = () -> {
            try {
                RoutePlan plan = app.routeService.optimizeStops(name.getText(), new ArrayList<>(app.routeStopsDraft), null);
                planned[0] = plan; saved[0] = false;
                app.previewStops = plan.stops();
                routeResult.setText(String.format("%s  ·  %.1f km  ·  about %d minutes", plan.name(), plan.distanceKm(), plan.estimatedMinutes()));
                app.showEtaBreakdown(etaBreakdown, plan);
                previewMap.redraw();
            } catch (AppException exception) { app.alert(Alert.AlertType.WARNING, "Route not created", exception.getMessage()); }
        };
        optimizeRouteRef[0] = optimizeRoute;
        optimize.setOnAction(event -> optimizeRoute.run());
        clickToggle.setOnAction(event -> {
            if (previewMap.clickAction != null) {
                previewMap.clickAction = null;
                clickToggle.setText("Map clicks: add stops OFF");
            } else {
                previewMap.clickAction = lonLat -> {
                    Stop pin = new Stop("custom-" + AppServices.id(), "Custom pin " + (++app.customPinCount), lonLat[0], lonLat[1]);
                    app.routeStopsDraft.add(pin);
                    optimizeRoute.run();
                };
                clickToggle.setText("Map clicks: add stops ON");
            }
        });
        saveAssign.setOnAction(event -> {
            if (planned[0] == null) { optimizeRoute.run(); return; }
            if (driver.getValue() == null) { app.alert(Alert.AlertType.INFORMATION, "Choose a driver", "Select a driver before saving and assigning this route."); return; }
            if (!saved[0]) {
                RoutePlan route = planned[0];
                planned[0] = new RoutePlan(route.id(), route.name(), driver.getValue().id(), route.stops(), route.distanceKm(), route.estimatedMinutes(), route.completed(), route.createdAt());
                app.repository.saveRoute(planned[0]); saved[0] = true;
            }
            app.alert(Alert.AlertType.INFORMATION, "Route assigned", "Route " + planned[0].name() + " has been saved and assigned to " + driver.getValue().name() + ".");
            app.showPage("routes");
        });
        controls.getChildren().addAll(new Label("Pickup location"), place, new Label("Delivery stops"), draft, addStop, driver, optimize, saveAssign, clickToggle, routeResult, etaBreakdown);
        controls.setPrefWidth(260); controls.setMinWidth(250); VBox.setVgrow(draft, ALWAYS);
        VBox mapPanel = panel(new Label("Route preview — scroll to zoom, drag to pan, click to add a stop")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        mapPanel.getChildren().addAll(previewMap.canvas, previewMap.zoomBar, app.legendBox());
        builder.getChildren().addAll(controls, mapPanel); HBox.setHgrow(mapPanel, ALWAYS);
        VBox history = panel(new Label("Route history")); ((Label) history.getChildren().get(0)).getStyleClass().add("section-title");
        TableView<RoutePlan> table = new TableView<>(); table.setPlaceholder(new Label("No routes have been planned yet."));
        table.getColumns().add(textCol("Route", RoutePlan::name, 180));
        table.getColumns().add(textCol("Stops", r -> String.valueOf(r.stops().size()), 60));
        table.getColumns().add(textCol("Distance", r -> String.format("%.1f km", r.distanceKm()), 95));
        table.getColumns().add(textCol("Estimate", r -> r.estimatedMinutes() + " min", 95));
        table.getColumns().add(textCol("Driver", r -> app.driverName(r.driverId()), 140));
        table.getColumns().add(textCol("Created", r -> r.createdAt().format(DclsApp.DATE_TIME), 130));
        table.setItems(FXCollections.observableArrayList(app.repository.routes())); table.setPrefHeight(180); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            if (selected != null) {
                app.previewStops = selected.stops();
                app.showEtaBreakdown(etaBreakdown, selected);
                previewMap.redraw();
            }
        });
        app.repository.routes().stream().max(Comparator.comparing(RoutePlan::createdAt)).ifPresent(route -> {
            app.previewStops = route.stops();
            app.showEtaBreakdown(etaBreakdown, route);
        });
        previewMap.redraw();
        history.getChildren().add(table); page.getChildren().addAll(builder, history); return page;
    }
}
