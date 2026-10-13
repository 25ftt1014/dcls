package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import java.util.Optional;

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
        edit.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) userDialog(selected); });
        approve.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) approveDialog(selected); });
        suspend.setOnAction(event -> {
            User selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.id().equals(app.currentUser.id())) return;
            User updated = copy(selected); updated.setActive(false);
            app.runAction(() -> app.repository.saveUser(updated), "Account suspended.", refresh);
        });
        reject.setOnAction(event -> {
            User selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.id().equals(app.currentUser.id()) || !selected.pending()) return;
            if (!app.confirm("Reject account", "Permanently delete " + selected.name() + " (" + selected.email() + ")? This cannot be undone.")) return;
            app.runAction(() -> app.repository.deleteUser(selected.id()), "Account deleted.", refresh);
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
        app.confirmForm("Approve account", form, "Approve", complete -> {
            if (role.getValue() == null) { complete.accept(new AppException("Choose a role before approving this account.")); return; }
            User updated = copy(user); updated.setRole(role.getValue()); updated.setActive(true); updated.setPending(false);
            app.runAsync(() -> app.repository.saveUser(updated), () -> { complete.accept(null); app.showPage("users"); }, complete);
        });
    }

    private void userDialog(User existing) {
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField email = new TextField(existing == null ? "" : existing.email());
        TextField phone = new TextField(existing == null ? "" : existing.phone());
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values())); role.setValue(existing == null ? Role.DRIVER : existing.role());
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone), labeled("Role", role));
        if (existing == null) form.getChildren().add(labeled("Temporary password", UiFactory.passwordInput(password)));
        app.confirmForm(existing == null ? "Register user" : "Edit user", form, existing == null ? "Create" : "Save", complete -> {
            try {
                if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new AppException("Enter a name and valid email address.");
                String fullName = name.getText().trim(), address = email.getText().trim(), contact = phone.getText().trim();
                Role selectedRole = role.getValue(); char[] secret = password.getText().toCharArray();
                if (existing == null && secret.length < 8) throw new AppException("Use a temporary password with at least 8 characters.");
                app.runAsync(() -> {
                    User updated;
                    if (existing == null) {
                        Security.Credentials credentials = Security.credentials(secret);
                        updated = new User(AppServices.id(), fullName, address, contact, credentials.hash(), credentials.salt(), selectedRole, true, false);
                    } else {
                        updated = copy(existing); updated.updateProfile(fullName, address, contact); updated.setRole(selectedRole);
                    }
                    app.repository.saveUser(updated);
                }, () -> { complete.accept(null); app.showPage("users"); }, complete);
            } catch (RuntimeException exception) { complete.accept(exception); }
        });
    }

    private User copy(User user) {
        return new User(user.id(), user.name(), user.email(), user.phone(), user.passwordHash(), user.passwordSalt(), user.role(), user.active(), user.pending());
    }
}
