# Biên bản kiểm tra kỹ thuật — phiên bản nguồn

Trạng thái kiểm tra trong môi trường tạo dự án:

- ✅ **Đọc đủ** ba ZIP nguồn tham khảo và hai PDF hướng dẫn; đã đối chiếu chức năng, giao diện, lịch tuần 6–15.
- ✅ **JDK 21 backend**: biên dịch thành công source của `auction-common`, `auction-core`, `auction-server` với stub thư viện Gson trong thư mục tạm **ngoài dự án**. Đây là kiểm tra cú pháp / liên kết Java nội bộ, không phải Maven build thật.
- ✅ **JUnit source**: biên dịch cú pháp source của các lớp test bằng stub annotations/assertions JUnit trong thư mục tạm **ngoài dự án**.
- ✅ **Smoke test chạy thực tế** với in-memory repository: role + bid validation, auto-bid ngang ceiling, 40 bidder cạnh tranh đồng thời, history giá tăng nghiêm ngặt và gia hạn deadline.
- ✅ Tất cả `.fxml` và `pom.xml` parse được bằng XML parser; ID FXML và các callback khớp với controller.
- ✅ Có CI GitHub Actions gọi `./mvnw --batch-mode clean verify`, có Maven shade tạo JAR client/server; cấu hình được viết vào repo.
- ⚠️ **Chưa chạy** `./mvnw clean verify` với các dependency thực vì môi trường kiểm tra không cài Maven và không phân giải được domain tải dependency. Do đó JUnit/JaCoCo, liên kết native JavaFX, fat JAR và giao diện thực tế **chưa được xác nhận vận hành end-to-end** ở môi trường này.
- ⚠️ Không có bản JAR đã build trong gói ZIP; xuất JAR bằng `build.bat` hoặc `build.sh` ở máy có Internet + JDK 21.

## Việc cần làm trên máy nhóm (cổng nghiệm thu)

1. Chạy `mvnw.cmd clean verify` (Windows) hoặc `./mvnw clean verify` (Linux/macOS).
2. Sửa mọi lỗi build/test nếu xuất hiện trong môi trường cài đặt thực.
3. Mở `auction-core/target/site/jacoco/index.html`, đạt coverage >= 60% theo yêu cầu tuần 10 (chưa xác minh ở đây).
4. Chạy 3 client và server, xem kết quả test Socket `SocketRealtimeIntegrationTest` và kiểm tra giá/biểu đồ push realtime.
5. Dùng `build.bat` để tạo `dist/auction-server.jar` và `dist/auction-client.jar`, build JavaFX client trên cùng hệ điều hành với máy trình diễn.
6. Review mã nguồn và tạo commit/PR thật, theo đúng mốc tuần trong `docs/WEEKLY_MILESTONES.md`.

## Kiểm tra bản vá đăng nhập — 27/10/2026

- **Nguyên nhân xác định từ source:** `dashboard.fxml` dùng `TableView placeholder="Chưa có phiên đấu giá"` dù JavaFX yêu cầu `placeholder` là một `Node`. Sau khi server trả về `LOGIN` thành công, lệnh `FXMLLoader.load()` có thể ném lỗi; LoginController bắt lỗi nhưng không mở Dashboard.
- **Đã sửa:** chuyển sang `<placeholder><Label text="..."/></placeholder>`; đưa cấu hình vị trí chia `SplitPane` vào controller; sau `REGISTER` tự thực hiện `LOGIN` trên socket hiện có.
- **Kiểm tra chạy:** biên dịch cú pháp Java backend với Gson stub trên JDK 21; biên dịch cú pháp `AuctionClient` + `LoginController` bằng Java 21 với API stub; kiểm tra toàn bộ XML và `fx:id`/callback khớp controller; chạy smoke test domain 40 concurrent bids + auto-bid + anti-sniping. Đều đạt.
- **Giới hạn:** chưa thể chạy JavaFX/FXMLLoader thực và Maven unit test trong môi trường kiểm tra, vì thiếu dependency và GUI runtime. Do đó cần xác nhận đăng nhập và chuyển sang Dashboard sau khi build trên máy Windows.

## Bổ sung: Seller quyết định bước giá tối thiểu — 27/10/2026

- `CREATE`/`EDIT` nhận `minimumIncrement` (số nguyên VND > 0). Seller đổi được khi phiên ở trạng thái OPEN chưa nhận giá.
- `AuctionView` và JSON snapshot lưu bước giá; bản lưu cũ thiếu trường được khôi phục với mặc định 1 VND.
- `BID` kiểm tra `amount - currentPrice >= minimumIncrement`; `AUTO_BID` chỉ nhận giá tối đa, server dùng bước giá của Seller.
- Thêm 6 bài JUnit trong `AuctionServiceTest` (Seller rule, 2 auto bids, tie-breaking, sửa/lưu, legacy, concurrent).
- **Đã chạy offline:** biên dịch backend Java với Gson stub, chạy smoke test dùng in-memory StateRepository (bid hợp lệ/không hợp lệ, auto tie, auto ceiling lớn hơn, Seller edit, snapshot, legacy và 40 user concurrent), tất cả đạt. Source JUnit core compile với API stub; FXML/POM parse XML và liên kết fx:id/action thành công.
- **Chưa chạy:** Maven thực, JavaFX desktop và TCP Gson thực trong môi trường hiện tại. Trên Windows cần chạy `mvnw.cmd clean verify` trước demo.
