# Quản trị hệ thống và kiểm chứng quy tắc đấu giá

Tài liệu này áp dụng cho dự án Maven đa module `online-auction-system`. Không thay tên các class gốc như `Admin`, `AuctionService`, `Auction`, `DashboardController`.

## 1. Phân công trách nhiệm

| Thành phần | Vai trò |
|---|---|
| `auction-common/.../model/Admin.java` | Kế thừa `User`, trả về `Role.ADMIN` |
| `auction-common/.../dto/UserSummary.java` | DTO danh sách tài khoản: username, role, suspended; **không chứa mật khẩu / hash** |
| `auction-core/.../service/AuctionService.java` | Xác thực, phân quyền, quản lý trạng thái tài khoản, lưu dữ liệu, CRUD, bid, kết thúc, winner |
| `auction-common/.../model/Auction.java` | `ReentrantLock` bảo vệ trạng thái, giá, người dẫn đầu, lịch sử; `getWinner()` sau khi kết thúc |
| `auction-server/.../network/ClientHandler.java` | Route các lệnh JSON, kiểm tra session đang hoạt động trước mỗi yêu cầu |
| `auction-client/.../controller/DashboardController.java` | Hiển thị Admin UI, leader/winner, khóa thao tác không hợp lệ |
| `auction-client/.../resources/.../dashboard.fxml` | Giao diện quản lý tài khoản; giữ giao diện Bidder/Seller cũ |

## 2. Quyền và API Admin

Đăng ký `ADMIN` từ `REGISTER` bị chặn. Đặt `AUCTION_ADMIN_PASSWORD` trước lần khởi động đầu để tạo `admin`. `LOGIN` sử dụng cùng màn hình như Bidder/Seller.

| Request type | Đối tượng | Điều kiện |
|---|---|---|
| `USERS` | Admin | Trả danh sách `UserSummary` đã sắp xếp |
| `SUSPEND_USER` | Admin | Data `{ "username": "alice" }`, không khóa Admin |
| `RESTORE_USER` | Admin | Data `{ "username": "alice" }` |
| `CANCEL` | Admin hoặc Seller sở hữu | Phiên chưa PAID/CANCELED |
| `PAY` | Admin | Chỉ FINISHED có người thắng |

Mỗi Client đang đăng nhập đều được xác thực lại khi gọi API, nhờ đó khóa tài khoản có hiệu lực ngay cho các yêu cầu tiếp theo. Tài khoản bị khóa không được nhận các sự kiện đấu giá mới; tin nhắn đã xếp hàng trước thời điểm khóa có thể còn đến. Lịch sử bid vẫn được lưu để bảo toàn kết quả trước đó, nhưng đăng ký Auto-Bid chờ của tài khoản bị khóa sẽ bị loại bỏ.

Dữ liệu khóa được lưu tại `State.suspendedUsers`; khi đọc JSON cũ không có trường này, mặc định không ai bị khóa. Không thay đổi cấu trúc mật khẩu của `UserData`.

## 3. Trước phiên / Khi chạy / Sau phiên

| Tình huống | Kết quả yêu cầu |
|---|---|
| Tạo phiên tên trống, giá <= 0, bước tối thiểu <= 0, end <= start hoặc end ở quá khứ | Từ chối |
| `OPEN`, bid trước `startsAt` | Từ chối |
| `RUNNING`, giá mới < giá hiện tại + minimumIncrement | Từ chối |
| `RUNNING`, Bidder trả giá cao hơn mức tối thiểu | Chấp nhận; cập nhật leader, giá và lịch sử |
| Seller tự đặt giá / Admin đặt giá / tài khoản bị khóa đặt giá | Từ chối |
| Bid đồng thời | Xử lý tuần tự trong Service, cùng khóa ở Auction; giá lịch sử tăng hợp lệ |
| Bid cuối phiên | Gia hạn deadline +60s nếu còn <=30s |
| Hết hạn (`now >= endsAt`) | Chuyển FINISHED tự động |
| FINISHED có bid | `winner()` trả `highestBidder` |
| FINISHED không có bid | `winner()` trả null, Admin không thể đánh dấu PAID |
| CANCELED | `winner()` trả null dù còn lịch sử người từng dẫn đầu |
| FINISHED/PAID/CANCELED, tiếp tục đặt giá | Từ chối |
| FINISHED có người thắng → Admin PAY | Chuyển PAID, người thắng không đổi |

Dữ liệu `winner()` được suy ra từ trạng thái và `highestBidder` đã lưu, không tạo một bản sao winner độc lập để tránh lệch dữ liệu.

## 4. Kiểm thử

Chạy tại thư mục có `pom.xml`:

```bat
mvnw.cmd clean verify
```

Những lớp có kiểm thử: `AuctionServiceTest`, `SocketRealtimeIntegrationTest`, `DashboardFxmlContractTest`. Các ca mới kiểm tra không đặt giá trước/sau phiên, người thắng/không có người thắng, hủy phiên, phân quyền Admin, khóa/mở tài khoản và duy trì trạng thái khóa sau khi phục hồi dữ liệu.

**Môi trường xây dựng nguồn:** JDK 21 đã biên dịch các module bằng thư viện giả lập API Gson và JavaFX từ JAR tham khảo để kiểm tra cú pháp; bài chạy thử nghiệp vụ trên Server với `StateRepository` trong bộ nhớ đã thành công. Do không có Maven/dependency online, đây **không phải** xác nhận `mvn verify` hoặc chạy JavaFX thật; nhóm phải chạy lại trên Windows trước khi nộp.
