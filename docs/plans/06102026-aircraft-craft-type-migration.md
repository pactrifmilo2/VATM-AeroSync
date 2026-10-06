# Plan: Chuyển resolver tàu bay về M_CRAFT_TYPE (06102026)

Trạng thái: hoàn tất phần code và kiểm thử local; chưa kiểm tra schema production,
chưa deploy và chưa push.

## Mục tiêu

Đổi toàn bộ luồng resolve aircraft của worker để dùng đúng quan hệ ATFM:

- `T_PERMDETAIL_SC.CRAFT_ID` và `T_PERMDETAIL_NO.CRAFT_ID` tham chiếu
  `M_CRAFT_TYPE.CRAFT_ID`.
- Mã ICAO 4 ký tự tra bằng `M_CRAFT_TYPE.MA`.
- Mã IATA 3 ký tự tra bằng `M_CRAFT_TYPE.SOHIEU`.

## Phạm vi

- Resolver `AtfmAircraftTypeResolver` và preference catalog.
- Gateway kiểm tra reference data trước khi insert/update.
- Audit SELECT-only aircraft dictionary.
- Fixture/test H2 và assertion liên quan đến duplicate, fallback A000, cache
  và aircraft resolution.
- Change log ngày `06102026`.

## Giả định đã xác nhận

- `CRAFT_ID` là giá trị số được ghi trực tiếp vào detail và có thể xuất hiện ở
  nhiều dòng `M_CRAFT_TYPE`; duplicate cùng mã nhưng khác `CRAFT_ID` vẫn phải
  xử lý bằng preference hoặc báo ambiguous.
- Các số ID trong `aircraft-preferences.yaml` được giữ nguyên và diễn giải là
  `M_CRAFT_TYPE.CRAFT_ID`.
- Chưa đổi quy tắc MTOW; resolver vẫn trả `0` vì yêu cầu hiện tại chỉ đổi bảng
  và tên cột mapping.
- Không sửa dữ liệu production, không chạy INSERT/UPDATE/DELETE và không
  deploy.

## Thứ tự thực hiện

1. Đổi SQL/index/lookup và lỗi tham chiếu runtime.
2. Đổi fixture H2 và audit live dictionary.
3. Chạy resolver, pipeline, gateway và compile regression.
4. Ghi change log, kiểm tra staged diff và commit local; không push.

## Tiêu chí hoàn thành

- Không còn code runtime tra `M_CRAFT`, `CRAFT_ICAO` hoặc `CRAFT_IATA` cho
  aircraft resolver.
- Runtime dùng đúng `M_CRAFT_TYPE(CRAFT_ID, MA, SOHIEU)`.
- Gateway validate `M_CRAFT_TYPE.CRAFT_ID`.
- Test xác nhận cả MA/ICAO, SOHIEU/IATA, duplicate preference, ambiguous,
  unknown và cache.
- `git diff --check`, targeted test và compile thành công.
