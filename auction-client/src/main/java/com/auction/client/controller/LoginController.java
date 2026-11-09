package com.auction.client.controller;

import com.auction.client.AuctionClient;
import com.auction.client.DesktopApp;
import com.auction.client.LanDiscoveryClient;
import com.auction.network.Packet;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Automatically discovers a LAN server; authenticates asynchronously, never blocking JavaFX. */
public final class LoginController {
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    @FXML private Label endpointLabel;
    @FXML private Button retryDiscoveryButton;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> roleChoice;
    @FXML private Button loginButton;
    @FXML private Button registerButton;
    @FXML private Label messageLabel;

    private LanDiscoveryClient.Endpoint discoveredServer;
    private boolean discovering;
    private boolean authenticating;

    @FXML private void initialize() {
        roleChoice.getItems().setAll("BIDDER", "SELLER");
        roleChoice.setValue("BIDDER");
        discoverServer();
    }

    /** "Tìm lại" is needed only if Server starts after Client or Wi-Fi temporarily drops. */
    @FXML private void discoverServer() {
        if (discovering || authenticating) return;
        discoveredServer = null;
        discovering = true;
        endpointLabel.setText("Đang tìm Server trong mạng Wi-Fi...");
        messageLabel.setText("Đang tìm máy chủ đấu giá, không cần nhập IP/port.");
        updateButtons();
        CompletableFuture.supplyAsync(() -> {
            try { return LanDiscoveryClient.discover(); }
            catch (IOException error) { throw new CompletionException(error); }
        }).whenComplete((endpoint, error) -> Platform.runLater(() -> {
            discovering = false;
            if (error != null) {
                endpointLabel.setText("Chưa tìm thấy Server");
                messageLabel.setText(rootMessage(error) + " Nhấn 'Tìm lại' sau khi kiểm tra.");
            } else {
                discoveredServer = endpoint;
                DesktopApp.configureServer(endpoint.host(), endpoint.port());
                endpointLabel.setText("Server: " + endpoint.host() + ":" + endpoint.port());
                messageLabel.setText("Đã tìm thấy Server. Có thể đăng nhập hoặc tạo tài khoản.");
            }
            updateButtons();
        }));
    }

    @FXML private void login() { authenticate(false); }
    @FXML private void register() { authenticate(true); }

    private void authenticate(boolean register) {
        LanDiscoveryClient.Endpoint endpoint = discoveredServer;
        if (endpoint == null || discovering) {
            messageLabel.setText("Chưa phát hiện Server. Hãy kiểm tra Wi-Fi và nhấn Tìm lại.");
            return;
        }
        if (authenticating) return;
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        if (username.isBlank() || password.isBlank()) {
            messageLabel.setText("Hãy nhập đầy đủ tên tài khoản và mật khẩu.");
            return;
        }
        JsonObject credentials = new JsonObject();
        credentials.addProperty("username", username);
        credentials.addProperty("password", password);
        JsonObject registerPayload = credentials.deepCopy();
        registerPayload.addProperty("role", roleChoice.getValue());
        authenticating = true;
        updateButtons();
        messageLabel.setText(register ? "Đang tạo tài khoản..." : "Đang đăng nhập...");

        CompletableFuture.supplyAsync(() -> {
            try { return new AuctionClient(endpoint.host(), endpoint.port()); }
            catch (IOException error) {
                throw new CompletionException(new IOException("Không kết nối được Server "
                        + endpoint.host() + ":" + endpoint.port(), error));
            }
        }).thenCompose(connection -> {
            CompletableFuture<Packet> response = connection.request(
                    register ? "REGISTER" : "LOGIN", register ? registerPayload : credentials)
                    .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (register) {
                response = response.thenCompose(first -> {
                    if (first == null || !first.success) return CompletableFuture.completedFuture(first);
                    return connection.request("LOGIN", credentials)
                            .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                });
            }
            return response.handle((packet, error) -> new LoginResult(connection, packet, error));
        }).whenComplete((result, failure) -> Platform.runLater(() -> finishLogin(result, failure)));
    }

    private void finishLogin(LoginResult result, Throwable failure) {
        authenticating = false;
        updateButtons();
        if (failure != null) {
            messageLabel.setText("Lỗi kết nối: " + rootMessage(failure)
                    + ". Nếu IP Server thay đổi, nhấn Tìm lại.");
            return;
        }
        if (result == null) {
            messageLabel.setText("Server không phản hồi.");
            return;
        }
        if (result.error() != null || result.packet() == null || !result.packet().success) {
            String message = result.error() != null ? rootMessage(result.error())
                    : result.packet() == null ? "Server không phản hồi." : result.packet().error;
            messageLabel.setText(message == null ? "Đăng nhập thất bại." : message);
            closeQuietly(result.client());
            return;
        }
        try {
            JsonObject user = result.packet().data.getAsJsonObject();
            DesktopApp.showDashboard(result.client(),
                    user.get("username").getAsString(), user.get("role").getAsString());
        } catch (Exception error) {
            error.printStackTrace(System.err);
            messageLabel.setText("Đăng nhập thành công nhưng không mở được Dashboard: "
                    + rootMessage(error) + " (xem Terminal).");
            closeQuietly(result.client());
        }
    }

    private void updateButtons() {
        boolean disabled = authenticating || discovering;
        loginButton.setDisable(disabled || discoveredServer == null);
        registerButton.setDisable(disabled || discoveredServer == null);
        retryDiscoveryButton.setDisable(disabled);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void closeQuietly(AuctionClient client) {
        try { client.close(); } catch (IOException ignored) { }
    }

    private record LoginResult(AuctionClient client, Packet packet, Throwable error) { }
}
