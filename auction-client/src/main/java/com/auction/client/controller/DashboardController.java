package com.auction.client.controller;

import com.auction.client.AuctionClient;
import com.auction.dto.AuctionView;
import com.auction.dto.UserSummary;
import com.auction.model.*;
import com.auction.network.Packet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.control.SplitPane;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** View controller: delegates all state changes to the remote service. */
public final class DashboardController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<List<AuctionView>>() { }.getType();
    private final Type usersType = new TypeToken<List<UserSummary>>() { }.getType();
    private final javafx.collections.ObservableList<AuctionView> allAuctions = FXCollections.observableArrayList();
    @FXML private TextField searchField;
    private AuctionClient client;
    private String username;
    private String role;

    @FXML private Label accountLabel, feedbackLabel;
    @FXML private TableView<AuctionView> auctionTable;
    @FXML private TableColumn<AuctionView, String> nameCol, priceCol, statusCol, endCol;
    @FXML private TextArea detailArea, descriptionArea;
    @FXML private TextField bidField, maxBidField, incrementField;
    private String shownAuctionId;
    @FXML private Label bidRuleLabel;
    @FXML private TextField nameField, priceField, delayField, durationField, minimumIncrementField;
    @FXML private ComboBox<Category> categoryChoice;
    @FXML private VBox sellerBox, adminBox;
    @FXML private TableView<UserSummary> usersTable;
    @FXML private TableColumn<UserSummary, String> userNameCol, userRoleCol, userStatusCol;
    @FXML private Label adminSummaryLabel;
    @FXML private SplitPane mainSplit;
    @FXML private Button bidButton, autoButton, payButton, cancelButton;
    @FXML private javafx.scene.chart.LineChart<String, Number> historyChart;

    @FXML private void initialize() {
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        userNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().username()));
        userRoleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().role().name()));
        userStatusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().suspended() ? "Đã khóa" : "Đang hoạt động"));
        priceCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(money(c.getValue().currentPrice())));
        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().status().name()));
        endCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(TIME.format(Instant.ofEpochMilli(c.getValue().endsAt()))));
        categoryChoice.setItems(FXCollections.observableArrayList(Category.values()));
        categoryChoice.setValue(Category.ELECTRONICS);
        durationField.setText("10");
        delayField.setText("0");
        minimumIncrementField.setText("100000");
        auctionTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) showDetail(selected);
        });
        mainSplit.setDividerPositions(0.47);
        historyChart.setAnimated(false);
        historyChart.setCreateSymbols(true);
    }

    public void initializeSession(AuctionClient client, String username, String role) {
        this.client = client;
        this.username = username;
        this.role = role;
        accountLabel.setText(username + " (" + role + ")");
        boolean seller = "SELLER".equals(role);
        sellerBox.setVisible(seller);
        sellerBox.setManaged(seller);
        boolean admin = "ADMIN".equals(role);
        adminBox.setVisible(admin);
        adminBox.setManaged(admin);
        bidButton.setDisable(!"BIDDER".equals(role));
        autoButton.setDisable(!"BIDDER".equals(role));
        payButton.setVisible("ADMIN".equals(role));
        payButton.setManaged("ADMIN".equals(role));
        cancelButton.setVisible(seller || "ADMIN".equals(role));
        cancelButton.setManaged(seller || "ADMIN".equals(role));
        bidButton.setDisable(true);
        autoButton.setDisable(true);
        payButton.setDisable(true);
        cancelButton.setDisable(true);
        // Socket push, not timer polling: patch the current UI from the received event.
        client.onEvent(packet -> Platform.runLater(() -> applyEvent(packet)));
        refresh();
    }

    @FXML private void refresh() {
        if (client == null) return;
        call("LIST", new JsonObject(), response -> {
            String selection = selectedId();
            List<AuctionView> list = gson.fromJson(response.data, listType);
            allAuctions.setAll(list);
            filterAuctions();
            selectById(selection);
            if (auctionTable.getSelectionModel().getSelectedItem() == null) clearSelection();
        });
        if ("ADMIN".equals(role)) refreshUsers();
    }

    @FXML private void refreshUsers() {
        if (!"ADMIN".equals(role)) return;
        call("USERS", new JsonObject(), response -> {
            List<UserSummary> users = gson.fromJson(response.data, usersType);
            String selected = usersTable.getSelectionModel().getSelectedItem() == null ? null
                    : usersTable.getSelectionModel().getSelectedItem().username();
            usersTable.setItems(FXCollections.observableArrayList(users));
            if (selected != null) usersTable.getItems().stream()
                    .filter(u -> u.username().equals(selected)).findFirst()
                    .ifPresent(u -> usersTable.getSelectionModel().select(u));
            long suspended = users.stream().filter(UserSummary::suspended).count();
            adminSummaryLabel.setText("Tài khoản: " + users.size() + " • Đã khóa: " + suspended);
        });
    }

    @FXML private void suspendUser() { updateUserStatus(true); }
    @FXML private void restoreUser() { updateUserStatus(false); }

    private void updateUserStatus(boolean suspend) {
        UserSummary selected = usersTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showError("Hãy chọn tài khoản trong bảng quản trị"); return; }
        if (selected.role() == Role.ADMIN) { showError("Không thể khóa tài khoản Admin"); return; }
        if (selected.suspended() == suspend) {
            showError(suspend ? "Tài khoản đã bị khóa" : "Tài khoản đang hoạt động");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("username", selected.username());
        call(suspend ? "SUSPEND_USER" : "RESTORE_USER", data, response -> {
            feedbackLabel.setText((suspend ? "Đã khóa " : "Đã mở khóa ") + selected.username());
            refreshUsers();
        });
    }

    @FXML private void filterAuctions() {
        if (auctionTable == null) return;
        String query = searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String selection = selectedId();
        var filtered = allAuctions.stream().filter(a ->
                a.name().toLowerCase(java.util.Locale.ROOT).contains(query)
                || a.category().name().toLowerCase(java.util.Locale.ROOT).contains(query)
                || a.id().toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
        auctionTable.setItems(FXCollections.observableArrayList(filtered));
        selectById(selection);
    }

    /** Apply server-authoritative snapshots; no repetitive LIST polling. */
    private void applyEvent(Packet packet) {
        try {
            if (packet.data == null || !packet.data.isJsonObject()) return;
            JsonObject data = packet.data.getAsJsonObject();
            if (data.has("removedId")) {
                String id = data.get("removedId").getAsString();
                boolean wasSelected = id.equals(selectedId());
                allAuctions.removeIf(a -> a.id().equals(id));
                filterAuctions();
                if (wasSelected) clearSelection();
                return;
            }
            AuctionView updated = gson.fromJson(packet.data, AuctionView.class);
            if (updated == null) return;
            String previouslySelected = selectedId();
            int index = -1;
            for (int i = 0; i < allAuctions.size(); i++) {
                if (allAuctions.get(i).id().equals(updated.id())) { index = i; break; }
            }
            if (index < 0) allAuctions.add(updated);
            else allAuctions.set(index, updated);
            filterAuctions();
            selectById(previouslySelected);
            if (updated.id().equals(previouslySelected)) showDetail(updated);
        } catch (RuntimeException e) {
            feedbackLabel.setText("Lỗi sự kiện realtime: " + e.getMessage());
        }
    }

    private void selectById(String id) {
        if (id == null) return;
        auctionTable.getItems().stream().filter(a -> a.id().equals(id)).findFirst()
                .ifPresent(a -> auctionTable.getSelectionModel().select(a));
    }

    private void clearSelection() {
        shownAuctionId = null;
        incrementField.clear();
        detailArea.clear();
        bidRuleLabel.setText("Bước giá tối thiểu: chọn một phiên đấu giá");
        historyChart.getData().clear();
        bidButton.setDisable(true);
        autoButton.setDisable(true);
        cancelButton.setDisable(true);
        payButton.setDisable(true);
    }

    private void showDetail(AuctionView view) {
        // Do not reset a bidder's preferred step on every realtime update.
        if (!view.id().equals(shownAuctionId)) {
            shownAuctionId = view.id();
            incrementField.setText(Long.toString(view.minimumIncrement()));
        }
        String text = "ID: " + view.id() + "\nSeller: " + view.seller()
                + "\nName: " + view.name() + " (" + view.category() + ")"
                + "\nDescription: " + view.description()
                + "\nStarting: " + money(view.startingPrice()) + " VND"
                + "\nCurrent: " + money(view.currentPrice()) + " VND"
                + "\nSeller minimum increment: " + money(view.minimumIncrement()) + " VND"
                + "\nĐang dẫn đầu: " + (view.highestBidder() == null ? "Chưa có" : view.highestBidder())
                + "\nKết quả: " + resultText(view)
                + "\nStatus: " + view.status()
                + "\nStarts: " + TIME.format(Instant.ofEpochMilli(view.startsAt()))
                + "\nEnds: " + TIME.format(Instant.ofEpochMilli(view.endsAt()))
                + "\nAccepted bids: " + view.history().size();
        detailArea.setText(text);
        updateAvailableActions(view);
        bidRuleLabel.setText("Seller yêu cầu bước tối thiểu: " + money(view.minimumIncrement())
                + " VND   •   Bidder có thể tăng nhiều hơn   •   Giá đặt tiếp theo ≥ "
                + money(view.currentPrice() > Long.MAX_VALUE - view.minimumIncrement()
                        ? Long.MAX_VALUE : view.currentPrice() + view.minimumIncrement()) + " VND");
        var series = new XYChart.Series<String, Number>();
        series.setName(view.name());
        int index = 0;
        series.getData().add(new XYChart.Data<>(TIME.format(Instant.ofEpochMilli(view.startsAt())) + " #0", view.startingPrice()));
        for (BidTransaction bid : view.history()) {
            String label = TIME.format(Instant.ofEpochMilli(bid.at())) + " #" + (++index);
            series.getData().add(new XYChart.Data<>(label, bid.amount()));
        }
        historyChart.getData().setAll(series);
        if ("SELLER".equals(role) && view.seller().equals(username)) {
            nameField.setText(view.name());
            descriptionArea.setText(view.description());
            priceField.setText(Long.toString(view.startingPrice()));
            minimumIncrementField.setText(Long.toString(view.minimumIncrement()));
            categoryChoice.setValue(view.category());
            long delay = Math.max(0, (view.startsAt() - System.currentTimeMillis() + 59_999) / 60_000);
            delayField.setText(Long.toString(delay));
            durationField.setText(Long.toString(Math.max(1, (view.endsAt() - view.startsAt()) / 60_000)));
        }
    }

    private static String resultText(AuctionView view) {
        if (view.status() == Status.CANCELED) return "Phiên bị hủy, không có người thắng";
        if (view.status() == Status.FINISHED || view.status() == Status.PAID)
            return view.winner() == null ? "Kết thúc không có người thắng"
                    : "Người thắng: " + view.winner() + " — " + money(view.currentPrice()) + " VND";
        return "Chưa kết thúc phiên đấu giá";
    }

    private void updateAvailableActions(AuctionView view) {
        boolean running = view.status() == Status.RUNNING;
        bidButton.setDisable(!"BIDDER".equals(role) || !running);
        autoButton.setDisable(!"BIDDER".equals(role) || !running);
        boolean owner = "SELLER".equals(role) && username.equals(view.seller());
        boolean admin = "ADMIN".equals(role);
        cancelButton.setDisable(!(owner || admin) || view.status() == Status.PAID
                || view.status() == Status.CANCELED);
        payButton.setDisable(!admin || view.status() != Status.FINISHED || view.winner() == null);
    }

    @FXML private void bid() {
        String id = selectedId();
        if (id == null) { showError("Select an auction"); return; }
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("amount", bidField.getText().trim());
        call("BID", data, p -> { feedbackLabel.setText("Bid accepted"); refresh(); });
    }

    @FXML private void autoBid() {
        AuctionView selected = auctionTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showError("Select an auction"); return; }
        try {
            long step = Long.parseLong(incrementField.getText().trim());
            long ceiling = Long.parseLong(maxBidField.getText().trim());
            if (step < selected.minimumIncrement()) {
                showError("Bước giá Auto-Bid phải ≥ " + money(selected.minimumIncrement()) + " VND");
                return;
            }
            if (ceiling <= selected.currentPrice() || ceiling - selected.currentPrice() < step) {
                showError("Giá tối đa phải cao hơn giá hiện tại ít nhất " + money(step) + " VND");
                return;
            }
            JsonObject data = new JsonObject();
            data.addProperty("id", selected.id());
            data.addProperty("maxBid", ceiling);
            data.addProperty("increment", step);
            call("AUTO_BID", data, p -> { feedbackLabel.setText("Auto-Bid đã bật (bước giá: "
                    + money(step) + " VND)"); refresh(); });
        } catch (NumberFormatException ex) {
            showError("Giá tối đa và bước giá phải là số nguyên VND hợp lệ");
        }
    }

    @FXML private void create() { modify("CREATE"); }
    @FXML private void edit() { modify("EDIT"); }

    private void modify(String type) {
        if ("EDIT".equals(type) && selectedId() == null) { showError("Select an auction"); return; }
        try {
            long price = Long.parseLong(priceField.getText().trim());
            long minimumIncrement = Long.parseLong(minimumIncrementField.getText().trim());
            if (price < 1 || minimumIncrement < 1 || minimumIncrement > Long.MAX_VALUE - price)
                throw new IllegalArgumentException("Bước giá phải là số dương và phù hợp giá khởi điểm");
            long delay = Long.parseLong(delayField.getText().trim());
            long duration = Long.parseLong(durationField.getText().trim());
            if (delay < 0 || duration <= 0 || delay > 525_600 || duration > 525_600)
                throw new IllegalArgumentException("Minutes must be positive and no more than one year");
            long start = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(delay, 60_000));
            long end = Math.addExact(start, Math.multiplyExact(duration, 60_000));
            JsonObject data = new JsonObject();
            data.addProperty("id", selectedId());
            data.addProperty("name", nameField.getText());
            data.addProperty("description", descriptionArea.getText());
            data.addProperty("category", categoryChoice.getValue().name());
            data.addProperty("price", price);
            data.addProperty("minimumIncrement", minimumIncrement);
            data.addProperty("startsAt", start);
            data.addProperty("endsAt", end);
            call(type, data, p -> { feedbackLabel.setText("Saved successfully"); refresh(); });
        } catch (Exception e) { showError("Invalid product values: " + e.getMessage()); }
    }

    @FXML private void delete() { actionOnSelected("DELETE"); }
    @FXML private void cancel() { actionOnSelected("CANCEL"); }
    @FXML private void pay() { actionOnSelected("PAY"); }

    private void actionOnSelected(String type) {
        if (selectedId() == null) { showError("Select an auction"); return; }
        JsonObject data = new JsonObject();
        data.addProperty("id", selectedId());
        call(type, data, p -> { feedbackLabel.setText(type + " successful"); refresh(); });
    }

    private void call(String type, JsonObject data, Consumer<Packet> success) {
        client.request(type, data).whenComplete((packet, exception) -> Platform.runLater(() -> {
            if (exception != null) showError(exception.getMessage());
            else if (!packet.success) showError(packet.error);
            else success.accept(packet);
        }));
    }

    private String selectedId() {
        AuctionView view = auctionTable.getSelectionModel().getSelectedItem();
        return view == null ? null : view.id();
    }

    private void showError(String error) { feedbackLabel.setText("Error: " + error); }
    private static String money(long amount) { return String.format("%,d", amount); }
}
