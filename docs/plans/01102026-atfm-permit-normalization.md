# Plan: Chuẩn hóa ghi phép bay ATFM (01102026)

## Mục tiêu

Triển khai các quyết định đã chốt cho content phép mùa, remark, tàu bay, route,
subject email correction/cancellation và danh mục purpose song ngữ.

## Quyết định đã chốt

- Permit identity có `/S/` hoặc `/W/`: không ghi cả `PERMCONTENT` và `PERMCONTENT1`.
- PURPOSE chỉ ghi `PURPOSE_ID`, không xuất hiện trong `REMARK`.
- Không tự thêm A/B cho mã tàu bay đầu 3/7.
- Tàu bay tra `M_CRAFT`: 3 ký tự theo `CRAFT_IATA`, 4 ký tự theo `CRAFT_ICAO`;
  không có/không duy nhất dùng A000 và warning.
- Tách tàu bay từ đầu token đến dấu không phải chữ/số; loại SUB; remark chỉ giữ
  tàu phụ hợp lệ, bỏ tàu chính.
- Route bỏ token chỉ có chữ, kể cả DCT; chuẩn hóa separator thành `/`; token dài
  từ 6 ký tự sinh warning nhưng vẫn SAVED.
- Route không đọc được: permit thường SAVED với VIA prefix mặc định và warning;
  replacement phải kiểm tra trước khi xóa và dừng nếu thiếu route mới.
- Subject CANCEL/HUY: lưu metadata SKIPPED, giữ archive, không publish job.
- Subject CORRECT/UPDATE/CORR: tìm master SC/NO, validate trước, thay toàn bộ detail
  giữ nguyên PERM_ID; cập nhật master/content; luôn warning đã CORRECT.
- Purpose YAML là alias song ngữ; PURPOSE_CODE trong M_FLY_PURPOSE là nguồn chuẩn.

## Thứ tự triển khai

1. Seasonal content + remark không purpose.
2. M_CRAFT resolver + parser token/remark tàu phụ.
3. Route normalization/warning/fallback.
4. Subject cancellation/correction và transaction replace SC/NO.
5. Purpose alias catalog + DB validation.
6. Regression/full tests, nhật ký và commit từng phần.

## Ràng buộc an toàn

- Không xóa detail trước khi parse, aircraft, purpose và route mới hợp lệ.
- Không chọn tùy tiện khi M_CRAFT trả nhiều ICAO khác nhau.
- Không đưa file build/log/thay đổi cũ ngoài phạm vi vào commit.
- Không deploy/push khi chưa được yêu cầu.