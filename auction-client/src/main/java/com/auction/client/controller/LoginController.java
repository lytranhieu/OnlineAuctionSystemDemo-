package com.auction.client.controller;

import com.auction.client.AuctionClient;
import com.auction.client.DesktopApp;
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

/** Handles the auth request asynchronously; navigation occurs on the JavaFX thread. */
public final class LoginController {
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    @FXML private Label endpointLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> roleChoice;
    @FXML private Button loginButton;
    @FXML private Button registerButton;
    @FXML private Label messageLabel;

    @FXML private void initialize() {
        roleChoice.getItems().setAll("BIDDER", "SELLER");
        roleChoice.setValue("BIDDER");
        endpointLabel.setText("SERVER  " + DesktopApp.serverHost() + ":" + DesktopApp.serverPort());
    }

    @FXML private void login() { authenticate(false); }
    @FXML private void register() { authenticate(true); }

    private void authenticate(boolean register) {
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
        setBusy(true);
        messageLabel.setText(register ? "Đang tạo tài khoản..." : "Đang đăng nhập...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return new AuctionClient(DesktopApp.serverHost(), DesktopApp.serverPort());
            } catch (IOException e) {
                throw new CompletionException(new IOException("Không kết nối được Server "
                        + DesktopApp.serverHost() + ":" + DesktopApp.serverPort(), e));
            }
        }).thenCompose(connection -> {
            // Registration creates an account only on the server. A second LOGIN request
            // authenticates the same socket so Dashboard can immediately request LIST.
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
        setBusy(false);
        if (failure != null) {
            messageLabel.setText("Lỗi kết nối: " + rootMessage(failure));
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
        } catch (Exception e) {
            // A successful LOGIN must not be reported as bad credentials when the
            // actual problem is an invalid dashboard.fxml or controller failure.
            e.printStackTrace(System.err);
            messageLabel.setText("Đăng nhập thành công nhưng không mở được Dashboard: "
                    + rootMessage(e) + " (xem Terminal). ");
            closeQuietly(result.client());
        }
    }

    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        registerButton.setDisable(busy);
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
