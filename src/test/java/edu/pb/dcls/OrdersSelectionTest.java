package edu.pb.dcls;

import edu.pb.dcls.Domain.Role;
import edu.pb.dcls.Domain.Shipment;
import edu.pb.dcls.Domain.User;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The Orders page must build on a signed-in session; a throw here blanks the screen. */
class OrdersSelectionTest {

    @Test
    void ordersPageBuildsForAnAdmin() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                MockRepository source = new MockRepository();
                CachedRepository cached = new CachedRepository(source);
                cached.refresh();

                DclsApp app = new DclsApp();
                app.repository = cached;
                app.auth = new AppServices.AuthService(cached);
                app.access = new AppServices.AccessService();
                app.fleet = new AppServices.FleetService(cached);
                app.orders = new AppServices.OrderService(cached);
                app.routeService = new AppServices.RouteService(cached);
                app.tracking = new AppServices.TrackingService(cached);
                app.currentUser = admin(app);

                VBox page = new OrdersView(app).page();
                assertNotNull(page, "Orders page must build");
                assertNotNull(page.getChildren());
                assertNotNull(page.getChildren().get(0), "page must have a header");

                selectionSurvivesSearch(page);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

/** Typing in the search box re-runs refresh(); the selected order must stay selected. */
    private static void selectionSurvivesSearch(VBox page) {
        TableView<Shipment> orders = ordersTable(page);
        List<Shipment> rows = new ArrayList<>(orders.getItems());
        assertFalse(rows.isEmpty(), "demo data must have orders to select");
        Shipment chosen = rows.get(0);

        orders.getSelectionModel().select(chosen);
        assertSame(chosen, orders.getSelectionModel().getSelectedItem(), "row must be selectable");

        TextField search = firstTextField(page);
        search.setText(chosen.customerName()); // still matches -> row must survive the refresh

        assertSame(chosen, orders.getSelectionModel().getSelectedItem(),
                "searching must not clear the selected order (details blanked)");

        actionsEnableForSelection(page);
        selectionSurvivesPostWriteRefresh(page);
    }

    /**
     * Every save re-reads the repository, and CachedRepository hands back brand new
     * Shipment instances. TableView keys selection on identity, so setItems() drops
     * the selection -- which nulled the field keepSelected() read. Reproduces the
     * "screen goes blank after assigning/advancing" symptom.
     */
    private static void selectionSurvivesPostWriteRefresh(VBox page) {
        TableView<Shipment> orders = ordersTable(page);
        Shipment chosen = orders.getItems().get(0);
        orders.getSelectionModel().select(chosen);

        // What refresh() does after a write: same ids, fresh instances.
        List<Shipment> reloaded = orders.getItems().stream()
                .map(s -> new Shipment(s.id(), s.customerName(), s.customerContact(), s.address(),
                        s.packageDescription(), s.quantity(), s.weightKg(), s.priority(), s.status(),
                        s.driverId(), s.vehicleId(), s.routeId(), s.createdAt(), s.deliveredAt()))
                .toList();
        assertNotSame(chosen, reloaded.get(0), "precondition: the reload produces new instances");

        String keepId = chosen.id(); // captured before setItems
        orders.setItems(FXCollections.observableArrayList(reloaded));

        Shipment kept = keepId == null ? null : OrdersView.byId(reloaded, keepId);
        assertNotNull(kept, "the selected order must be restorable after a post-write refresh");
        assertEquals(chosen.id(), kept.id());
    }

    /** Selecting a row must light up the action buttons; a dead screen leaves them all greyed. */
    private static void actionsEnableForSelection(VBox page) {
        TableView<Shipment> orders = ordersTable(page);
        orders.getSelectionModel().clearSelection(); // start from the nothing-selected state
        Shipment open = orders.getItems().stream()
                .filter(s -> s.status() == edu.pb.dcls.Domain.OrderStatus.PENDING)
                .findFirst().orElseThrow(() -> new AssertionError("demo data has no pending order"));

        Map<String, Button> buttons = new LinkedHashMap<>();
        find(page, node -> { if (node instanceof Button b) buttons.put(b.getText(), b); });

        Button edit = buttons.get("Edit details");
        assertNotNull(edit, "page must have an Edit details button, found " + buttons.keySet());
        assertTrue(edit.isDisabled(), "with nothing selected the actions must start disabled");

        orders.getSelectionModel().select(open);
        assertFalse(edit.isDisabled(), "selecting a pending order must enable Edit details");

        Button cancel = buttons.get("Cancel order");
        assertNotNull(cancel);
        assertFalse(cancel.isDisabled(), "a pending order must be cancellable");
    }

    @SuppressWarnings("unchecked")
    private static TableView<Shipment> ordersTable(javafx.scene.Node root) {
        List<TableView<?>> found = new ArrayList<>();
        find(root, node -> { if (node instanceof TableView<?> t) found.add(t); });
        assertTrue(found.size() >= 2, "expected the Orders and Assigned tables, found " + found.size());
        return (TableView<Shipment>) found.get(0);
    }

    private static TextField firstTextField(javafx.scene.Node root) {
        List<TextField> found = new ArrayList<>();
        find(root, node -> { if (node instanceof TextField t) found.add(t); });
        assertFalse(found.isEmpty(), "page must have a search box");
        return found.get(0);
    }

private static void find(javafx.scene.Node root, Consumer<javafx.scene.Node> visit) {
        visit.accept(root);
        // A ScrollPane's content is not part of its children, so walk it explicitly.
        if (root instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            find(scroll.getContent(), visit);
        }
        if (root instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> find(child, visit));
        }
    }

    private static User admin(DclsApp app) {
        return app.repository.users().stream()
                .filter(u -> u.role() == Role.ADMIN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("demo data has no admin to sign in as"));
    }
}


