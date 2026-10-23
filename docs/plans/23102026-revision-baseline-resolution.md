# Phiếu Plan 23102026 - Chuẩn hóa tìm permit gốc cho revision

## Mục tiêu

Khảo sát và sửa thống nhất các nhánh revision để dùng được permit gốc đã tồn tại trong ATFM, nhưng không tự suy diễn sang một permit khác khi chứng cứ trong Word không đủ.

## Phạm vi dự kiến

- `RevisionReconciliationStep`, `SchedulePermit` và gateway ATFM liên quan đến baseline revision.
- Các kiểm thử cho revision seasonal, revision `NO`, revision nhiều ngày `NO` lưu ở `SC`, revision có số phép tham chiếu, và trường hợp lệch kho.
- Thông báo lỗi cho trường hợp thiếu tham chiếu tường minh nhưng baseline cùng permit đã được xác minh.

Không thay đổi dữ liệu ATFM hiện hữu và không tự phát lại các file đang quarantine.

## Kết quả khảo sát ban đầu

- Revision seasonal đã tìm baseline ở `SC`, gồm cả chuỗi revision trước đó.
- Revision `NO` đã tìm `NO`, rồi fallback sang `SC` cho dữ liệu `NO` nhiều ngày lưu theo cơ chế cũ.
- Revision có `referencedPermitId` đã đối chiếu đúng permit `SC` được tham chiếu.
- Lỗ hổng: revision `NO` nhiều ngày có bảng `OLD`/`NEW` và baseline `SC` cùng số phép đã tìm thấy vẫn bị chặn nếu Word không có cột/trường `Original permit`.
- Ví dụ OF-7974: baseline `O/F 07974/CHK/2026` tồn tại tại `T_PERMMASTER_SC`; ba dòng `OLD` khớp chính xác, nhưng workflow chặn tại `BR-MULTI-DAY-REVISION-REFERENCE-REQUIRED`.

## Câu hỏi cần xác nhận

### 1. Chính sách suy luận permit gốc khi Word không nêu số tham chiếu

**A. Chỉ suy luận sau khi xác thực chính xác (khuyến nghị)**

Cho phép dùng số phép của revision làm permit gốc chỉ khi: có bảng `OLD`, lookup ATFM tìm đúng một baseline `SC` cùng canonical permit id, và các dòng `OLD` khớp duy nhất. Các trường hợp permit khác số, thiếu baseline hoặc mơ hồ vẫn quarantine.

Ưu: xử lý OF-7974 và mẫu tương tự, không nới lỏng bảo vệ nhầm permit. Nhược: vẫn quarantine các Word không đủ dữ liệu để xác minh.

**B. Luôn dùng số phép revision khi thiếu tham chiếu**

Tự dùng normalized permit id cho mọi revision không có `Original permit`.

Ưu: ít bị quarantine hơn. Nhược: có thể cập nhật nhầm permit khi số revision không trùng permit gốc.

**C. Chỉ áp dụng riêng AXM/OF-7974**

Tạo ngoại lệ profile hãng AXM.

Ưu: phạm vi thay đổi rất hẹp. Nhược: lặp lại cùng lỗi với các hãng/mẫu CAAV tương tự.

**Phương án khác do user nhập:**

## Kiểm tra dự kiến

- Unit test cho từng nhánh baseline đã nêu và các trường hợp từ chối an toàn.
- Regression test với file quarantine OF-7974: parse đủ OLD/NEW, khớp baseline và không còn lỗi thiếu referenced permit.
- Chạy Maven test module worker theo phạm vi liên quan.

## Lựa chọn của user

Chờ xác nhận.
