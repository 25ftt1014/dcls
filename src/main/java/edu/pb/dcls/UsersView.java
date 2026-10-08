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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Optional;

import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.pageHeader;
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
        Button add = button("Register user", "primary-button"); add.setOnAction(event -> userDialog(null));
        controls.getChildren().addAll(search, add);
        TableView<User> table = new TableView<>(); table.setPlaceholder(new Label("No accounts match this search."));
        table.getColumns().add(textCol("Name", User::name, 170));
        table.getColumns().add(textCol("Email", User::email, 210));
        table.getColumns().add(textCol("Phone", User::phone, 130));
        table.getColumns().add(textCol("Role", u -> u.role().label(), 110));
        table.getColumns().add(textCol("Status", u -> u.active() ? "Active" : "Inactive", 100));
        Runnable refresh = () -> { String q = search.getText().toLowerCase(Locale.ROOT).trim(); table.setItems(FXCollections.observableArrayList(app.repository.users().stream().filter(u -> (u.name()+" "+u.email()+" "+u.phone()).toLowerCase(Locale.ROOT).contains(q)).toList())); };
        search.textProperty().addListener((o, old, value) -> refresh.run()); refresh.run(); table.setPrefHeight(390); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        HBox actions = new HBox(8); Button edit = button("Edit account", "secondary-button"), toggle = button("Activate / deactivate", "secondary-button");
        edit.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) userDialog(selected); });
        toggle.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null && !selected.id().equals(app.currentUser.id())) { selected.setActive(!selected.active()); app.repository.saveUser(selected); refresh.run(); } });
        actions.getChildren().addAll(edit, toggle); page.getChildren().addAll(controls, table, actions); VBox.setVgrow(table, ALWAYS); return page;
    }

    private void userDialog(User existing) {
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField email = new TextField(existing == null ? "" : existing.email());
        TextField phone = new TextField(existing == null ? "" : existing.phone());
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values())); role.setValue(existing == null ? Role.DRIVER : existing.role());
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone), labeled("Role", role));
        if (existing == null) form.getChildren().add(labeled("Temporary password", UiFactory.passwordInput(password)));
        Optional<ButtonType> result = app.confirmDialog(existing == null ? "Register user" : "Edit user", form, existing == null ? "Create" : "Save");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new AppException("Enter a name and valid email address.");
                if (existing == null) {
                    if (password.getText().length() < 8) throw new AppException("Use a temporary password with at least 8 characters.");
                    Security.Credentials c = Security.credentials(password.getText().toCharArray());
                    app.repository.saveUser(new User(AppServices.id(), name.getText().trim(), email.getText().trim(), phone.getText().trim(), c.hash(), c.salt(), role.getValue(), true));
                } else {
                    existing.updateProfile(name.getText().trim(), email.getText().trim(), phone.getText().trim()); existing.setRole(role.getValue()); app.repository.saveUser(existing);
                }
                app.showPage("users");
            } catch (AppException exception) { app.alert(Alert.AlertType.WARNING, "User not saved", exception.getMessage()); }
        }
    }
}
