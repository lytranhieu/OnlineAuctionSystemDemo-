package com.auction.client;

import com.auction.client.controller.DashboardController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Application entry point. Connection options are configuration, not login credentials. */
public final class DesktopApp extends Application {
    private static Stage window;
    private static String host;
    private static int port;

    @Override public void start(Stage stage) throws Exception {
        window = stage;
        host = System.getProperty("auction.host", System.getenv().getOrDefault("AUCTION_HOST", "127.0.0.1"));
        port = Integer.parseInt(System.getProperty("auction.port", System.getenv().getOrDefault("AUCTION_PORT", "5050")));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid AUCTION_PORT");
        stage.setTitle("AUCTIVA | Online Auction System");
        stage.setMinWidth(1050);
        stage.setMinHeight(700);
        showLogin();
        stage.show();
    }

    public static String serverHost() { return host; }
    public static int serverPort() { return port; }

    /** Save the selected endpoint for reconnects within this application session. */
    public static void configureServer(String serverHost, int serverPort) {
        if (serverHost == null || serverHost.isBlank() || serverPort < 1 || serverPort > 65535)
            throw new IllegalArgumentException("Invalid server host or port");
        host = serverHost;
        port = serverPort;
    }

    public static void showLogin() throws Exception {
        // Do not retain the close handler (and socket) of a previous account.
        window.setOnCloseRequest(null);
        FXMLLoader loader = new FXMLLoader(DesktopApp.class.getResource("/com/auction/client/login.fxml"));
        Scene scene = new Scene(loader.load(), 920, 570);
        scene.getStylesheets().add(DesktopApp.class.getResource("/com/auction/client/auction.css").toExternalForm());
        window.setScene(scene);
    }

    public static void showDashboard(AuctionClient client, String username, String role) throws Exception {
        FXMLLoader loader = new FXMLLoader(DesktopApp.class.getResource("/com/auction/client/dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1250, 825);
        scene.getStylesheets().add(DesktopApp.class.getResource("/com/auction/client/auction.css").toExternalForm());
        DashboardController controller = loader.getController();
        controller.initializeSession(client, username, role);
        window.setScene(scene);
        window.setOnCloseRequest(event -> { try { client.close(); } catch (Exception ignored) { } });
    }

    public static void main(String[] args) { launch(args); }
}
