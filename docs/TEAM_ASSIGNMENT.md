# Phân công công việc 4 thành viên — theo module

| Thành viên | Owner module | Kết quả cần nghiệm thu | Peer reviewer |
| --- | --- | --- | --- |
| TV1 – Core/OOP | `auction-common`, `auction-core` | Class diagram, subtype, Factory, Singleton, domain, auto-bid, anti-sniping | TV4 |
| TV2 – Networking/Storage | `auction-server` và `auction-core/repository` | Socket đa client, login session, event fanout, JSON storage và restore | TV1 |
| TV3 – GUI/UX | `auction-client` | JavaFX MVC/FXML/CSS, login, thị trường, seller CRUD, giá trực tiếp, biểu đồ | TV2 |
| TV4 – QA/Integration/Release | `auction-core/src/test`, `.github`, `docs`, Maven root | JUnit, concurrent stress, test E2E, CI, Checkstyle, README, đóng gói 2 JAR | TV3 |

Mỗi người làm chủ một module chính **nhưng phải giải thích được toàn bộ code**.

Quy trình: issue → feature branch `feature/tvX-short-name` từ `dev` → commit Conventional Commits → Pull Request → ít nhất 1 reviewer → green CI → merge dev. `main` chỉ nhận các mốc phát hành. Không gộp toàn bộ bài bằng một commit duy nhất; lịch sử git cần phản ánh công việc thật, không tạo commit giả lùi thời gian.

Mỗi tuần, TV1/TV2 demo invariant và logic, TV3 demo màn hình kết nối, TV4 gửi báo cáo coverage + bug count. Luân phiên reviewer để mỗi người hiểu các module khác.
