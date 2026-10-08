package edu.pb.dcls;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Optional;
import java.util.function.Function;

/**
 * Stateless builders for the common JavaFX widgets used across every screen
 * (buttons, panels, labelled fields, metric cards, table columns, form grids).
 * Screens pull these in with {@code import static edu.pb.dcls.UiFactory.*;}.
 */
final class UiFactory {
    private UiFactory() { }

    static Button button(String text, String style) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        return button;
    }

    static VBox panel(Node heading) {
        VBox box = new VBox(13);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("panel");
        box.getChildren().add(heading);
        return box;
    }

    static HBox labeled(String label, Node control) {
        VBox item = new VBox(5);
        Label text = new Label(label);
        text.getStyleClass().add("eyebrow");
        item.getChildren().addAll(text, control);
        return new HBox(item);
    }

    static Node metric(String title, String value, String caption) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setMinWidth(140);
        card.setPrefHeight(80);
        card.getStyleClass().add("panel-tight");
        Label t = new Label(title);
        t.getStyleClass().add("metric-caption");
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        Label c = new Label(caption);
        c.getStyleClass().add("muted");
        c.setStyle("-fx-font-size: 11px;");
        card.getChildren().addAll(t, v);
        if (caption != null && !caption.isBlank()) card.getChildren().add(c);
        return card;
    }

    static VBox pageHeader(String title, String subtitle) {
        VBox page = new VBox(14);
        page.setPadding(new Insets(15, 20, 22, 58));
        page.getStyleClass().add("workspace");
        return page;
    }

    static Node detailField(String title, String value) {
        VBox box = new VBox(4);
        Label heading = new Label(title);
        heading.getStyleClass().add("eyebrow");
        Label content = new Label(value == null || value.isBlank() ? "—" : value);
        content.setWrapText(true);
        content.getStyleClass().add("detail-value");
        box.getChildren().addAll(heading, content);
        return box;
    }

    static <T> TableColumn<T, String> textCol(String heading, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(heading);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(Optional.ofNullable(value.apply(cell.getValue())).orElse("—")));
        return column;
    }

    static <E extends Enum<E>> ChoiceBox<E> enumFilter(E[] values, String allLabel) {
        ChoiceBox<E> choice = new ChoiceBox<>(FXCollections.observableArrayList(values));
        choice.getItems().add(0, null);
        choice.setValue(null);
        choice.setConverter(new StringConverter<>() {
            public String toString(E value) { return value == null ? allLabel : value.toString(); }
            public E fromString(String value) { return null; }
        });
        return choice;
    }

    static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints left = new ColumnConstraints(145), right = new ColumnConstraints(280);
        grid.getColumnConstraints().addAll(left, right);
        return grid;
    }

    static void addField(GridPane grid, int row, String label, Node field) {
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
        if (field instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }

    static javafx.scene.Node passwordInput(javafx.scene.control.PasswordField password) {
        javafx.scene.control.TextField revealed = new javafx.scene.control.TextField();
        revealed.setPromptText(password.getPromptText());
        revealed.setAccessibleText(password.getAccessibleText());
        revealed.textProperty().bindBidirectional(password.textProperty());
        password.setPadding(new javafx.geometry.Insets(6, 56, 6, 8));
        revealed.setPadding(new javafx.geometry.Insets(6, 56, 6, 8));
        revealed.setManaged(false);
        revealed.setVisible(false);

        javafx.scene.control.Button toggle = button("Show", "password-toggle");
        toggle.setAccessibleText("Show password");
        toggle.setFocusTraversable(true);
        toggle.setStyle("-fx-background-color: transparent; -fx-text-fill: #176df6; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 5 7; -fx-cursor: hand;");
        javafx.scene.layout.StackPane input = new javafx.scene.layout.StackPane(password, revealed, toggle);
        javafx.scene.layout.StackPane.setAlignment(toggle, javafx.geometry.Pos.CENTER_RIGHT);
        javafx.scene.layout.StackPane.setMargin(toggle, new javafx.geometry.Insets(0, 5, 0, 0));
        toggle.setOnAction(event -> {
            boolean show = !revealed.isVisible();
            password.setVisible(!show);
            password.setManaged(!show);
            revealed.setVisible(show);
            revealed.setManaged(show);
            toggle.setText(show ? "Hide" : "Show");
            toggle.setAccessibleText(show ? "Hide password" : "Show password");
            if (show) {
                revealed.requestFocus();
                revealed.positionCaret(revealed.getText().length());
            } else {
                password.requestFocus();
                password.positionCaret(password.getText().length());
            }
        });
        return input;
    }
}
