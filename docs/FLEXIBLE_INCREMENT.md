# Seller minimum + Bidder flexible increment

## Quy tắc cuối cùng

- Seller đặt `minimumIncrement` khi tạo/sửa sản phẩm trước khi đấu giá bắt đầu.
- Bidder đặt giá thủ công tùy ý, với điều kiện `amount >= currentPrice + minimumIncrement`.
  Ví dụ: current=1.000.000, Seller minimum=100.000; 1.050.000 bị từ chối,
  1.100.000 / 1.350.000 / 1.500.000 đều được chấp nhận.
- Đối với Auto-Bid, Bidder nhập `maxBid` và `increment` (bước **mong muốn**)
  với `increment >= minimumIncrement`. Mặc định ô bước giá bằng Seller minimum.
  Lần đăng ký Auto-Bid yêu cầu `maxBid >= currentPrice + increment`.
- Khi tự động phản hồi một bid, Server ưu tiên bước tăng do **người đặt tự động**
  lựa chọn. Nếu gần đến giá trần (`maxBid`) thì có thể tăng ít hơn bước mong muốn,
  nhưng **không bao giờ ít hơn Seller minimum**, và không bao giờ vượt `maxBid`.
- Khi hai Auto-Bid tranh chấp: ưu tiên `maxBid` cao hơn; nếu hai trần bằng nhau,
  ưu tiên người đăng ký sớm hơn. Các thao tác cập nhật được tuần tự hóa trong
  `AuctionService` để tránh lost update. Không yêu cầu giá phải là bội số của bước.
- Snapshot JSON cũ chưa có trường `increment` trong AutoBid sẽ được Server mặc
  định bằng `minimumIncrement` khi khôi phục.
- Giao thức `AUTO_BID` mới: `{"id":"...","maxBid":2000000,"increment":200000}`.
  Client không gửi `increment` (bản cũ) được Server chấp nhận và dùng Seller minimum.

## Kiểm thử và nghiệm thu

1. Seller minimum 100.000, current 1.000.000. Manual Bidder A: 1.050.000 bị từ chối;
   1.500.000 được chấp nhận.
2. Seller minimum 100.000, current 1.000.000. Auto A chọn max 2.000.000, step
   300.000 -> giá đầu tiên 1.300.000.
3. Auto B max 2.500.000, step 200.000 -> giành quyền dẫn đầu, giá 2.200.000.
4. Auto A max 2.000.000, step 600.000; B max 2.000.000, step 100.000 -> A thắng
   khi cùng ceiling nếu đăng ký trước.
5. Dùng đồng thời nhiều Client, theo dõi giá realtime; test Maven JUnit và CI.

## Cập nhật dự án hiện tại

Chỉ chép bản vá vào thư mục **có `pom.xml` và các thư mục `auction-client`,
`auction-common`, `auction-core`, `auction-server`**. Bản ZIP patch đã bỏ thư mục
cha dư thừa để Windows hiển thị yêu cầu ghi đè tệp.

Tắt server + mọi client, lưu `data/state.json`, chép patch vào đúng thư mục gốc,
chọn Replace, sau đó tại thư mục gốc dùng:

```powershell
.\mvnw.cmd clean verify
.\run-server.bat
```

Chạy `.\run-client.bat` ở Terminal thứ hai. Không khởi động JAR build cũ.
