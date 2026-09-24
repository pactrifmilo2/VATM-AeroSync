# Plan — 25092026 — Thay thế OLD/NEW của revision PERMNO

## Yêu cầu và phạm vi đã xác nhận

User yêu cầu phép sửa đột xuất ghi PERMNO: OLD định danh dòng trong phép gốc để xóa; NEW chèn thay thế; các trường lịch lấy NEW, VIA theo tuyến mới, CRAFT/PURPOSE không thay đổi kế thừa phép gốc. Nhánh PERMSC (kể cả phép đột xuất được route vào SC) giữ nguyên. Chỉ sửa source/test, không replay, deploy hoặc ghi ATFM thật.

## Đối chiếu thực tế trước mutation source

- Git HEAD 9367645a; source sạch, các log/build/Word/plan cũ có thay đổi không thuộc phần này sẽ giữ ngoài commit.
- File cùng hash e42a02d6 ở processed ngày 24/09 mang timestamp 170129. OLD/NEW cùng ngày 26/09/2026; EOBT 1800→2000; LFPB→LFLL; EIBT 0655+1→0830+1. Bổ sung fixture đổi ngày để kiểm chứng yêu cầu tổng quát.
- Hiện NO update theo business row hoặc fallback callsign, rồi insert phần thiếu; chưa delete/insert. Parser có profile includeEta=false, dùng một route table chung cho OLD và NEW; purpose mặc định PAX có thể che mất purpose kế thừa.

## Quy tắc kỹ thuật an toàn (giả định công khai)

- Chỉ áp dụng thay thế khi revision thực sự route NO. SC không dùng nhánh mới.
- OLD khớp trong đúng master/permId bằng callsign, ngày, cặp sân bay, giờ; chỉ khớp duy nhất. Không xóa toàn phép, không xóa theo callsign đơn lẻ; thiếu/mơ hồ phải báo lỗi.
- Xác minh toàn bộ OLD trước mutation; xóa và chèn trong một transaction. Lỗi insert phải rollback cả delete và reference.
- Retry chỉ là no-op khi revision đã áp dụng và NEW khớp đủ; không mặc nhiên bỏ qua OLD chỉ vì NEW cũng tồn tại.
- NEW giữ trường lịch và dữ liệu thay đổi tường minh; phần không có thay đổi kế thừa baseline. VIA cũ chỉ được kế thừa khi cùng sector; không đem VIA của sector cũ sang sector mới.
- Ghép NEW↔OLD để kế thừa ưu tiên định danh/callsign duy nhất; trường hợp không xác định duy nhất dừng để review, không chọn ngẫu nhiên.

## Thực hiện và kiểm chứng

1. Bổ sung mapper và regression file thật/synthetic: OLD/NEW, ETA, route OLD/NEW, purpose/craft thay đổi hoặc kế thừa.
2. Reconcile riêng NO, giữ SC nguyên vẹn; kiểm tra baseline thiếu/mơ hồ và retry.
3. Gateway lock master, delete chính xác OLD, insert NEW, transaction và idempotency; test H2 Oracle mode, không DB thật.
4. Chạy bộ worker offline và build; ghi nhật ký 25092026, staged diff chọn lọc, commit local; không push.

## Kết quả thực hiện

- Hoàn tất mapper riêng NO + reconcile baseline + delete/insert transaction + bảo vệ retry. Không thay file YAML dùng chung; tái sử dụng alias/format/markers và override chọn OLD/NEW, include ETA, route/aircraft theo role chỉ sau khi xác định NO.
- Xóa nhánh UPDATE NO theo callsign cũ, giữ nguyên insertScWithinTransaction/updateScWithinTransaction. Mapping/chính sách parser SC vẫn cũ, có test đối chiếu.
- Bộ worker offline cuối: 400 tests, 0 failure/error, 30 skipped. 26 tests mới (12 mapper + 14 gateway) chạy có fixture OF-6916 thật, không skip; common đạt, package đạt. Test Docker/RabbitMQ chưa chạy.
- Chi tiết luồng và giới hạn fail-closed: `docs/permno-revision-replacement.md`. Không ghi DB thật, không replay/deploy/push.
