package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.onRowDoubleClick;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.statusCol;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Admin-only account and role management screen. */
final class UsersView {
    private final DclsApp app;

    UsersView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Account & role management", "Manage access, roles, and account status.");
        HBox controls = new HBox(8); controls.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search name, email, or phone"); search.setPrefWidth(320);
        ToggleButton pendingOnly = new ToggleButton("Pending only"); pendingOnly.getStyleClass().add("filter-toggle");
        Button add = button("Register user", "primary-button"); add.setOnAction(event -> userDialog(null));
        controls.getChildren().addAll(search, pendingOnly, add);
        TableView<User> table = new TableView<>(); table.setPlaceholder(new Label("No accounts match this search."));
        onRowDoubleClick(table, this::userDialog);
        table.getColumns().add(textCol("Name", User::name, 170));
        table.getColumns().add(textCol("Email", User::email, 210));
        table.getColumns().add(textCol("Phone", User::phone, 130));
        table.getColumns().add(textCol("Role", u -> u.role().label(), 110));
<<<<<<< HEAD
        table.getColumns().add(textCol("Status", u -> u.pending() ? "Pending approval" : u.active() ? "Active" : "Suspended", 140));
        Runnable refresh = () -> {
            List<User> all = app.repository.users();
            String q = search.getText().toLowerCase(Locale.ROOT).trim();
            List<User> shown = all.stream()
                    .filter(u -> !pendingOnly.isSelected() || u.pending())
                    .filter(u -> (u.name()+" "+u.email()+" "+u.phone()).toLowerCase(Locale.ROOT).contains(q)).toList();
            table.setItems(FXCollections.observableArrayList(shown));
            long waiting = all.stream().filter(User::pending).count();
            pendingOnly.setText(waiting == 0 ? "Pending only" : waiting + " pending");
            table.setPlaceholder(new Label(shown.isEmpty() && pendingOnly.isSelected()
                    ? "No accounts are waiting for approval." : "No accounts match this search."));
        };
        search.textProperty().addListener((o, old, value) -> refresh.run());
        pendingOnly.selectedProperty().addListener((o, old, value) -> refresh.run());
        refresh.run(); table.setPrefHeight(390); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Button edit = button("Edit account", "secondary-button");
        Button approve = button("Approve…", "primary-button");
        Button suspend = button("Suspend", "secondary-button");
        Button reject = button("Reject", "danger-button");
=======
        table.getColumns().add(statusCol("Status", u -> u.active() ? "Active" : "Inactive", 100));
        Runnable refresh = () -> { String q = search.getText().toLowerCase(Locale.ROOT).trim(); table.setItems(FXCollections.observableArrayList(app.repository.users().stream().filter(u -> (u.name()+" "+u.email()+" "+u.phone()).toLowerCase(Locale.ROOT).contains(q)).toList())); };
        search.textProperty().addListener((o, old, value) -> refresh.run()); refresh.run(); table.setPrefHeight(390); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        HBox actions = new HBox(8); Button edit = button("Edit account", "secondary-button"), toggle = button("Activate / deactivate", "secondary-button");
>>>>>>> origin/feature/ux-polish
        edit.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) userDialog(selected); });
        approve.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) approveDialog(selected); });
        suspend.setOnAction(event -> {
            User selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.id().equals(app.currentUser.id())) return;
            selected.setActive(false); app.repository.saveUser(selected); refresh.run();
        });
        reject.setOnAction(event -> {
            User selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.id().equals(app.currentUser.id()) || !selected.pending()) return;
            if (!app.confirm("Reject account", "Permanently delete " + selected.name() + " (" + selected.email() + ")? This cannot be undone.")) return;
            try { app.repository.deleteUser(selected.id()); refresh.run(); }
            catch (AppException exception) { app.alert(Alert.AlertType.WARNING, "Account not removed", exception.getMessage()); }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> {
            approve.setDisable(value == null || !value.pending());
            suspend.setDisable(value == null || value.pending() || !value.active() || value.id().equals(app.currentUser.id()));
            reject.setDisable(value == null || !value.pending() || value.id().equals(app.currentUser.id()));
        });
        approve.setDisable(true); suspend.setDisable(true); reject.setDisable(true);
        HBox actions = new HBox(8, edit, approve, suspend, reject);
        page.getChildren().addAll(controls, table, actions); VBox.setVgrow(table, ALWAYS); return page;
    }

    /** Approving forces an explicit role choice, so nobody gains access without an admin deciding what they get. */
    private void approveDialog(User user) {
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values()));
        role.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(10,
                new Label(user.name() + " (" + user.email() + ") is waiting for approval."),
                labeled("Role to grant", role));
        form.setPrefWidth(360);
        Optional<ButtonType> result = app.confirmDialog("Approve account", form, "Approve");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            if (role.getValue() == null) { app.alert(Alert.AlertType.WARNING, "Role required", "Choose a role before approving this account."); return; }
            user.setRole(role.getValue()); user.setActive(true); user.setPending(false);
            app.repository.saveUser(user); app.showPage("users");
        }
    }

    private void userDialog(User existing) {
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField email = new TextField(existing == null ? "" : existing.email());
        TextField phone = new TextField(existing == null ? "" : existing.phone());
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values())); role.setValue(existing == null ? Role.DRIVER : existing.role());
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone), labeled("Role", role));
        if (existing == null) form.getChildren().add(labeled("Temporary password", UiFactory.passwordInput(password)));
<<<<<<< HEAD
        Optional<ButtonType> result = app.confirmDialog(existing == null ? "Register user" : "Edit user", form, existing == null ? "Create" : "Save");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new AppException("Enter a name and valid email address.");
                if (existing == null) {
                    if (password.getText().length() < 8) throw new AppException("Use a temporary password with at least 8 characters.");
                    Security.Credentials c = Security.credentials(password.getText().toCharArray());
                    app.repository.saveUser(new User(AppServices.id(), name.getText().trim(), email.getText().trim(), phone.getText().trim(), c.hash(), c.salt(), role.getValue(), true, false));
                } else {
                    existing.updateProfile(name.getText().trim(), email.getText().trim(), phone.getText().trim()); existing.setRole(role.getValue()); app.repository.saveUser(existing);
                }
                app.showPage("users");
            } catch (AppException exception) { app.alert(Alert.AlertType.WARNING, "User not saved", exception.getMessage()); }
        }
=======
        app.confirmForm(existing == null ? "Register user" : "Edit user", form, existing == null ? "Create" : "Save", () -> {
            if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new AppException("Enter a name and valid email address.");
            if (existing == null) {
                if (password.getText().length() < 8) throw new AppException("Use a temporary password with at least 8 characters.");
                Security.Credentials c = Security.credentials(password.getText().toCharArray());
                app.repository.saveUser(new User(AppServices.id(), name.getText().trim(), email.getText().trim(), phone.getText().trim(), c.hash(), c.salt(), role.getValue(), true));
            } else {
                existing.updateProfile(name.getText().trim(), email.getText().trim(), phone.getText().trim()); existing.setRole(role.getValue()); app.repository.saveUser(existing);
            }
            app.showPage("users");
        });
>>>>>>> origin/feature/ux-polish
    }
}
