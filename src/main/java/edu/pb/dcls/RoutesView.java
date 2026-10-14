package edu.pb.dcls;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.Domain.RoutePlan;
import static edu.pb.dcls.Domain.Stop;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Route planning screen: place stops, get the fastest road route automatically, assign a driver. */
final class RoutesView {
    private final DclsApp app;
    /** Pins snap to roads only within this distance; beyond it the click stands. */
    private static final double SNAP_MAX_KM = 1.5;

    RoutesView(DclsApp app) { this.app = app; }

    private static HBox stepSection(int number, String title) {
        Label badge = new Label(String.valueOf(number));
        badge.getStyleClass().add("step-badge");
        Label text = new Label(title);
        text.getStyleClass().add("section-title");
        HBox row = new HBox(8, badge, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("step-header");
        return row;
    }

    private static void setStatus(Label pill, String text, String cls) {
        pill.setText(text);
        pill.getStyleClass().removeAll("status-good", "status-info", "status-warn", "status-bad");
        pill.getStyleClass().add(cls);
    }

    private static VBox heroMetric(String caption, Label value) {
        Label title = new Label(caption);
        title.getStyleClass().add("eyebrow");
        VBox box = new VBox(2, title, value);
        HBox.setHgrow(box, ALWAYS);
        return box;
    }

    VBox page() {
        final int gen = ++app.routePageGen;
        // Always-blank entry: clear stale draft/preview unless arriving via order handoff.
        List<String> handoff = app.pendingRouteShipmentIds == null ? List.of() : List.copyOf(app.pendingRouteShipmentIds);
        app.pendingRouteShipmentIds = List.of();
        app.routeStopsDraft.clear();
        app.previewStops = List.of();
        final List<String> handoffShipmentIds = new ArrayList<>(handoff);
        final Map<String, Double> stopConfidence = app.routeStopConfidence;
        final java.util.Set<String> confirmedStops = app.routeStopConfirmed;
        final Map<String, AddressGeocoder.GeocodedStop> lastGeocode = new java.util.HashMap<>();
        stopConfidence.clear();
        confirmedStops.clear();
        VBox page = pageHeader("Route planning", handoffShipmentIds.isEmpty()
                ? "Start blank — add stops or arrive from an order to auto-locate its address."
                : "Route for " + String.join(", ", handoffShipmentIds) + " — address located by keywords.");
        HBox builder = new HBox(12);
        VBox controls = panel(new Label("Create a route"));
        ((Label) controls.getChildren().get(0)).getStyleClass().add("section-title");

        TextField name = new TextField(); name.setPromptText("Route name");
        ComboBox<String> place = new ComboBox<>(FXCollections.observableArrayList(app.routeService.placeNames())); place.setPromptText("Choose stop"); place.setMaxWidth(Double.MAX_VALUE);
        Button addStop = button("Add stop", "secondary-button"); addStop.setMaxWidth(Double.MAX_VALUE);
        ComboBox<User> driver = app.driverCombo(); driver.setPromptText("Assign driver (optional)");
        CheckBox optimizeToggle = new CheckBox("Optimize order"); optimizeToggle.setSelected(true);
        optimizeToggle.setTooltip(new Tooltip("Uncheck to visit stops in the order listed"));
        Button optimize = button("Calculate now", "secondary-button"); optimize.setMaxWidth(Double.MAX_VALUE);
        Button saveAssign = button("Save & assign", "primary-button"); saveAssign.setMaxWidth(Double.MAX_VALUE);
        Button resetTrail = button("Reset to initial trail", "secondary-button"); resetTrail.setMaxWidth(Double.MAX_VALUE);
        Button clickToggle = button("Map clicks: placing ON", "secondary-button"); clickToggle.setMaxWidth(Double.MAX_VALUE);

        Label statusPill = new Label("Add stops");
        statusPill.getStyleClass().addAll("status-pill", "status-warn");
        Label heroKm = new Label("—"); heroKm.getStyleClass().add("metric-value");
        Label heroMin = new Label("—"); heroMin.getStyleClass().add("metric-value");
        HBox hero = new HBox(10, heroMetric("Distance", heroKm), heroMetric("Drive time", heroMin));
        Label routeResult = new Label("Add at least one delivery stop after the depot."); routeResult.getStyleClass().add("muted"); routeResult.setWrapText(true);
        VBox legRows = new VBox(2);
        VBox etaBreakdown = new VBox(2);

        DclsApp.MapController previewMap = app.new MapController(440, 340);
        final boolean[] dirty = {false};
        final int[] highlightLeg = {-1};
        previewMap.content = () -> (dirty[0] ? app.routeStopsDraft : app.previewStops).stream().map(s -> new double[] {s.x(), s.y()}).toList();
        previewMap.painter = (g, v) -> {
            double w = previewMap.canvas.getWidth(), h = previewMap.canvas.getHeight();
            if (dirty[0]) {
                if (!app.routeStopsDraft.isEmpty()) MapRenderer.drawStopMarkers(g, w, h, new ArrayList<>(app.routeStopsDraft), v);
            } else if (!app.previewStops.isEmpty()) {
                MapRenderer.drawRouteOn(g, w, h, app.previewStops, null, "#176df6", v, highlightLeg[0]);
            }
        };

        final RoutePlan[] planned = {null};
        final boolean[] saved = {false};
        final Consumer<Boolean>[] runCalcRef = new Consumer[1];
        PauseTransition debounce = new PauseTransition(Duration.seconds(1.5));
        debounce.setOnFinished(event -> {
            if (gen == app.routePageGen && runCalcRef[0] != null) runCalcRef[0].accept(false);
        });
        // Preview-only calc on a worker: snap pins to roads, order (table solver or
        // kept order), resolve road geometry per leg. Persisted solely by Save & assign.
        final Thread[] worker = new Thread[1];
        Consumer<List<Stop>> showLegEstimates = stops -> {
            legRows.getChildren().clear();
            highlightLeg[0] = -1;
            for (int i = 1; i < stops.size(); i++) {
                Stop a = stops.get(i - 1), b = stops.get(i);
                AppServices.OsrmRouter.LegResult cached =
                        AppServices.OsrmRouter.leg(a.x(), a.y(), b.x(), b.y());
                double km = cached != null ? cached.km()
                        : AppServices.OsrmRouter.snapKm(a.x(), a.y(), b.x(), b.y());
                legRows.getChildren().add(legRow(i - 1, km, highlightLeg, previewMap));
            }
        };
        runCalcRef[0] = manual -> {
            if (gen != app.routePageGen) return;
            // Confidence gate: block fastest-route calc until unsure pins are confirmed.
            List<String> pending = pendingOrderIds(app.routeStopsDraft, confirmedStops, stopConfidence);
            if (!pending.isEmpty()) {
                app.previewStops = new ArrayList<>(app.routeStopsDraft);
                planned[0] = null; saved[0] = false;
                dirty[0] = true;
                routeResult.setText("Confirm locations for " + String.join(", ", pending)
                        + " in the panel before calculating the fastest route.");
                setStatus(statusPill, "Needs confirmation", "status-bad");
                previewMap.redraw();
                if (manual) app.alert(Alert.AlertType.WARNING, "Confirm location",
                        "These orders need confirmation: " + String.join(", ", pending)
                                + ". Pick a candidate in the locations panel, confirm a pin on the map, or Confirm all.");
                return;
            }
            if (app.routeStopsDraft.size() < 2) {
                app.previewStops = new ArrayList<>(app.routeStopsDraft);
                planned[0] = null; saved[0] = false;
                dirty[0] = true;
                routeResult.setText("Add at least one delivery stop after the depot.");
                heroKm.setText("—"); heroMin.setText("—");
                setStatus(statusPill, "Add stops", "status-warn");
                legRows.getChildren().clear();
                highlightLeg[0] = -1;
                etaBreakdown.getChildren().clear();
                previewMap.redraw();
                if (manual) app.alert(Alert.AlertType.WARNING, "Route not created", "Add a depot and at least one stop.");
                return;
            }
            if (worker[0] != null) worker[0].interrupt();
            setStatus(statusPill, "Routing…", "status-info");
            routeResult.setText("Snapping pins and finding fastest roads…");
            previewMap.redraw();
            List<Stop> snapshot = new ArrayList<>(app.routeStopsDraft);
            String routeName = name.getText();
            boolean optimizeOrder = optimizeToggle.isSelected();
            Thread thread = new Thread(() -> {
                try {
                    List<Stop> snapped = new ArrayList<>();
                    int moved = 0;
                    for (Stop stop : snapshot) {
                        if (Thread.currentThread().isInterrupted()) return;
                        // Snap every non-depot pin (custom + order + geo) so the marker
                        // sits on the road the route line follows; depot stays fixed.
                        if (!"DCLS Depot".equals(stop.name()) && !"depot".equals(stop.id())) {
                            double[] near = AppServices.OsrmRouter.snapToRoad(stop.x(), stop.y());
                            if (near != null && Double.isFinite(near[0]) && Double.isFinite(near[1])) {
                                double shiftKm = AppServices.BruneiPlaces.haversineKm(stop.x(), stop.y(), near[0], near[1]);
                                if (shiftKm <= SNAP_MAX_KM
                                        && (Math.abs(near[0] - stop.x()) > 1e-7 || Math.abs(near[1] - stop.y()) > 1e-7)) {
                                    snapped.add(new Stop(stop.id(), stop.name(), near[0], near[1]));
                                    moved++;
                                    System.out.println("[dcls-routes] snapped " + stop.name() + " by " + String.format("%.1f", shiftKm) + "km");
                                    continue;
                                } else if (shiftKm > SNAP_MAX_KM) {
                                    System.out.println("[dcls-routes] snap " + String.format("%.1f", shiftKm) + "km away for " + stop.name() + ", keeping click");
                                }
                            } else if (near == null) {
                                System.out.println("[dcls-routes] snap missed for " + stop.name() + ", keeping clicked location");
                            }
                        }
                        snapped.add(stop);
                    }
                    List<Stop> ordered;
                    if (!optimizeOrder) {
                        ordered = snapped;
                    } else {
                        double[][] matrix = AppServices.OsrmRouter.tableKm(snapped);
                        ordered = matrix != null
                                ? AppServices.OsrmRouter.orderByMatrix(snapped, matrix)
                                : app.routeService.computeRoute(routeName, new ArrayList<>(snapped)).stops();
                    }
                    double km = 0;
                    boolean allRoad = true;
                    List<AppServices.OsrmRouter.LegResult> legs = new ArrayList<>();
                    for (int i = 1; i < ordered.size(); i++) {
                        if (Thread.currentThread().isInterrupted()) return;
                        Stop a = ordered.get(i - 1), b = ordered.get(i);
                        AppServices.OsrmRouter.LegResult leg =
                                AppServices.OsrmRouter.resolve(a.x(), a.y(), b.x(), b.y());
                        legs.add(leg); km += leg.km();
                        if (!leg.roadQuality()) allRoad = false;
                    }
                    double roadKm = Math.round(km * 10d) / 10d;
                    int minutes = Math.max(1, (int) Math.ceil(km / AppServices.BruneiPlaces.AVG_SPEED_KMH * 60));
                    RoutePlan plan = new RoutePlan(AppServices.id(), orderName(routeName), null, ordered,
                            roadKm, minutes, false, LocalDateTime.now());
                    boolean ready = allRoad;
                    int snappedCount = moved;
                    Platform.runLater(() -> {
                        if (gen != app.routePageGen) return;
                        if (ordered.size() < 2 || legs.size() != ordered.size() - 1) {
                            setStatus(statusPill, "Routing failed — showing pins", "status-warn");
                            previewMap.redraw();
                            return;
                        }
                        java.util.Set<String> previewIds = new java.util.HashSet<>();
                        for (Stop s : ordered) previewIds.add(s.id());
                        for (Stop s : app.routeStopsDraft) {
                            if (!previewIds.contains(s.id())) {
                                System.out.println("[dcls-routes] preview missing draft stop: " + s.name());
                            }
                        }
                        for (Stop s : snapped) {
                            int idx = indexById(app.routeStopsDraft, s.id());
                            if (idx >= 0) app.routeStopsDraft.set(idx, s);
                        }
                        planned[0] = plan; saved[0] = false;
                        app.previewStops = plan.stops();
                        dirty[0] = false;
                        routeResult.setText(plan.name() + "  ·  " + plan.stops().size() + " stops"
                                + (snappedCount > 0 ? "  ·  " + snappedCount + " pinned to roads" : ""));
                        heroKm.setText(String.format("%.1f km", plan.distanceKm()));
                        heroMin.setText("about " + plan.estimatedMinutes() + " min");
                        setStatus(statusPill, ready ? "Route ready" : "Approximate route (offline)",
                                ready ? "status-good" : "status-warn");
                        legRows.getChildren().clear();
                        highlightLeg[0] = -1;
                        for (int i = 0; i < legs.size(); i++) {
                            legRows.getChildren().add(legRow(i, legs.get(i).km(), highlightLeg, previewMap));
                        }
                        app.showEtaBreakdown(etaBreakdown, plan);
                        previewMap.manual = null;
                        previewMap.redraw();
                    });
                } catch (AppException exception) {
                    if (manual) Platform.runLater(() ->
                        app.alert(Alert.AlertType.WARNING, "Route not created", exception.getMessage()));
                    else Platform.runLater(() -> {
                        if (gen != app.routePageGen) return;
                        setStatus(statusPill, "Routing failed — showing pins", "status-warn");
                        previewMap.redraw();
                    });
                } catch (RuntimeException exception) {
                    exception.printStackTrace();
                    Platform.runLater(() -> {
                        if (gen != app.routePageGen) return;
                        setStatus(statusPill, "Routing failed — showing pins", "status-warn");
                        previewMap.redraw();
                    });
                }
            });
            thread.setDaemon(true);
            worker[0] = thread;
            thread.start();
        };
        // Pins first, blue line later: edits refresh markers instantly and restart the auto-calc.
        Runnable markDirty = () -> {
            if (gen != app.routePageGen) return;
            dirty[0] = true;
            planned[0] = null; saved[0] = false;
            int extra = app.routeStopsDraft.size() - 1;
            routeResult.setText(extra <= 0 ? "Add at least one delivery stop after the depot."
                    : extra + " stop" + (extra == 1 ? "" : "s") + " placed — calculating…");
            setStatus(statusPill, "Placing stops…", "status-info");
            heroKm.setText("—"); heroMin.setText("—");
            legRows.getChildren().clear();
            highlightLeg[0] = -1;
            etaBreakdown.getChildren().clear();
            previewMap.redraw();
            debounce.playFromStart();
        };
        DclsApp.ListViewWithRemove draft = new DclsApp.ListViewWithRemove(app.routeStopsDraft, () -> {
            if (app.routeStopsDraft.size() < 2) runCalcRef[0].accept(false);
            else markDirty.run();
        });
        VBox geocodeBox = new VBox(4);
        addStop.setOnAction(event -> {
            if (place.getValue() == null) return;
            Stop stop = app.routeService.place(place.getValue());
            if (stop != null && app.routeStopsDraft.stream().noneMatch(s -> s.name().equals(stop.name()))) {
                if (app.routeStopsDraft.isEmpty()) app.routeStopsDraft.add(AppServices.BruneiPlaces.DEPOT);
                app.routeStopsDraft.add(stop);
                stopConfidence.put(stop.id(), 1.0);
                confirmedStops.add(stop.id());
                markDirty.run();
            }
        });
        if (app.routeStopsDraft.isEmpty()) app.routeStopsDraft.add(AppServices.BruneiPlaces.DEPOT);
        // Handoff prefill: depot + geocoded order addresses on a worker (never the
        // UI thread). Low confidence blocks calc until confirmed in the panel below.
        final Thread[] prefillWorker = new Thread[1];
        Runnable prefillHandoff = () -> {
            if (handoffShipmentIds.isEmpty()) return;
            app.routeStopsDraft.clear();
            app.routeStopsDraft.add(AppServices.BruneiPlaces.DEPOT);
            stopConfidence.clear();
            confirmedStops.clear();
            lastGeocode.clear();
            geocodeBox.getChildren().clear();
            Label locating = new Label("Locating addresses…");
            locating.getStyleClass().add("muted");
            geocodeBox.getChildren().add(locating);
            dirty[0] = true;
            previewMap.redraw();
            if (prefillWorker[0] != null) prefillWorker[0].interrupt();
            List<String> ids = new ArrayList<>(handoffShipmentIds);
            Thread thread = new Thread(() -> {
                var places = new java.util.HashMap<String, Stop>();
                for (String n : app.routeService.placeNames()) places.put(n, app.routeService.place(n));
                record Pin(String sid, Stop pin, AddressGeocoder.GeocodedStop g) { }
                List<Pin> pins = new ArrayList<>();
                for (String sid : ids) {
                    if (Thread.currentThread().isInterrupted()) return;
                    var ship = app.repository.shipment(sid).orElse(null);
                    if (ship == null) continue;
                    AddressGeocoder.GeocodedStop g = AddressGeocoder.geocode(ship.address(), places);
                    pins.add(new Pin(sid,
                            new Stop("order-" + sid, g.stop().name() + " · " + sid, g.stop().x(), g.stop().y()), g));
                }
                Platform.runLater(() -> {
                    if (gen != app.routePageGen) return;
                    for (Pin p : pins) {
                        app.routeStopsDraft.add(p.pin());
                        stopConfidence.put(p.pin().id(), p.g().confidence());
                        if (p.g().confidence() >= AddressGeocoder.CONFIRM_THRESHOLD) confirmedStops.add(p.pin().id());
                        lastGeocode.put(p.pin().id(), p.g());
                    }
                    refreshConfirmCards(geocodeBox, markDirty, stopConfidence, confirmedStops, lastGeocode);
                    if (app.routeStopsDraft.size() >= 2) markDirty.run();
                    else { dirty[0] = true; previewMap.redraw(); }
                });
            });
            thread.setDaemon(true);
            prefillWorker[0] = thread;
            thread.start();
        };
        Button addNearby = button("Add nearby pending orders", "secondary-button");
        addNearby.setMaxWidth(Double.MAX_VALUE);
        addNearby.setOnAction(event -> {
            if (app.routeStopsDraft.isEmpty()) return;
            Stop anchor = app.routeStopsDraft.get(app.routeStopsDraft.size() - 1);
            var near = AddressGeocoder.nearbyPending(app.repository.shipments(), anchor.x(), anchor.y(), 5, 5);
            near.removeIf(s -> handoffShipmentIds.contains(s.id())
                    || app.routeStopsDraft.stream().anyMatch(p -> p.id().equals("order-" + s.id())));
            if (near.isEmpty()) { app.toast("No additional pending orders within 5km."); return; }
            addNearby.setDisable(true);
            Thread thread = new Thread(() -> {
                var places2 = new java.util.HashMap<String, Stop>();
                for (String n : app.routeService.placeNames()) places2.put(n, app.routeService.place(n));
                record Pin(String sid, Stop pin, AddressGeocoder.GeocodedStop g) { }
                List<Pin> pins = new ArrayList<>();
                for (var s : near) {
                    if (Thread.currentThread().isInterrupted()) return;
                    AddressGeocoder.GeocodedStop g = AddressGeocoder.geocode(s.address(), places2);
                    pins.add(new Pin(s.id(),
                            new Stop("order-" + s.id(), g.stop().name() + " · " + s.id(), g.stop().x(), g.stop().y()), g));
                }
                Platform.runLater(() -> {
                    if (gen != app.routePageGen) return;
                    addNearby.setDisable(false);
                    for (Pin p : pins) {
                        if (app.routeStopsDraft.stream().anyMatch(existing -> existing.id().equals(p.pin().id()))) continue;
                        app.routeStopsDraft.add(p.pin());
                        stopConfidence.put(p.pin().id(), p.g().confidence());
                        if (p.g().confidence() >= AddressGeocoder.CONFIRM_THRESHOLD) confirmedStops.add(p.pin().id());
                        lastGeocode.put(p.pin().id(), p.g());
                        handoffShipmentIds.add(p.sid());
                    }
                    refreshConfirmCards(geocodeBox, markDirty, stopConfidence, confirmedStops, lastGeocode);
                    markDirty.run();
                });
            });
            thread.setDaemon(true);
            thread.start();
        });
        final RoutePlan[] editing = {null};
        previewMap.clickAction = lonLat -> placePinAt(lonLat[0], lonLat[1], markDirty);
        optimize.setOnAction(event -> { debounce.stop(); runCalcRef[0].accept(true); });
        optimizeToggle.setOnAction(event -> markDirty.run());
        clickToggle.setOnAction(event -> {
            if (previewMap.clickAction != null) {
                previewMap.clickAction = null;
                clickToggle.setText("Map clicks: placing OFF");
            } else {
                previewMap.clickAction = lonLat -> placePinAt(lonLat[0], lonLat[1], markDirty);
                clickToggle.setText("Map clicks: placing ON");
            }
        });
        resetTrail.setText("Clear all");
        saveAssign.setOnAction(event -> {
            debounce.stop();
            if (planned[0] == null || dirty[0]) {
                runCalcRef[0].accept(true);
                if (planned[0] == null) return;
            }
            if (driver.getValue() == null) { app.alert(Alert.AlertType.INFORMATION, "Choose a driver", "Select a driver before saving and assigning this route."); return; }
            if (!saved[0]) {
                RoutePlan route = planned[0];
RoutePlan toSave = new RoutePlan(route.id(), route.name(), driver.getValue().id(), route.stops(), route.distanceKm(), route.estimatedMinutes(), route.completed(), route.createdAt());
                saveAssign.setDisable(true);
                app.runAsync(() -> {
                    app.repository.saveRoute(toSave);
                    // Link handoff orders to this route (risky orphan-route code removed: always link).
                    for (String sid : handoffShipmentIds) {
                        app.repository.shipment(sid).ifPresent(s -> {
                            s.setAssignments(s.driverId(), s.vehicleId(), toSave.id());
                            app.repository.saveShipment(s);
                        });
                    }
                }, () -> {
                    planned[0] = toSave; saved[0] = true; saveAssign.setDisable(false); editing[0] = null;
                    String linked = handoffShipmentIds.isEmpty() ? "" : " Linked to " + String.join(", ", handoffShipmentIds) + ".";
                    app.toast("Route " + toSave.name() + " saved and assigned to " + driver.getValue().name() + "." + linked);
                    handoffShipmentIds.clear();
                    app.showPage("routes");
                }, exception -> { saveAssign.setDisable(false); app.showError(app.readable(exception)); });
                return;
            }
            app.toast("Route " + planned[0].name() + " is already saved.");
        });
        // Pin hover names + click popups (badges carry numbers only).
        Tooltip pinTip = new Tooltip();
        Tooltip.install(previewMap.canvas, pinTip);
        previewMap.canvas.setOnMouseMoved(event -> {
            List<Stop> shown = dirty[0] ? app.routeStopsDraft : app.previewStops;
            int hit = pinAt(shown, previewMap, event.getX(), event.getY());
            if (hit < 0) { pinTip.hide(); return; }
            Stop stop = shown.get(hit);
            pinTip.setText((hit + 1) + ". " + stop.name()
                    + String.format("%n%.6f, %.6f", stop.x(), stop.y())
                    + (stop.id().startsWith("custom-") ? "\nCustom pin" : ""));
            if (!pinTip.isShowing()) pinTip.show(previewMap.canvas, event.getScreenX() + 12, event.getScreenY() + 12);
        });
        previewMap.canvas.setOnMouseExited(event -> pinTip.hide());
        Popup pinPopup = new Popup();
        pinPopup.setAutoHide(true);
        final double[] press = new double[2];
        previewMap.canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> { press[0] = event.getX(); press[1] = event.getY(); });
        previewMap.canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (Math.hypot(event.getX() - press[0], event.getY() - press[1]) > 5) return;
            List<Stop> shown = dirty[0] ? app.routeStopsDraft : app.previewStops;
            int hit = pinAt(shown, previewMap, event.getX(), event.getY());
            if (hit < 0) return;
            event.consume();
            pinTip.hide();
            showPinPopup(pinPopup, shown.get(hit), markDirty);
        });
        controls.getChildren().addAll(
                stepSection(1, "Add stops"),
                place, addStop, addNearby, clickToggle, geocodeBox,
                new Label("Delivery stops"), draft,
                stepSection(2, "Route"),
                optimizeToggle, statusPill, hero, routeResult, legRows, etaBreakdown, optimize, resetTrail,
                stepSection(3, "Assign & save"),
                new Label("Route name"), name, driver, saveAssign);
        controls.setPrefWidth(348); controls.setMinWidth(320); VBox.setVgrow(draft, ALWAYS);
        VBox mapPanel = panel(new Label("Route preview — click to place stops, the route draws itself")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
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
        Button editRoute = button("Edit selected route", "secondary-button"), deleteRoute = button("Delete selected route", "danger-button");
        editRoute.setDisable(true); deleteRoute.setDisable(true);
        editRoute.setOnAction(event -> {
            RoutePlan selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            editing[0] = selected; planned[0] = null; saved[0] = false;
            name.setText(selected.name()); app.routeStopsDraft.setAll(selected.stops());
            app.repository.users().stream().filter(user -> user.id().equals(selected.driverId())).findFirst().ifPresent(driver::setValue);
            app.previewStops = selected.stops(); previewMap.redraw();
            routeResult.setText("Editing " + selected.name() + ". Optimize and save to update this route.");
        });
        deleteRoute.setOnAction(event -> {
            RoutePlan selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && app.confirm("Delete route?", "Delete " + selected.name() + "? Routes assigned to orders cannot be removed.")) {
                app.runAction(() -> app.routeService.delete(selected), "Route deleted.", () -> app.showPage("routes"));
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            editRoute.setDisable(selected == null); deleteRoute.setDisable(selected == null);
        });
        HBox routeActions = new HBox(8, editRoute, deleteRoute);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            if (selected != null) {
                debounce.stop();
                app.previewStops = selected.stops();
                planned[0] = null; saved[0] = false;
                dirty[0] = false;
                routeResult.setText(selected.name() + "  ·  " + selected.stops().size() + " stops");
                heroKm.setText(String.format("%.1f km", selected.distanceKm()));
                heroMin.setText("about " + selected.estimatedMinutes() + " min");
                setStatus(statusPill, "Viewing saved route", "status-good");
                showLegEstimates.accept(selected.stops());
                app.showEtaBreakdown(etaBreakdown, selected);
                previewMap.manual = null;
                previewMap.redraw();
            }
        });
        // Blank by default: no auto-loaded saved route (removed error-prone initial trail).
        // Selecting a history row still previews it, but never mutates the draft.
        resetTrail.setOnAction(event -> {
            debounce.stop();
            if (worker[0] != null) worker[0].interrupt();
            app.routeStopsDraft.clear();
            app.previewStops = List.of();
            handoffShipmentIds.clear();
            geocodeBox.getChildren().clear();
            stopConfidence.clear();
            confirmedStops.clear();
            lastGeocode.clear();
            planned[0] = null; saved[0] = false;
            dirty[0] = true;
            highlightLeg[0] = -1;
            routeResult.setText("Add at least one delivery stop after the depot.");
            heroKm.setText("—"); heroMin.setText("—");
            setStatus(statusPill, "Add stops", "status-warn");
            legRows.getChildren().clear();
            etaBreakdown.getChildren().clear();
            previewMap.manual = null;
            previewMap.redraw();
        });
        prefillHandoff.run();
        previewMap.redraw();
        history.getChildren().addAll(table, routeActions); page.getChildren().addAll(builder, history); return page;
    }

    /** Rebuilds the locations panel from the current draft: one status line per
     *  order pin (with candidate buttons while unconfirmed) plus the Confirm-all
     *  footer whenever anything is still pending. Never clears other orders. */
    private void refreshConfirmCards(VBox geocodeBox, Runnable markDirty,
                                     Map<String, Double> stopConfidence, java.util.Set<String> confirmedStops,
                                     Map<String, AddressGeocoder.GeocodedStop> lastGeocode) {
        geocodeBox.getChildren().clear();
        for (Stop s : new ArrayList<>(app.routeStopsDraft)) {
            if (!s.id().startsWith("order-")) continue;
            String sid = s.id().substring("order-".length());
            AddressGeocoder.GeocodedStop g = lastGeocode.get(s.id());
            double c = stopConfidence.getOrDefault(s.id(), 0.0);
            boolean ok = confirmedStops.contains(s.id());
            Label line = new Label(sid + ": " + (g == null ? s.name() : g.note())
                    + String.format("%n%.0f%% · %s · %.6f, %.6f%s", c * 100,
                            g == null ? "placed" : g.method(), s.x(), s.y(), ok ? " (confirmed)" : ""));
            line.getStyleClass().add("muted");
            line.setWrapText(true);
            geocodeBox.getChildren().add(line);
            if (!ok && g != null) {
                for (Stop cand : g.candidates()) {
                    Stop alt = cand;
                    Button use = button("Use " + alt.name(), "secondary-button");
                    use.setMaxWidth(Double.MAX_VALUE);
                    use.setOnAction(e2 -> {
                        int idx = indexById(app.routeStopsDraft, s.id());
                        Stop confirmed = new Stop(s.id(), alt.name() + " · " + sid, alt.x(), alt.y());
                        if (idx >= 0) app.routeStopsDraft.set(idx, confirmed);
                        stopConfidence.put(s.id(), 1.0);
                        confirmedStops.add(s.id());
                        refreshConfirmCards(geocodeBox, markDirty, stopConfidence, confirmedStops, lastGeocode);
                        markDirty.run();
                    });
                    geocodeBox.getChildren().add(use);
                }
                Label hint = new Label("Confirm a candidate or drag the pin on the map to calculate.");
                hint.getStyleClass().add("muted");
                hint.setWrapText(true);
                geocodeBox.getChildren().add(hint);
            }
        }
        refreshConfirmFooter(geocodeBox, markDirty, stopConfidence, confirmedStops, lastGeocode);
    }

    /** One-click escape hatch: confirm every pending order pin as placed. */
    private void refreshConfirmFooter(VBox geocodeBox, Runnable markDirty,
                                      Map<String, Double> stopConfidence, java.util.Set<String> confirmedStops,
                                      Map<String, AddressGeocoder.GeocodedStop> lastGeocode) {
        geocodeBox.getChildren().removeIf(n -> Boolean.TRUE.equals(n.getProperties().get("confirmFooter")));
        long pending = app.routeStopsDraft.stream()
                .filter(s -> s.id().startsWith("order-") && !confirmedStops.contains(s.id()))
                .count();
        if (pending == 0) return;
        Label label = new Label(pending + " pin(s) need confirmation — or confirm all as placed.");
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        label.getProperties().put("confirmFooter", Boolean.TRUE);
        Button confirmAll = button("Confirm all " + pending + " pin(s) as placed", "primary-button");
        confirmAll.setMaxWidth(Double.MAX_VALUE);
        confirmAll.getProperties().put("confirmFooter", Boolean.TRUE);
        confirmAll.setOnAction(e -> {
            for (Stop s : app.routeStopsDraft) {
                if (s.id().startsWith("order-")) {
                    stopConfidence.put(s.id(), 1.0);
                    confirmedStops.add(s.id());
                }
            }
            app.toast(pending + " pin(s) confirmed as placed.");
            refreshConfirmCards(geocodeBox, markDirty, stopConfidence, confirmedStops, lastGeocode);
            markDirty.run();
        });
        geocodeBox.getChildren().addAll(label, confirmAll);
    }

    private void placePinAt(double lon, double lat, Runnable markDirty) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) return;
        if (lon < MapRenderer.MAP_LON_MIN || lon > MapRenderer.MAP_LON_MAX
                || lat < MapRenderer.MAP_LAT_MIN || lat > MapRenderer.MAP_LAT_MAX) return;
        // If an order pin still needs confirmation, move it to the clicked point = confirm.
        for (Stop s : List.copyOf(app.routeStopsDraft)) {
            if (s.id().startsWith("order-") && !app.routeStopConfirmed.contains(s.id())) {
                int idx = indexById(app.routeStopsDraft, s.id());
                if (idx >= 0) {
                    Stop moved = new Stop(s.id(), s.name(), lon, lat);
                    app.routeStopsDraft.set(idx, moved);
                    app.routeStopConfidence.put(s.id(), 1.0);
                    app.routeStopConfirmed.add(s.id());
                    markDirty.run();
                    return;
                }
            }
        }
        if (app.routeStopsDraft.isEmpty()) app.routeStopsDraft.add(AppServices.BruneiPlaces.DEPOT);
        Stop pin = new Stop("custom-" + AppServices.id(), "Custom pin " + (++app.customPinCount), lon, lat);
        app.routeStopsDraft.add(pin);
        app.routeStopConfidence.put(pin.id(), 1.0);
        app.routeStopConfirmed.add(pin.id());
        markDirty.run();
    }

    /** Order ids whose pins still block route calculation. Pure and headless-testable. */
    static List<String> pendingOrderIds(List<Stop> draft, java.util.Set<String> confirmedStops,
                                        Map<String, Double> stopConfidence) {
        if (draft == null) return List.of();
        return draft.stream()
                .filter(p -> p.id().startsWith("order-") && !confirmedStops.contains(p.id())
                        && stopConfidence.getOrDefault(p.id(), 0.0) < AddressGeocoder.CONFIRM_THRESHOLD)
                .map(p -> p.id().substring("order-".length()))
                .toList();
    }

    private static String orderName(String routeName) {
        return routeName == null || routeName.isBlank() ? "Delivery route" : routeName.trim();
    }

    private static int indexById(List<Stop> stops, String id) {
        for (int i = 0; i < stops.size(); i++) if (stops.get(i).id().equals(id)) return i;
        return -1;
    }

    private Label legRow(int leg, double km, int[] highlightLeg, DclsApp.MapController previewMap) {
        double legMin = km / AppServices.BruneiPlaces.AVG_SPEED_KMH * 60;
        Label row = new Label((leg + 1) + "→" + (leg + 2) + "  ·  " + String.format("%.1f km", km)
                + "  ·  ~" + Math.max(1, Math.round(legMin)) + " min");
        row.getStyleClass().add("leg-row");
        row.setOnMouseEntered(event -> { highlightLeg[0] = leg; previewMap.redraw(); });
        row.setOnMouseExited(event -> { highlightLeg[0] = -1; previewMap.redraw(); });
        return row;
    }

    private static int pinAt(List<Stop> shown, DclsApp.MapController previewMap, double px, double py) {
        if (shown == null || shown.isEmpty()) return -1;
        MapRenderer.ViewBounds view = previewMap.currentView();
        double w = previewMap.canvas.getWidth(), h = previewMap.canvas.getHeight();
        int hit = -1;
        double best = 14 * 14;
        for (int i = 0; i < shown.size(); i++) {
            double[] p = MapRenderer.projectIfVisible(shown.get(i).x(), shown.get(i).y(), w, h, view);
            if (p == null) continue;
            double d = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py);
            if (d <= best) { best = d; hit = i; }
        }
        return hit;
    }

    private void showPinPopup(Popup pinPopup, Stop stop, Runnable markDirty) {
        Label title = new Label(stop.name());
        title.getStyleClass().add("section-title");
        Label coords = new Label(String.format("%.6f, %.6f", stop.x(), stop.y()));
        coords.getStyleClass().add("muted");
        Button remove = button("Remove stop", "danger-button");
        remove.setOnAction(event -> {
            pinPopup.hide();
            if (!"DCLS Depot".equals(stop.name())) {
                int idx = indexById(app.routeStopsDraft, stop.id());
                if (idx >= 0) app.routeStopsDraft.remove(idx);
                app.routeStopConfidence.remove(stop.id());
                app.routeStopConfirmed.remove(stop.id());
                markDirty.run();
            }
        });
        Button confirm = button("Confirm location", "primary-button");
        boolean needsConfirm = stop.id().startsWith("order-") && !app.routeStopConfirmed.contains(stop.id());
        confirm.setVisible(needsConfirm);
        confirm.setManaged(needsConfirm);
        confirm.setOnAction(event -> {
            pinPopup.hide();
            app.routeStopConfidence.put(stop.id(), 1.0);
            app.routeStopConfirmed.add(stop.id());
            markDirty.run();
        });
        Button close = button("Close", "link-button");
        close.setOnAction(event -> pinPopup.hide());
        VBox card = new VBox(8, title, coords, new HBox(8, remove, confirm, close));
        card.getStyleClass().add("panel-tight");
        card.setPadding(new javafx.geometry.Insets(12));
        card.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        pinPopup.getContent().setAll(card);
        pinPopup.show(app.stage);
    }
}
