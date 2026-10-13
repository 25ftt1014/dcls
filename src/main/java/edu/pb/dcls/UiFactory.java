package edu.pb.dcls;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Optional;
import java.util.function.Consumer;
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

    /** A metric card plus handles on the two labels the dashboard ticks live. */
    record Metric(VBox card, Label value, Label caption) { }

    static Metric metric(String title, String value, String caption) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setMinWidth(140);
        card.setMinHeight(80);
        card.getStyleClass().add("panel-tight");
        Label t = new Label(title);
        t.getStyleClass().add("metric-caption");
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        Label c = new Label(caption == null ? "" : caption);
        c.getStyleClass().add("muted");
        c.setStyle("-fx-font-size: 11px;");
        c.setWrapText(true);
        card.getChildren().addAll(t, v, c);
        return new Metric(card, v, c);
    }

    static VBox pageHeader(String title, String subtitle) {
        VBox page = new VBox(14);
        page.setPadding(new Insets(15, 24, 22, 28));
        page.getStyleClass().add("workspace");
        // Render the (previously discarded) descriptive subtitle so each screen
        // has a clear heading hierarchy, not just a bare toolbar.
        if (subtitle != null && !subtitle.isBlank()) {
            Label caption = new Label(subtitle);
            caption.getStyleClass().add("page-subtitle");
            caption.setWrapText(true);
            page.getChildren().add(caption);
        }
        return page;
    }

    /** Maps a human status label to a pill style class, so colour is consistent everywhere. */
    static String pillClassFor(String label) {
        if (label == null) return "status-info";
        return switch (label) {
            case "Available", "Delivered", "Active" -> "status-good";
            case "In Delivery", "In Transit", "Assigned" -> "status-info";
            case "Under Maintenance", "Pending" -> "status-warn";
            case "Unavailable", "Cancelled", "Inactive" -> "status-bad";
            default -> "status-info";
        };
    }

    /** A table column that renders its value as a coloured status pill instead of plain text. */
    static <T> TableColumn<T, String> statusCol(String heading, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(heading);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(Optional.ofNullable(value.apply(cell.getValue())).orElse("—")));
        column.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Label pill = new Label(item);
                pill.getStyleClass().addAll("status-pill", pillClassFor(item));
                setGraphic(pill);
                setText(null);
            }
        });
        return column;
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

    /** Opens {@code action} for a row when it is double-clicked (a common, expected table affordance). */
    static <T> void onRowDoubleClick(TableView<T> table, Consumer<T> action) {
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && !row.isEmpty()) action.accept(row.getItem()); });
            return row;
        });
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
