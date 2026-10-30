# Phiếu Plan 30102026 - Lưu chuyến trùng phép mùa LD

## Mục tiêu

Cho phép phép mùa LD mới giữ và ghi tất cả các chuyến có cùng bộ trường đã chốt: Flight number, Effective from, Effective to, Days of services, Departure Airport, ETD, Arrival Airport, ETA. Mỗi nhóm trùng phải có cảnh báo liệt kê toàn bộ số dòng; permit phải `SAVED` khi ghi ATFM thành công.

## Phạm vi cần quyết định

- Hành vi unique constraint production trên `T_PERMDETAIL_SC` đối với các dòng có cùng khóa nghiệp vụ.
- Cơ chế persistence để giữ mọi bản ghi trùng mà không báo `SAVED` khi transaction ATFM thất bại.
- Cảnh báo chỉ áp dụng phép mùa LD mới; không áp dụng revision mùa hoặc O/F.

## Bằng chứng hiện tại

- `JdbcAtfmScheduleGateway.insertScWithinTransaction` gửi toàn bộ flights vào batch insert, không lọc duplicate.
- Test gateway có kiểm tra metadata một unique constraint test trên `T_PERMDETAIL_SC(FLIGHTNBR, BEGINDATE)`, nhưng không chứng minh schema production có cùng constraint hay mọi bản sao đều bị chặn.
- Khi Oracle từ chối batch, transaction rollback và coordinator hiện không thể trả `SAVED` hợp lệ.

## Câu hỏi cần xác nhận

### 1. Chính sách cho unique constraint ATFM cản dòng trùng

- [ ] A - **Khuyến nghị:** Cho DBA xác nhận/điều chỉnh constraint để cho phép nhiều detail cùng khóa nghiệp vụ; AeroSync giữ toàn bộ dòng, insert trong một transaction, chỉ chuyển `SAVED` sau commit. Ưu: giữ đủ dữ liệu và bảo toàn ý nghĩa `SAVED`. Nhược: cần thay đổi/kiểm chứng schema ATFM production.
- [ ] B - Bỏ qua các detail trùng tại tầng ghi và lưu các dòng còn lại. Ưu: thường tránh được unique conflict. Nhược: trái yêu cầu giữ các chuyến trùng; không khuyến nghị.
- [ ] C - Nếu Oracle từ chối, vẫn đánh dấu `SAVED` và chỉ ghi cảnh báo. Ưu: job không vào trạng thái lỗi. Nhược: dữ liệu ATFM không được lưu đầy đủ nhưng trạng thái báo thành công; không khuyến nghị và không triển khai.
- [ ] Phương án khác do user nhập: ____________________

**Lựa chọn của user:** Chưa xác nhận.

## Phạm vi độc lập đã triển khai

Cảnh báo chặng quá cảnh cho LD mùa được thực hiện riêng, không phụ thuộc quyết định trên: sau resolve ICAO, khi cả hai sân bay không bắt đầu bằng `VV`, giữ chuyến, thêm warning theo dòng và tiếp tục import bình thường.
