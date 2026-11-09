package com.auction.client;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

/** Prevents a login-success/dashboard-load regression without requiring a display. */
class DashboardFxmlContractTest {
    @Test void sellerIncrementAndBidRuleAreVisibleInFxml() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/com/auction/client/dashboard.fxml")) {
            assertNotNull(resource);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            boolean sellerInput = false, bidderRule = false, bidderCustomStep = false;
            for (int i = 0; i < document.getElementsByTagName("TextField").getLength(); i++) {
                Element field = (Element) document.getElementsByTagName("TextField").item(i);
                sellerInput |= "minimumIncrementField".equals(field.getAttribute("fx:id"));
                bidderCustomStep |= "incrementField".equals(field.getAttribute("fx:id"));
            }
            for (int i = 0; i < document.getElementsByTagName("Label").getLength(); i++) {
                Element label = (Element) document.getElementsByTagName("Label").item(i);
                bidderRule |= "bidRuleLabel".equals(label.getAttribute("fx:id"));
            }
            assertTrue(sellerInput, "Seller requires a minimum-increment input");
            assertTrue(bidderRule, "Bidder must see the server-defined minimum increment");
            assertTrue(bidderCustomStep, "Bidder may choose an auto-bid step above seller minimum");
        }
    }

    @Test void administratorHasAUserManagementPanel() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/com/auction/client/dashboard.fxml")) {
            assertNotNull(resource);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            var boxes = document.getElementsByTagName("VBox");
            boolean adminBox = false;
            for (int i = 0; i < boxes.getLength(); i++)
                adminBox |= "adminBox".equals(((Element) boxes.item(i)).getAttribute("fx:id"));
            assertTrue(adminBox, "ADMIN must have a management panel, not just a model class");
            var buttons = document.getElementsByTagName("Button");
            boolean lock = false, unlock = false;
            for (int i = 0; i < buttons.getLength(); i++) {
                String action = ((Element) buttons.item(i)).getAttribute("onAction");
                lock |= "#suspendUser".equals(action);
                unlock |= "#restoreUser".equals(action);
            }
            assertTrue(lock && unlock, "ADMIN must be able to block/re-enable accounts");
        }
    }

    @Test void administratorCanApproveOrRejectSubmittedAuctions() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/com/auction/client/dashboard.fxml")) {
            assertNotNull(resource);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            boolean queue = false, reason = false, approve = false, reject = false;
            var tables = document.getElementsByTagName("TableView");
            for (int i = 0; i < tables.getLength(); i++)
                queue |= "pendingTable".equals(((Element) tables.item(i)).getAttribute("fx:id"));
            var fields = document.getElementsByTagName("TextField");
            for (int i = 0; i < fields.getLength(); i++)
                reason |= "rejectionReasonField".equals(((Element) fields.item(i)).getAttribute("fx:id"));
            var buttons = document.getElementsByTagName("Button");
            for (int i = 0; i < buttons.getLength(); i++) {
                String action = ((Element) buttons.item(i)).getAttribute("onAction");
                approve |= "#approveAuction".equals(action);
                reject |= "#rejectAuction".equals(action);
            }
            assertTrue(queue && reason && approve && reject);
        }
    }

    @Test void dashboardExposesLogoutButtonForAllRoles() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/com/auction/client/dashboard.fxml")) {
            assertNotNull(resource);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            boolean found = false;
            var buttons = document.getElementsByTagName("Button");
            for (int i = 0; i < buttons.getLength(); i++) {
                Element button = (Element) buttons.item(i);
                if ("logoutButton".equals(button.getAttribute("fx:id"))) {
                    assertEquals("#logout", button.getAttribute("onAction"));
                    assertEquals("Đăng xuất", button.getAttribute("text"));
                    found = true;
                }
            }
            assertTrue(found, "Every signed-in role must see a logout button");
        }
    }

    @Test void tablePlaceholderIsAJavaFxNodeNotAnAttributeString() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/com/auction/client/dashboard.fxml")) {
            assertNotNull(resource, "Dashboard FXML must be on classpath");
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource);
            NodeList tables = document.getElementsByTagName("TableView");
            assertEquals(3, tables.getLength(), "Auctions, admin approval queue and user management tables must exist");
            for (int i = 0; i < tables.getLength(); i++) {
                Element table = (Element) tables.item(i);
                assertFalse(table.hasAttribute("placeholder"),
                        "Node-valued property cannot be a string");
                NodeList placeholders = table.getElementsByTagName("placeholder");
                assertEquals(1, placeholders.getLength());
                Element placeholder = (Element) placeholders.item(0);
                assertEquals(1, placeholder.getElementsByTagName("Label").getLength(),
                        "TableView.placeholder must receive a Label Node");
            }
        }
    }
}
