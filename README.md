# AUCTIVA — Online Auction System (LTNC 2026)

Dự án Java Client–Server sử dụng **Java 21 LTS**, **Maven 4 module**, **JavaFX/FXML**, **TCP Socket + JSON**, **Observer realtime**, **File JSON persistence**, **JUnit 5**. Phát triển bằng Maven, đóng gói mỗi app thành executable JAR.

> Yêu cầu triển khai theo `2026-BTL` + `Hướng dẫn BTL LTNC-2026`, tham khảo hai GUI mẫu trong ZIP người dùng cung cấp; giao diện FXML/CSS trong dự án là phiên bản thiết kế lại, không phụ thuộc tài nguyên riêng của mẫu.

## 1. Yêu cầu môi trường

- JDK **21 trở lên**, khuyến nghị JDK 21 (Temurin). Trên máy Windows dùng `java -version` và `where java` kiểm tra.
- Kết nối Internet **lần build đầu** để Maven Wrapper tải Maven và dependency (`Gson`, `JUnit`, `JavaFX`, ...). Sau đó có thể build offline khi cache đầy đủ.
- JavaFX native libs phụ thuộc hệ điều hành; đóng gói **JAR client trên hệ điều hành sẽ chạy** (Windows build → Windows, Linux build → Linux). Không nên dùng JAR client build ở Linux để chạy trực tiếp trên Windows.
- Server mặc định TCP **5050**, bind `0.0.0.0` để dùng mạng LAN; client mặc định **127.0.0.1** nhưng có thể nhập IP máy Server và Port ở Login. Chỉ dùng LAN tin cậy khi chưa bật TLS. Xem `docs/LAN_SETUP.md` và `docs/LAN_CODE_REVIEW.md`.

## 2. Cây thư mục

```text
online-auction-system-2026/
├── pom.xml                      # Maven parent / reactor
├── auction-common/               # Shared Entity, Item, User, DTO, Packet, exceptions
├── auction-core/                 # Business logic, auto-bid, Observer, repository, tests
├── auction-server/               # TCP Server / ClientHandler / ClientHub
├── auction-client/               # JavaFX/FXML/CSS / socket client / MVC
├── .github/workflows/ci.yml      # Build, verify, test, artifacts
├── docs/                         # Kiến trúc, phân công, mốc nghiệm thu tuần
├── build.bat / build.sh          # Build và copy JAR vào dist/
├── run-server.bat / .sh          # Demo local từ target/
└── run-client.bat / .sh
```

Dependency graph: `client → common`; `server → core → common`; server cũng phụ thuộc common.

## 3. Build Maven (dành cho VS Code / IntelliJ)

**Windows CMD (hoặc PowerShell, viết `.\mvnw.cmd`)** tại thư mục gốc:

```bat
mvnw.cmd clean verify
build.bat
```

**Linux / macOS:**

```bash
./mvnw clean verify
./build.sh
```

Các lệnh tạo ra:

- `auction-server/target/auction-server-1.0.0-all.jar`
- `auction-client/target/auction-client-1.0.0-all.jar`
- `dist/auction-server.jar` và `dist/auction-client.jar` sau script build.

Nếu chỉ muốn test module core: `./mvnw -pl auction-core -am test`. Báo cáo JaCoCo: `auction-core/target/site/jacoco/index.html` sau `verify`.

## 4. Khởi động (2 terminal riêng)

Terminal 1:

```bat
java -jar auction-server\target\auction-server-1.0.0-all.jar 5050 data\state.json
```

Terminal 2:

```bat
java -jar auction-client\target\auction-client-1.0.0-all.jar
```

Hoặc chạy `run-server.bat`, `run-client.bat` trong Windows. Bản GUI hiển thị `SERVER 127.0.0.1:5050` góc phải phía trên form login và không bắt người dùng nhập host/port.

Để kết nối server ở máy khác:

```bat
set AUCTION_HOST=192.168.1.20
set AUCTION_PORT=5050
java -jar auction-client\target\auction-client-1.0.0-all.jar
```

PowerShell: `$env:AUCTION_HOST="192.168.1.20"; $env:AUCTION_PORT="5050"; java -jar auction-client\target\auction-client-1.0.0-all.jar`.

Linux/macOS: `AUCTION_HOST=192.168.1.20 AUCTION_PORT=5050 java -jar ...`.

Server có thể chọn cổng và file data riêng: `java -jar ... 5051 data/demo.json`.

## 5. Tài khoản và quyền

- Màn hình login cho phép **đăng ký BIDDER / SELLER**. Tài khoản trùng tên không hợp lệ, password PBKDF2+salt trong file state.
- **BIDDER**: đặt giá thủ công với bất kỳ mức tăng nào ≥ Seller minimum; Auto-Bid nhập `maxBid` và bước tăng mong muốn (`increment ≥ minimumIncrement`), xem lịch sử, giá realtime.
- **SELLER**: tạo sản phẩm, chọn **bước giá tối thiểu (minimumIncrement) tính bằng VND** cho từng phiên; mỗi phiên mới ở trạng thái **`PENDING`**, phải được Admin duyệt. Có thể sửa khi `PENDING`, `REJECTED` hoặc `OPEN` chưa bắt đầu và chưa có bid; **sửa phiên đã duyệt sẽ tự gửi duyệt lại**. Không thể tự bid sản phẩm của mình.
- **ADMIN**: tài khoản quản trị `admin` chỉ được tạo bởi Server qua biến môi trường `AUCTION_ADMIN_PASSWORD` (ít nhất 8 ký tự). Sau khi đăng nhập sẽ thấy **QUẢN TRỊ HỆ THỐNG**: danh sách tài khoản, vai trò, trạng thái, khóa/mở khóa Bidder/Seller; xem tất cả phiên, **duyệt/từ chối phiên do Seller gửi lên (kèm lý do)**, hủy phiên vi phạm, xác nhận `PAID` cho phiên kết thúc có người thắng. Không thể tự khóa tài khoản Admin.
- Khóa tài khoản sẽ chặn **đăng nhập mới và phiên đăng nhập đang mở** khi gửi yêu cầu tiếp theo, đồng thời xóa cấu hình Auto-Bid đang chờ của tài khoản đó; các bid hợp lệ đã ghi vào lịch sử **không bị xóa**. Trạng thái khóa được lưu trong `data/state.json`.
- Đăng ký công khai **không thể tạo ADMIN**. Trường chọn vai trò ở form đăng nhập chỉ dùng cho nút **Tạo tài khoản**, không ảnh hưởng đến đăng nhập bằng `admin`. `PAID` là xác nhận **thủ công**, không xử lý thanh toán thật.
- Sau khi đăng ký thành công, client tự gửi lệnh `LOGIN` trên cùng kết nối và chuyển thẳng sang Dashboard. Nếu tài khoản đã tồn tại, bấm **Đăng nhập** với đúng mật khẩu.

## Tạo và đăng nhập tài khoản Admin (Windows)

1. Tắt Server đang chạy. **Sao lưu `data/state.json`** trước khi thay mã nguồn.
2. Mở PowerShell tại thư mục gốc dự án (có `pom.xml`) và chạy:

```powershell
$env:AUCTION_ADMIN_PASSWORD = "ThayBangMatKhauRiengDuManh"
.\run-server.bat
```

3. Mở terminal thứ hai: `.\run-client.bat`. Nhập **tên tài khoản `admin`** và mật khẩu đã đặt, rồi bấm **Đăng nhập** (không bấm Tạo tài khoản).
4. Tại **Phiên đấu giá chờ duyệt**, chọn một phiên, kiểm tra tên, mô tả, giá, bước giá và thời hạn; bấm **Duyệt phiên** hoặc nhập lý do và **Từ chối phiên**. Tại bảng tài khoản có thể khóa/mở khóa người dùng. Với phiên đã kết thúc và có người thắng, Admin có thể xác nhận **Đã thanh toán**.

Tài khoản Admin chỉ được tạo khi Server chưa có tài khoản `admin` trong file dữ liệu. Đổi biến môi trường ở các lần chạy sau **không đặt lại** mật khẩu Admin đang tồn tại. Không chép mật khẩu vào README, Git hoặc source code. Hệ thống chỉ phù hợp demo trên máy/LAN tin cậy, không sử dụng cho đấu giá bằng tiền thật.

## Quy trình Admin duyệt phiên (tính năng mở rộng)

1. Seller bấm **Gửi duyệt**: Server tạo `PENDING` và lưu vào `data/state.json`. Chỉ chính Seller và Admin nhìn thấy bản gửi này.
2. Admin xem bảng **PHIÊN ĐẤU GIÁ CHỜ DUYỆT** trong Dashboard. Chọn phiên để xem chi tiết và bấm **Duyệt phiên** → trạng thái `OPEN` hoặc `RUNNING` (nếu đã đến giờ, còn trước hạn kết thúc).
3. Admin có thể bấm **Từ chối phiên** sau khi nhập lý do (bắt buộc). Phiên trở thành `REJECTED`, Seller thấy lý do; sửa và lưu (`EDIT`) để trở lại `PENDING`.
4. Bidder **không thấy và không được đặt giá** ở phiên `PENDING`/`REJECTED`, kể cả qua Socket API hay Auto-Bid. Lệnh `APPROVE`/`REJECT` yêu cầu vai trò ADMIN tại Server.
5. Nếu Seller sửa phiên đã duyệt nhưng chưa chạy và chưa có bid, toàn bộ thông tin phải được Admin duyệt lại. Nếu hạn kết thúc đã qua khi Admin bấm duyệt, Admin phải yêu cầu Seller sửa thời gian và gửi lại.
6. **Tương thích dữ liệu:** phiên cũ có trạng thái `OPEN`, `RUNNING`, `FINISHED`, `PAID` vẫn được xem là đã duyệt; không thay đổi kết quả đấu giá có từ trước. Sao lưu `data/state.json` trước khi nâng cấp.

Xem `docs/ADMIN_APPROVAL.md` để có danh sách API và kiểm tra chi tiết.

## Quy tắc kiểm tra trước, trong và sau phiên

- Khi **tạo phiên**, Server yêu cầu tên và thông tin hợp lệ, giá khởi điểm và bước giá tối thiểu dương, thời gian kết thúc lớn hơn thời gian bắt đầu **và chưa nằm trong quá khứ**.
- `PENDING`/`REJECTED`: chưa được phép đấu giá và không tự mở theo thời gian. `OPEN` trước giờ bắt đầu: không cho đặt giá. `RUNNING`: chấp nhận bid khi đủ bước giá tối thiểu và tài khoản có quyền Bidder. Người bán không tự đặt giá; người đang dẫn đầu không tự nâng giá thủ công.
- Mỗi bid hợp lệ ghi **giá cao nhất + người dẫn đầu + lịch sử** trong cùng vùng khóa; giá không được giảm, nhiều Client cùng bid không tạo ra hai giá thắng mâu thuẫn.
- Khi hết giờ: Server tự cập nhật `FINISHED`. **Người thắng** là `highestBidder` nếu có bid hợp lệ; nếu chưa có bid thì **không có người thắng**. Trong `FINISHED`/`PAID`, giá trị thắng cuộc không được thay đổi bằng bid mới. `CANCELED` không có người thắng, dù trước đó có người dẫn đầu.
- `PAID` chỉ do Admin xác nhận với phiên `FINISHED` có người thắng. Không thể đặt giá tiếp khi `FINISHED`, `PAID` hoặc `CANCELED`.
- Anti-sniping: nếu bid hợp lệ xuất hiện trong 30 giây cuối, thời hạn cộng 60 giây; người thắng chỉ xác định sau thời hạn đã được cập nhật.

Xem `docs/ADMIN_AND_AUCTION_VALIDATION.md` để biết API, trường hợp thử và giới hạn.

## Seller đặt bước giá tối thiểu cho từng phiên

- Trong **Quản lý sản phẩm / Seller**, nhập **Giá khởi điểm** (ví dụ `79000000`) và **Bước giá tối thiểu (VND)** (ví dụ `100000`), rồi bấm **Tạo phiên**.
- Seller có thể thay đổi bước giá khi phiên còn **PENDING**, **REJECTED**, hoặc **OPEN chưa chạy và chưa nhận bid**; sửa phiên OPEN sẽ quay lại **PENDING** để duyệt lại. Không được thay đổi khi RUNNING.
- Quy tắc Server áp dụng cho cả **Đặt giá** và **Auto-Bid**: `newBid >= currentPrice + minimumIncrement`. Không làm tròn thành bội số: bid 79.250.000 hợp lệ khi giá hiện tại 79.000.000, bước tối thiểu 100.000.
- Bidder có thể đặt giá thủ công cao hơn tùy ý: Seller yêu cầu bước 100.000, giá hiện tại 1.000.000 thì 1.100.000 và 1.500.000 đều hợp lệ, 1.050.000 không hợp lệ.
- Với **Auto-Bid**, Bidder nhập cả **Giá tối đa** và **Bước giá Auto-Bid** (mặc định bằng mức Seller yêu cầu, có thể lớn hơn). Server từ chối nếu bước riêng nhỏ hơn Seller minimum hoặc giá tối đa không đủ một bước của Bidder.
- Khi hai Auto-Bid cạnh tranh: ưu tiên mức trần cao hơn, nếu cùng mức trần ưu tiên người đăng ký trước; không vượt mức trần. Mỗi lượt tự đặt ưu tiên bước tăng do người dẫn đầu chọn; sát mức trần có thể tăng ít hơn bước mong muốn nhưng không được thấp hơn Seller minimum, không yêu cầu đặt giá theo bội số của bước.
- Trong chi tiết phiên, xem dòng **Bước giá tối thiểu** và **Giá đặt tiếp theo ≥**. Mỗi phiên có thể có bước giá khác nhau.
- Dự án trước khi bổ sung bước giá: khi Server nạp `data/state.json` cũ chưa có `minimumIncrement`, phiên cũ sẽ dùng bước giá **1 VND** để tương thích. Muốn đặt bước giá khác cho phiên cũ, Seller phải sửa trước khi phiên bắt đầu; với phiên đã RUNNING, hãy tạo phiên mới.
- **Không dùng đồng thời bản Client cũ và Server mới**: gói lệnh `CREATE`/`EDIT` đã có trường `minimumIncrement`. Sau khi cập nhật phải build và khởi động lại cả hai.

## 6. Quy trình demo chức năng

1. Khởi động server, mở **3 instance client**: một SELLER và hai BIDDER.
2. SELLER tạo sản phẩm với `Bắt đầu sau (phút) = 0`, `Thời lượng (phút) = 10` và bấm **Gửi duyệt**.
3. Mở client ADMIN, chọn sản phẩm trong **Phiên chờ duyệt**, bấm **Duyệt phiên**. Chỉ sau đó BIDDER A mới có thể đặt giá cao hơn giá hiện tại; BIDDER B nhìn thấy biến động theo **server push**, không cần refresh.
4. SELLER đặt bước giá tối thiểu `10000`, BIDDER A bật Auto-Bid với `maxBid = 500000`, `bước giá = 25000`; BIDDER B đặt giá cạnh tranh.
5. Quan sát `LineChart` vẽ thêm điểm mỗi bid được chấp nhận.
6. Tạo phiên thời lượng 1 phút, đặt giá trong 30 giây cuối để thấy thời hạn tự cộng 60 giây.
7. Đợi phiên kết thúc, admin `PAID`; thử bid phiên đã FINISHED/PAID để kiểm tra exception.
8. Đóng server, chạy lại với cùng `data/state.json` và xem dữ liệu được giữ lại.

## 7. Business rules

- Model `Entity` → `User` (`Bidder`, `Seller`, `Admin`), `Item` (`Electronics`, `Art`, `Vehicle`), `Auction`, `BidTransaction`.
- Singleton `AuctionManager`; `ItemFactory` là Factory Method; `EventBus` thực hiện Observer; `AutoBidEngine` là strategy logic độc lập.
- Bid được service tuần tự hoá để không lost update; `Auction` dùng `ReentrantLock` bảo vệ giá, leading bidder và lịch sử.
- Auto-Bid dùng `PriorityQueue`: ceiling giảm dần, hòa ceiling ưu tiên lần đăng ký sớm hơn; không vượt max; từng Bidder chọn mức tăng ≥ seller minimum, mọi bid được kiểm tra tại Server.
- Anti-sniping: bid hợp lệ trong 30s cuối gia hạn thêm 60s so với hạn cũ.
- Trạng thái: `PENDING → OPEN → RUNNING → FINISHED → PAID`; `PENDING → REJECTED → PENDING` khi từ chối và Seller sửa; `CANCELED` khi Seller/Admin hủy hợp lệ. Server scheduler tự chốt phiên.
- Mỗi client đăng nhập nhận sự kiện `AUCTION_UPDATED` khi có bid/status; ứng dụng JavaFX cập nhật bảng và biểu đồ trên FX Application Thread.

## 8. Test, CI, nộp bài

- `./mvnw clean verify` chạy unit tests, Checkstyle và tạo coverage report. Workflow GitHub Actions chạy tự động trên PR/push vào `dev` và `main`.
- Các bài kiểm thử bao gồm duyệt/từ chối/bảo mật phiên chưa duyệt, bid invalid, quyền hạn, đồng thời 2/32 bidder, anti-sniping, auto-bid hòa ceiling, maxBid, persistence, transitions.
- Xem `docs/WEEKLY_MILESTONES.md` cho mục tiêu chính xác từng tuần 6→15; `docs/TEAM_ASSIGNMENT.md` phân công 4 thành viên; `docs/ARCHITECTURE.md` trình tự xử lý và hạn chế hệ thống.
- Nộp source zip/repo GitHub với PR và commit thật, `dist` JAR (sau khi build thành công ở máy nộp), demo 3 client, kết quả `mvn verify`, JaCoCo và slide báo cáo.

## 9. Lưu ý an toàn & hạn chế

Đây là phần mềm bài tập lớn, **không phải dịch vụ đấu giá production**. TCP không TLS và JSON file không ACID transaction; không dùng trên Internet công cộng hoặc cho giao dịch tiền thật. Không commit `data/state.json` hay mật khẩu admin. Kết quả Maven/JUnit/JaCoCo cần chạy và xác minh thực tế trên máy có Maven/JDK/JavaFX; không suy diễn từ source code.

## 10. Sửa lỗi đăng nhập / không chuyển vào Dashboard (hotfix đăng nhập)

- Sửa FXML `dashboard.fxml`: `TableView.placeholder` phải chứa `<Label/>` (JavaFX `Node`), không được gán chuỗi ở attribute.
- Bỏ setter varargs `SplitPane.dividerPositions` dạng chuỗi khỏi FXML; vị trí divider được cấu hình trong `DashboardController.initialize()`.
- Sau đăng ký, thực hiện luôn lệnh `LOGIN` trên cùng socket, không cần đăng nhập thêm lần nữa.
- Tăng khả năng quan sát lỗi: nếu đăng nhập thành công nhưng load Dashboard thất bại, client hiển thị thông báo cụ thể và in stack trace ra Terminal.
- Login/Register bị khóa tạm khi gửi yêu cầu, mở khóa sau khi hoàn tất; kết nối và yêu cầu có giới hạn chờ.

**Cập nhật source ở máy đã giải nén:** lưu lại `data/state.json` nếu có, thay toàn bộ source từ ZIP mới (không thay `data/state.json`), đóng client/server cũ và build lại ở thư mục gốc:

```bat
mvnw.cmd clean verify
run-server.bat
```

Sau khi server chạy ổn định, mở terminal khác và chạy `run-client.bat`. Không chạy JAR cũ chưa build lại. Tài khoản trước đó nằm trong `data/state.json` của server và tiếp tục sử dụng được nếu giữ nguyên file dữ liệu. Phải chạy cả hai script ở đúng thư mục dự án.


## Tự động tìm Server trong mạng LAN

Client tự tìm Auction Server qua UDP broadcast cổng **5051**, không cần nhập IP/port. Server vẫn truyền lệnh đấu giá qua TCP 5050 (JSON/Gson). Mở Server trước, Client sau; nếu Client chưa thấy Server, nhấn **Tìm lại**. Mở inbound firewall **TCP 5050 + UDP 5051** với profile Private/LocalSubnet. Hướng dẫn: [`docs/AUTO_DISCOVERY_LAN.md`](docs/AUTO_DISCOVERY_LAN.md). Không triển khai trên Wi-Fi công cộng vì giao thức TCP hiện chưa mã hóa.
