# Kiến trúc kỹ thuật

## Module Maven và hướng phụ thuộc

```text
auction-client ---> auction-common <--- auction-server ---> auction-core ---> auction-common
  JavaFX            API/DTO             TCP clients        services        DTO/entities
```

- **Client GUI**: JavaFX/FXML/CSS, Controllers chỉ gửi lệnh qua `AuctionClient`, không truy cập database và không thao tác trực tiếp vào domain server.
- **Common**: hợp đồng DTO `AuctionView`, entity trừu tượng `User`, `Item`, các subtype và `Packet` giao thức.
- **Core**: `AuctionService`, `AutoBidEngine`, singleton `AuctionManager`, `EventBus`, `StateRepository` và `FileStateRepository`.
- **Server**: TCP `AuctionServer`, `ClientHandler`, `ClientHub`; mỗi session giữ danh tính `User` sau LOGIN; không tin role do client tự khai ở các yêu cầu nghiệp vụ.

## Luồng Admin xét duyệt

1. Seller tạo phiên `PENDING`; Server kiểm tra đầu vào, ghi `State.AuctionData` và thông báo realtime **chỉ** đến Admin và Seller sở hữu.
2. Admin nhìn thấy hàng đợi duyệt trong Dashboard và quyết định `APPROVE` hoặc `REJECT` (có lý do bắt buộc).
3. Duyệt chuyển sang `OPEN`, hoặc `RUNNING` nếu đến giờ và thời hạn còn hiệu lực. Từ lúc này Bidder mới nhìn thấy phiên.
4. Từ chối chuyển sang `REJECTED`, chỉ Admin và Seller nhìn thấy ghi chú; Seller sửa để gửi xét duyệt lại.
5. `ClientHandler` lọc `LIST`/`DETAIL` theo người dùng; `ClientHub` chỉ phát bản cập nhật riêng tư cho đúng Admin/Seller. Khi một phiên công khai quay về PENDING, Client đã biết phiên nhận sự kiện xóa khỏi danh sách.

## Luồng một giao dịch bid

1. User đăng nhập, Server cấp session gắn vào một kết nối TCP đang mở.
2. GUI gửi JSON line `{id,type:"BID",data:{id,amount}}`.
3. `ClientHandler` lấy `session`, không lấy `username` của bản tin, gọi `AuctionService.bid()`.
4. `AuctionService` đồng bộ hoá thay đổi cả giá / người dẫn đầu / lịch sử / trạng thái; `Auction` có `ReentrantLock` bảo vệ invariant giá.
5. `AutoBidEngine` áp dụng proxy bid theo giới hạn và thứ tự đăng ký, `Auction.bid()` thực hiện anti-sniping trên từng bid hợp lệ.
6. `FileStateRepository` ghi snapshot JSON bằng temporary file + atomic rename nếu hệ thống tệp hỗ trợ.
7. `EventBus` gửi snapshot mới cho `ClientHub`, fanout các phiên đăng nhập qua hàng đợi bounded; Client JavaFX nhận sự kiện, cập nhật TableView + LineChart bằng `Platform.runLater`.

## Các invariant quan trọng

- Mọi bid hợp lệ phải có `amount >= currentPrice + minimumIncrement` (bước giá Seller thiết lập, tránh overflow) và phiên ở `RUNNING`.
- Mỗi phiên mới là `PENDING`; chỉ Admin được `APPROVE`/`REJECT`. Bidder không được thấy hoặc tham gia `PENDING`/`REJECTED`. Seller chỉ sửa/xoá phiên chưa chạy và chưa có bid; sửa phiên đã duyệt thì **trở lại PENDING** để xét duyệt lại. Chỉ `BIDDER` đặt giá ở `RUNNING`; `ADMIN` xác nhận thanh toán sau khi kết thúc.
- Auction kết thúc theo đồng hồ Server, xử lý cả sau khi khởi động lại, duy nhất một `highestBidder`.
- `AutoBidEngine` dùng priority ordering `(maxBid DESC, createdAt ASC)` và không vượt ceiling; mỗi Auto-Bid có bước tăng mong muốn `increment >= Auction.minimumIncrement` do Bidder chọn; ở giá trần bước thực tế có thể nhỏ hơn bước mong muốn nhưng vẫn phải ≥ mức Seller yêu cầu. Đặt giá thủ công được tăng tùy ý, miễn không thấp hơn mức tối thiểu.
- Anti-sniping: khi bid được chấp nhận với thời gian còn `<=30s`, dời deadline thêm `60s`.
- GUI không polling theo chu kỳ: nhận `AUCTION_UPDATED` và `removedId` đẩy qua TCP, cập nhật danh sách theo sự kiện.

## Giới hạn bản thực hành

- Server dùng snapshot JSON và khoá dịch vụ toàn cục; an toàn cho nhiều client trong một tiến trình nhưng **không** hỗ trợ nhiều server chia sẻ cùng file/scale-out.
- Giao thức Socket JSON hiện là TCP thuần; chỉ sử dụng LAN/local được tin cậy. Để public internet cần TLS, hạn mức request, audit log, cơ chế phục hồi giao dịch/DB và hardening thêm.
- PAID là thao tác quản trị xác nhận thủ công, **không** tích hợp cổng thanh toán thật.
- File snapshot được lưu bằng rename; không phải hệ cơ sở dữ liệu giao dịch ACID. Không quảng bá như hệ thống thương mại production.
