# Review và bản vá LAN — Online-Auction-System(3).zip

## Phạm vi

Đối chiếu bản ZIP nguồn tải lên ngày 09/11/2026. **Giữ nguyên** Java 21, bốn Maven module, Gson/Packet, `AuctionService`, `Auction.bid`, Auto-Bid, Admin approval và CSS hiện có. Không thay cơ chế nghiệp vụ. ZIP bàn giao không chứa thư mục `.git` và `data/state.json` (dữ liệu/mật khẩu được lưu trên máy của bạn).

## 1. Nguyên nhân kết nối localhost

- `auction-server/src/main/java/com/auction/network/AuctionServer.java`, dòng gốc **29**: `new ServerSocket(port)` đã bind wildcard. Không giới hạn localhost. Bản vá viết tường minh `--bind=0.0.0.0`, `--port=5050`, vẫn hiểu đối số positional cũ.
- `auction-client/src/main/java/com/auction/client/DesktopApp.java`, dòng gốc **17–18**: mặc định `127.0.0.1:5050`. Địa chỉ loopback của mỗi máy khác nhau. Trước đây chỉ điều chỉnh qua `-Dauction.host`/`AUCTION_HOST` hoặc thuộc tính Port; không có ô nhập.
- `auction-client/src/main/java/com/auction/client/controller/LoginController.java`, dòng gốc **30–34, 54–60** và `login.fxml`: hiển thị `endpointLabel` mà không cho sửa. Bản vá thêm `serverHostField`, `serverPortField`, validate và kết nối async.
- `run-server.bat` / `run-server.sh`: trước gọi `5050 data/state.json`, nay minh họa `--bind=0.0.0.0 --port=5050 --data=data/state.json`.

## 2. Tóm tắt before / after theo file

### `auction-server/.../AuctionServer.java`

Trước:
```java
int port = args.length > 0 ? Integer.parseInt(args[0]) : 5050;
var workers = Executors.newFixedThreadPool(64);
try (ServerSocket server = new ServerSocket(port)) {
    while (true) workers.execute(new ClientHandler(server.accept(), service, hub));
}
```
Sau:
```java
String bindHost = System.getenv().getOrDefault("AUCTION_BIND", "0.0.0.0");
// Parse --port= / --bind= / --data= (giữ đối số cũ)
server.bind(new InetSocketAddress(InetAddress.getByName(bindHost), port), 128);
ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
Semaphore capacity = new Semaphore(128);
// tryAcquire -> run ClientHandler -> release; hook đóng listener/peers/executor
```
Lý do: cấu hình interface rõ, tránh queue chưa giới hạn của fixed pool, giới hạn 128 kết nối, shutdown gọn.

### `auction-client/.../DesktopApp.java`, `LoginController.java`, `login.fxml`

Trước:
```java
host = System.getProperty("auction.host", System.getenv().getOrDefault("AUCTION_HOST", "127.0.0.1"));
endpointLabel.setText("SERVER " + DesktopApp.serverHost() + ":" + DesktopApp.serverPort());
// new AuctionClient(DesktopApp.serverHost(), DesktopApp.serverPort())
```
Sau:
```java
@FXML private TextField serverHostField;
@FXML private TextField serverPortField;
// Lấy host/port từ form, kiểm tra hostname không rỗng, port 1..65535
DesktopApp.configureServer(enteredHost, selectedPort);
// new AuctionClient(enteredHost, selectedPort) trong CompletableFuture.supplyAsync
```
Lý do: có thể nhập IP LAN từng máy; lỗi dữ liệu hiện trên Login, không mở Socket trên JavaFX Application Thread; nhớ endpoint khi quay về Login.

### `auction-client/.../AuctionClient.java`

Trước:
```java
synchronized (writer) { writer.println(gson.toJson(packet)); }
while ((line = reader.readLine()) != null) { ... }
// catch IOException | RuntimeException: không báo UI khi mất kết nối
```
Sau:
```java
// ThreadPoolExecutor(1 writer, hàng đợi 256, virtual-thread factory)
outbound.execute(() -> { writer.println(gson.toJson(packet)); /* kiểm tra lỗi */ });
while ((line = JsonLineFraming.readLine(reader, 2_000_000)) != null) { ... }
// future.orTimeout(12s); mất phản hồi -> close socket -> onDisconnect(...)
```
Lý do: không ghi TCP trên JavaFX thread, chống hàng đợi tràn, không nuốt lỗi ngắt kết nối, chống giữ `pending` vô hạn. Khi đóng do logout thì không báo mất kết nối giả.

### `auction-common/.../network/JsonLineFraming.java` (mới)

Trước: `readLine()` có thể phân bổ chuỗi rất dài trước khi so độ dài ở `ClientHandler`.
Sau: đọc tối đa 64.000 ký tự ở phía Server, 2.000.000 ở phía Client; hỗ trợ LF và CRLF; UTF-8 được cấu hình ở stream. Vẫn dùng **mỗi Packet Gson JSON trên một dòng**, `println()` tự kết thúc dòng. Không thay `Packet` schema.

### `auction-server/.../ClientHandler.java`, `ClientHub.java`

Trước:
```java
while ((line = input.readLine()) != null) {
    if (line.length() > MAX_LINE_LENGTH) break;
    ...
}
private void disconnect() { hub.remove(this); socket.close(); }
```
Sau:
```java
while ((line = JsonLineFraming.readLine(input, MAX_LINE_LENGTH)) != null) { ... }
public void disconnect() { hub.remove(this); knownAuctions.clear(); session = null; socket.close(); }
// hub.closeAll() dọn các Client khi Server shutdown
```
Lý do: giới hạn bản tin trước cấp phát lớn, hủy phiên đúng lúc ngắt, không giữ observer trong hub.

### `auction-client/.../controller/DashboardController.java`

Trước: có `onEvent(packet -> Platform.runLater(...))` nhưng không đăng ký callback disconnect.
Sau: `onDisconnect` chuyển về JavaFX thread, hiển thị cảnh báo và quay về Login; endpoint cũ giữ lại để kết nối lại.

### `run-server.bat`, `run-server.sh`

Trước: `java -jar ... 5050 data/state.json`
Sau: `java -jar ... --bind=0.0.0.0 --port=5050 --data=data/state.json`.

### Tests thêm

- `auction-common/src/test/java/com/auction/network/JsonLineFramingTest.java`: LF, CRLF, Unicode, EOF, packet quá lớn.
- `auction-client/src/test/java/com/auction/client/AuctionClientNetworkingTest.java`: gửi JSON PING và nhận phản hồi, phát hiện Server đóng kết nối.
- Core đã có `AuctionServiceTest.concurrentBidsCannotRollback`, `AuctionServiceTest` cho nhiều bidder và `SocketRealtimeIntegrationTest` cho broadcast; giữ nguyên.

## 3. Review chất lượng code (mức độ và xử lý)

| Mức độ | Vị trí | Phát hiện | Đã xử lý / Đề xuất |
|---|---|---|---|
| **Nghiêm trọng** | `AuctionClient` + `ClientHandler` JSON LOGIN | Username/password truyền TCP không mã hóa. `PasswordHasher` lưu PBKDF2+salt **không bảo vệ đường truyền**. | **Chưa sửa do ràng buộc giữ giao thức**. Chỉ dùng LAN tin cậy, firewall Private/LocalSubnet. Muốn ra Internet phải dùng TLS (`SSLSocket` với xác thực certificate), không port-forward. |
| **Nghiêm trọng** | `AuctionService.changed()`, `save()`, `bid()` | Biến đổi trạng thái in-memory rồi mới ghi file; nếu `repository.save()` lỗi, in-memory đã thay đổi nhưng khôi phục sau restart có thể không nhất quán. | Không đổi thuật toán nghiệp vụ trong patch LAN. Tương lai: transaction/commit ghi bền trước publish, cơ chế rollback hoặc DB ACID; test giả lập I/O failure. |
| **Trung bình** | `AuctionService` | Nhiều method `synchronized`; `save()` thực hiện File I/O trong monitor dùng chung, các phiên khác có thể phải chờ. | **Giữ nguyên để bảo đảm correctness**; cân nhắc per-auction lock + persistence transaction nếu tối ưu tải. |
| **Trung bình** | `AuctionServer` | `newFixedThreadPool(64)` có work queue không giới hạn khi quá tải. | **Đã sửa**: virtual thread per task + Semaphore tối đa 128 socket active. |
| **Trung bình** | `ClientHandler` / `JsonLineFraming` | `readLine()` cấp phát không giới hạn trước kiểm tra độ dài. | **Đã sửa**: bounded reader. |
| **Trung bình** | `AuctionClient` | `writer.println()` chạy trực tiếp trên FX thread, I/O có thể làm đơ UI; chưa báo disconnect. | **Đã sửa**: hàng đợi writer riêng, timeout, callback UI, trở về Login. |
| **Trung bình** | `EventBus` | Dispatcher `newSingleThreadExecutor` có hàng đợi sự kiện chưa giới hạn; nếu tốc độ tạo event cao hơn dispatch, RAM có thể tăng. | Chưa thay vì yêu cầu không thay logic realtime; bổ sung backpressure, snapshot coalescing và metrics ở bước tối ưu hiệu năng. |
| **Trung bình** | `ClientHub` / `AuctionView` | Mỗi sự kiện gửi toàn bộ `AuctionView` gồm lịch sử; càng nhiều bid, packet càng lớn và vượt giới hạn nhận Client. | Nên dùng event delta (bid mới + current price) và snapshot ban đầu; điều này sẽ đổi payload nên cần kế hoạch tương thích. |
| **Trung bình** | `AuctionService.login()` | Không có throttling/rate limit với LOGIN; Client có thể thử nhiều mật khẩu. | Thêm giới hạn theo IP/account và ghi log bảo mật, ưu tiên trước triển khai ngoài lớp học. |
| **Trung bình** | `ClientHub.broadcast()` | `CopyOnWriteArraySet` thread-safe, send enqueue có giới hạn; nhưng `canSee()` gọi service lock và có thể bị chậm do save(). | Hiện không gửi socket trực tiếp trên luồng Observer và không block vì Client chậm. Nếu scale lên, tách auth snapshot khỏi I/O, đo thời gian event dispatch. |
| **Nhẹ** | `ClientHandler` | Import trùng `AuctionView` và wildcard import nhiều nơi; lỗi nhỏ về code style. | Xóa import trùng và chuẩn hóa import khi refactor tiếp. |
| **Nhẹ** | `ClientHandler`, `DashboardController` | `handle()` nhiều nhánh, controller ~500 dòng; magic strings như `"BID"`, `"LOGIN"` lặp. | Trong refactor sau: enum `PacketType`, tách command handlers và form validation; không đổi message `type` ngay để giữ tương thích. |
| **Nhẹ** | `ClientHandler.disconnect()`, `DesktopApp` | Có catch IOException từng bỏ qua; thiếu log khi đóng socket lỗi. | **Đã thêm log** cho network cleanup; một số đóng UI im lặng khi logout vẫn hợp lý. |

### Kiểm tra race/authorization (giữ nguyên)

- `Auction.java:101–118` dùng `ReentrantLock` bao **kiểm tra trạng thái/giá**, cập nhật `currentPrice`, `highestBidder`, `history` và gia hạn thời gian. `AuctionService.bid()` đang `synchronized`, nên hai bid cùng giá không đồng thời thắng. Cả request thắng/thua đều được `ClientHandler` trả `Packet.result` hoặc `Packet.error`.
- Phía Server dùng `ClientHandler.session` của chính socket, các lệnh `BID`, `CREATE`, `APPROVE`, `USERS` đều gọi `authorized()`/`requireRole()`. Không lấy username do client tự gửi để đại diện bidder.
- Giá đặt dùng `long`, kiểm tra bước giá tối thiểu và trạng thái. Các input giá âm / dữ liệu sai JSON bị từ chối bởi nghiệp vụ hoặc parse; `JsonSyntaxException` (RuntimeException) vào nhánh `Packet.error`.
- `ClientHub` dùng `CopyOnWriteArraySet`, `ClientHandler.knownAuctions` là concurrent set và có `ArrayBlockingQueue<>(256)`. Chậm client -> rớt kết nối khi queue đầy, không chặn những client khác; rời đi -> `hub.remove()`.
- Observer `EventBus` dispatch asynchronous, toàn bộ cập nhật UI gửi qua `Platform.runLater`. UI không polling liên tục.

## 4. Kết quả kiểm tra bản vá

- **Đã chạy:** biên dịch backend + `AuctionClient` với JDK21 và Gson stubs (chỉ kiểm tra cú pháp và liên kết tên API), XML FXML/POM parse thành công; smoke test JSON-line giới hạn, LF, CRLF, Unicode; smoke test 24 bidder gửi cùng mức giá: chỉ một bid thành công.
- **Chưa chạy được:** Maven `clean verify` với dependency Gson/JavaFX thật, JUnit tích hợp, GUI JavaFX và kiểm thử trên hai máy LAN. Cần thực hiện trên máy của nhóm theo `docs/LAN_SETUP.md` trước khi công bố đã nghiệm thu.

## 5. Cách triển khai

Đọc `docs/LAN_SETUP.md`. Cần đồng bộ **Server và Client**, vì có thêm khả năng cấu hình và kiểm tra framing (không đổi schema JSON). Để giữ dữ liệu đăng ký cũ, sao lưu và chỉ chuyển file `data/state.json` trên máy Server. **Không đăng file này lên GitHub.**
