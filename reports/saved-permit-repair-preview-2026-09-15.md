# Preview sửa dữ liệu SAVED — 15/09/2026

Preview này được lập theo lựa chọn: tạo danh sách để duyệt trước. Không có câu lệnh `DELETE`, `UPDATE`, `INSERT`, `COMMIT` hoặc replay nào được chạy.

## Phạm vi

| Import | Job | Hồ sơ | Dữ liệu hiện tại | Dự kiến |
|---:|---:|---|---|---|
| 3355 | 15869 | LD-3331 VASCO | `SC:203921/203427`, 50 detail, 11 VIA null | Giữ target `SC`; cập nhật lại 50 detail từ Word, bổ sung VIA/đăng ký bị null |
| 3356 | 15871 | OF-6605 FQA162 | `NO:261865/232991`, QFA, ETA null, A000 | Import lại bằng profile FQA, operator FQA, ETA 1115; aircraft cần xác nhận vì ATFM chưa có B737-85P |
| 3357 | 15873 | OF-6431 VTAKV REV1 | `NO:261620/232746`, operator PRV | Duyệt lại operator theo hồ sơ N/A và baseline; không tự đổi sang mã khác |
| 3358 | 15874 | OF-6516 VTASR REV1 | `NO:261751/232877`, operator PRV | Duyệt lại operator theo hồ sơ N/A và baseline; không tự đổi sang mã khác |
| 3359 | 15876 | LD-3185 HAI488 REV2 | `NO:261486/232612`, purpose PAX | Cập nhật lại detail mới với purpose BUS và remark `15092026 R(2)` |
| 3361 | 15878 | LD-32/B/S/2026 REV15 SPQ | `SC:203923/203429`, 15 detail | Xóa 5 detail hủy thừa, giữ 5 detail hủy + 5 detail mới |

## Trạng thái code sau sửa

- Permit đột xuất nhiều ngày theo quy tắc hiện tại vẫn có thể được lưu ở `SC`; LD-3331 phải giữ `SC` theo nghiệp vụ. Không dùng riêng hậu tố permit để tự động đổi target của hồ sơ đã xác định là lịch mùa.
- Profile `fqa-english-overflight-issued` nhận FQA, giữ ETA và nhận ngày `14SEP2026`.
- Profile HAI mặc định BUS khi revision không khai báo lại purpose.
- Profile SPQ nhận bảng `2.1` là OLD và `2.2` là NEW; parser hiện trả `OLD=5`, `NEW=5`.
- Parser không tự ghép “W15 hoặc W2” thành một airway giả. Route này cần được xử lý như lựa chọn thay thế trước khi ghi VIA.

## Cần duyệt trước khi sửa Oracle

1. Xác nhận giữ target SC `203921/203427` của LD-3331 và cập nhật lại 50 detail từ Word.
2. Xác nhận xử lý các target SC/NO cũ của SPQ `203923/203429` theo số detail nêu trên.
3. Xác nhận mã operator cho VTAKV/VTASR khi Word ghi IATA/ICAO là N/A; preview hiện giữ PRV để tránh đoán hãng.
4. Xác nhận aircraft `B737-85P` của FQA trước khi thay A000.

Lệnh tạo lại preview dạng SELECT:

```powershell
sqlplus.exe -L -S <ATFM_USER>/<PASSWORD>@<HOST>:<PORT>/<SERVICE> `
  @scripts/preview-saved-permit-repair.sql
```
