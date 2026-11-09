# Tự tìm Server qua Wi-Fi/LAN (UDP discovery)

## Mục tiêu

Mỗi máy chạy Client chỉ cần nằm cùng **broadcast LAN** với máy Server. Người dùng mở `run-client.bat`, ứng dụng sẽ tự tìm IP/port Server và hiện `Server: <IP>:<port>`. Không phải nhập IP hoặc port. Bấm **Đăng nhập** hay **Tạo tài khoản** như bình thường.

## Cơ chế

- Server tiếp tục xử lý mọi nghiệp vụ qua **TCP 5050** (hoặc port cấu hình bằng `--port=...`). Gson JSON-Line, Auto-Bid, Observer và logic nghiệp vụ không thay đổi.
- Server mở thêm **UDP 5051** (discovery) để trả lời thông điệp `AUCTIVA_DISCOVER_V1:<nonce>` bằng `AUCTIVA_SERVER_V1:<nonce>:<tcpPort>:<serverId>`.
- Client gửi các gói UDP broadcast đến broadcast address của từng interface Wi-Fi/Ethernet và địa chỉ toàn mạng. Server trả lời *unicast* về địa chỉ phát, Client lấy IP từ địa chỉ gói phản hồi, không tin IP do payload cung cấp.
- Sử dụng nonce để bỏ phản hồi cũ, dedup theo serverId để không nhầm một Server nhiều network adapter thành nhiều Server.
- Nếu có hai Server khác nhau trả lời, Client **không tự chọn ngẫu nhiên**, mà hiển thị cảnh báo; để tất cả Client dùng cùng một Server, hãy chỉ chạy một Server trên cùng mạng.
- Tìm Server chạy bất đồng bộ, không chặn JavaFX Application Thread. Nút **Tìm lại** dành cho trường hợp Server mở sau Client.
- Discovery chỉ tìm địa chỉ, **không xác thực máy chủ hay mã hóa mật khẩu**. Không dùng ở mạng công cộng hoặc mở cổng ra Internet; với triển khai ngoài LAN đáng tin cậy cần TLS và xác thực endpoint.

## Chạy trên Windows

1. Hai máy vào cùng mạng Wi-Fi (không dùng Guest Wi-Fi), để network profile máy Server là **Private**.
2. Build dự án tại thư mục có `pom.xml` bằng `mvnw.cmd clean verify` (hoặc build target JAR với `mvnw.cmd package`).
3. Chạy `run-server.bat` trên máy A. Console phải có `LAN discovery UDP 5051`.
4. Trên máy A, mở PowerShell **Run as Administrator** rồi chạy:

```powershell
New-NetFirewallRule -DisplayName "Auction TCP 5050 LAN" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5050 -Profile Private -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName "Auction UDP 5051 Discovery" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5051 -Profile Private -RemoteAddress LocalSubnet
```

5. Mở `run-client.bat` trên mỗi máy B/C trong cùng Wi-Fi. Sau tối đa ~2 giây, màn hình Login hiển thị địa chỉ Server tự tìm được và cho phép đăng nhập.
6. Nếu Client mở trước Server, đợi Server chạy rồi nhấn **Tìm lại**.
7. Dùng hai tài khoản Bidder trên hai máy, xem cùng một phiên, đặt bid và quan sát cập nhật realtime không cần Refresh.

## Khi không tìm thấy Server

- Server phải đang chạy **và** lắng nghe trên `0.0.0.0`, hoặc IP LAN thật. `127.0.0.1` chỉ phục vụ máy Server.
- Kiểm tra UDP 5051 **và** TCP 5050 đã được cho phép trong Firewall. `Test-NetConnection <ip> -Port 5050` chỉ kiểm tra TCP, không kiểm tra UDP broadcast.
- Các máy cần ở cùng **broadcast domain/subnet**. Một số router chặn client-to-client (AP isolation), Wi-Fi khách, VLAN, VPN hoặc broadcast; lúc đó tự phát hiện không hoạt động dù cùng tên Wi-Fi.
- Nếu Wi-Fi/Router chặn UDP broadcast, dùng mDNS/Bonjour với multicast được cho phép hoặc cấu hình DNS hostname nội bộ là giải pháp thay thế; không thể bảo đảm mọi Wi-Fi đều tự khám phá Server.
- Có thể kiểm tra UDP 5051 bằng Wireshark hoặc log ứng dụng. Khi máy Client đã tìm được Server nhưng không đăng nhập được, kiểm tra TCP 5050, tài khoản và dữ liệu trên Server.

## Các hệ điều hành khác

- macOS: cho phép ứng dụng Java nhận kết nối trong System Settings > Network > Firewall; phải cho phép UDP 5051/TCP 5050 nếu firewall bật.
- Linux UFW: cho phép hai cổng **chỉ từ subnet LAN đáng tin cậy**, ví dụ `sudo ufw allow from 192.168.1.0/24 to any port 5050 proto tcp` và tương tự `5051 proto udp` (điều chỉnh theo subnet thực tế).

## Bản cập nhật

- `auction-common/.../LanDiscoveryProtocol.java` — khai báo gói discovery.
- `auction-server/.../LanDiscoveryResponder.java`, `AuctionServer.java` — trả lời broadcast UDP, đóng socket khi shutdown.
- `auction-client/.../LanDiscoveryClient.java` — tự tìm một Server bằng UDP, kiểm tra nonce và địa chỉ.
- `auction-client/.../LoginController.java`, `login.fxml` — không cần trường IP/port; tự tìm khi mở giao diện; hiển thị tình trạng; `Tìm lại`.
- `auction-client/.../LanDiscoveryClientTest.java` — kiểm thử giao thức discovery cục bộ.
