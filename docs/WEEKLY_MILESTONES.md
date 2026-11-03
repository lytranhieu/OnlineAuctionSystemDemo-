# Ma trận nghiệm thu lộ trình Tuần 6–15

**Nguồn**: Hướng dẫn BTL LTNC-2026 (Tuần 6 → 15). File này mô tả mốc *phải nghiệm thu*, không khẳng định nhóm đã hoàn thành/commit các mốc trong thực tế.

| Tuần | Mục tiêu trong PDF | Nơi triển khai trong source | Điều kiện nghiệm thu / demo |
| --- | --- | --- | --- |
| 6 | Phân tích đề, repository/dev, cây kế thừa, Singleton, Factory, JavaFX HelloWorld | `auction-common/model`, `ItemFactory`, `auction-core/AuctionManager`; GUI FXML | Class diagram; `mvn test`; chạy Login |
| 7 | Observer, tạo/bid, trạng thái, concurrent bidding, Login/Danh sách | `Auction`, `AuctionService`, `EventBus`, `DashboardController` | 2 client bid cùng lúc; thắng duy nhất; demo thay đổi giá |
| 8 | Custom exceptions, JUnit, SOLID refactor, GUI MVC FXML | `service/exception`, `auction-core/src/test`, `auction-client/*.fxml` | Test bid hợp lệ/sai, hết hạn; review code và CRUD seller |
| 9 | Maven, Checkstyle, Actions, Socket, Serialization | 4 `pom.xml`, `.github/workflows/ci.yml`, `ClientHandler`, `FileStateRepository` | `./mvnw verify` xanh; khởi động lại server còn dữ liệu |
| 10 | Server nhiều client, push Socket/Observer, 5 màn hình logic, coverage >=60% | `AuctionServer`, `ClientHub`, FXML Login/Dashboard với danh sách/chi tiết/live/seller | Kiểm tra coverage thực bằng JaCoCo; hai tài khoản nhận push |
| 11–12 | Tích hợp GUI ↔ server ↔ storage, E2E, xử lý edge cases | mọi module | 3 client, đồng thời, mất kết nối và khởi động lại, CI xanh |
| 13–14 | UX, Auto-Bidding, anti-sniping, LineChart, README | `AutoBidEngine`, `Auction.bid`, `historyChart`, `README.md` | Tie-break bằng thời gian, không vượt max; deadline gia hạn, đường giá live |
| 15 | Slide, demo, giải thích code, đóng góp | `docs/TEAM_ASSIGNMENT.md`, dự án hoàn chỉnh | 4 thành viên demo & trả lời câu hỏi, nộp JAR/README |

**Tuyệt đối không ghi đạt 60% coverage chỉ dựa trên số test.** Dùng JaCoCo HTML report và thêm tests tới khi thực tế >=60%; CI hiện xuất report và chưa khóa cứng ngưỡng này.

Một buổi nghiệm thu thật cần lưu: ảnh màn hình, log chạy test, link PR/commit hash, người review, issue tồn đọng. Source có sẵn không thay thế việc mỗi thành viên tự triển khai và chứng minh đóng góp đúng tiến độ.
