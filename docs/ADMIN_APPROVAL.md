# Duyệt phiên đấu giá bởi Admin

Đây là chức năng bổ sung của nhóm ngoài danh sách ví dụ chức năng ở đề 2026-BTL.

1. Seller tạo phiên (`CREATE`) → trạng thái `PENDING` (chờ duyệt). Giá, thông tin và lịch đặt trước phải hợp lệ.
2. Chỉ Admin và Seller sở hữu nhìn thấy phiên `PENDING`/`REJECTED`. Bidder không thấy nó trong danh sách; Server không gửi thông tin phiên chưa duyệt tới Bidder.
3. Admin mở bảng **Phiên chờ duyệt**, chọn phiên và kiểm tra nội dung → **Duyệt** hoặc **Từ chối** (bắt buộc nhập lý do).
4. Duyệt (`APPROVE`): `PENDING → OPEN`, hoặc `PENDING → RUNNING` nếu đã đến giờ bắt đầu, nhưng vẫn còn trước giờ kết thúc. Từ đây Bidder mới thấy phiên và có thể đặt giá khi `RUNNING`.
5. Từ chối (`REJECT`): `PENDING → REJECTED`, Seller nhìn thấy lý do, sửa thông tin và lưu (`EDIT`) để gửi duyệt lại (`REJECTED → PENDING`).
6. Khi Seller sửa phiên đã được duyệt nhưng chưa chạy và chưa có bid, trạng thái trở lại `PENDING` để Admin duyệt lại. Không thể âm thầm sửa điều khoản đấu giá đã được duyệt.
7. Nếu thời điểm kết thúc đã qua trước khi duyệt, Server từ chối phê duyệt; Seller cần sửa thời gian và gửi lại.
8. Phiên cũ trong `data/state.json` có trạng thái `OPEN/RUNNING/FINISHED/PAID` được coi là đã duyệt để tránh làm thay đổi lịch sử đấu giá. Không chuyển dữ liệu cũ sang `PENDING`.
9. Cần sao lưu `data/state.json` trước khi nâng cấp. Chạy lại **cả Server và Client**, không chỉ chép FXML hoặc chạy JAR cũ.

## Kiểm tra

- Seller tạo, kiểm tra `PENDING`.
- Bidder không thấy, không được `DETAIL` hoặc `BID` trước duyệt.
- Bidder không nhận sự kiện thông tin phiên chưa duyệt từ Socket.
- Bidder không được gọi `APPROVE/REJECT` giả mạo.
- Admin duyệt, Bidder mới thấy và có thể tham gia khi đã bắt đầu.
- Admin từ chối kèm lý do; Seller sửa và gửi lại.
- Phiên chưa duyệt không tự mở/đóng khi đến thời điểm đã lên lịch.
- Trạng thái và lý do xét duyệt vẫn còn sau khi khởi động lại Server.
