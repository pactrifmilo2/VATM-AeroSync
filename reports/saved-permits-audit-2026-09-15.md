# Đối chiếu hồ sơ SAVED ngày 15/09/2026

Phạm vi: 7 import SAVED từ 08:20:23 đến 09:54:59 ngày 15/09/2026 (giờ máy/DB địa phương). Khi bắt đầu có 6 file; hồ sơ SPQ được lưu thêm lúc 09:54:59 và đã được đưa vào kiểm tra. Số liệu là ảnh chụp tại thời điểm kiểm tra, không phải tổng kết cuối ngày.

Phương pháp: đọc bảng Word gốc trong `D:/vatm-storage/processed/2026/09/15`, SELECT Oracle trong transaction READ ONLY, đối chiếu `PERMIT_IMPORTS`, `FILE_RECORDS`, bảng master/detail SC/NO, `M_OPER`, `M_CRAFT_TYPE`, và chạy parser hiện tại riêng biệt không ghi DB. Không dùng trạng thái SAVED hoặc chỉ số lượng dòng để suy ra nội dung đúng. Không sửa code nghiệp vụ, YAML hay dữ liệu ATFM trong đợt kiểm tra này.

## Kết quả theo hồ sơ

| Job / Import | Hồ sơ | Target master / perm | Kết quả |
|---|---|---|---|
| 15878 / 3361 | SPA017 REV15 LD-32/B/S/2026VN | SC 203923 / 203429 | Sai tách OLD/NEW: 5 dòng hủy thừa |
| 15877 / 3360 | LD-3335, Bamboo QH9588/QH9589 | SC 203922 / 203428 | Hai dòng lịch khớp ngày, giờ, chặng, ngày trong tuần; hãng BAV khớp nguồn |
| 15876 / 3359 | LD-3185 REV2 HAI488 | NO 261486 / 232612 | Đúng ngày/chặng/giờ mới nhưng mục đích BUS bị thay thành PAX |
| 15874 / 3358 | OF-6516 REV1 VTASR | NO 261751 / 232877 | Đổi WSSS thành WSSL đúng; giữ ngày 16SEP, 0900–1225, GL7T và đăng ký VT-ASR |
| 15873 / 3357 | OF-6431 REV1 VTAKV | NO 261620 / 232746 | Đổi WSSS thành WSSL đúng; giữ ngày 16SEP, 0900–1240, B739 và đăng ký VT-AKV |
| 15871 / 3356 | OF-6605 FQA162 | NO 261865 / 232991 | Hãng mâu thuẫn, mất ETA, sai ngày cấp so với Word; có cảnh báo aircraft A000 |
| 15869 / 3355 | LD-3331 VASCO | SC 203921 / 203427 | Đủ 50 dòng lịch, nhưng 11 dòng mất VIA |

Các kết luận “khớp” chỉ áp dụng cho những trường được nêu; không thay thế kiểm chứng toàn bộ danh mục hàng không. LD-3331 và LD-3335 có khoảng hiệu lực nhiều ngày, nên lưu SC dù số phép không có `/S/` phù hợp với `SchedulePermit.usesScheduledStorage()` hiện tại. Không kết luận sai luồng chỉ vì số phép không có ký hiệu mùa. ID master có thể trùng giữa SC/NO; đối chiếu luôn cả loại bảng và PERM_ID.

## 1. SPQ REV15: đọc lịch mới thành lịch gốc

File: `dungdm_20260915_095459_email_000__SPA017__REV15_LD-32B_S26_17SEP-29SEP_ND_4a031213.docx`.

- Word: 5 dòng lịch gốc, 5 dòng lịch mới.
- Parser hiện tại: `NEW=5`, `OLD=10`, profile `spa017-vietnamese-seasonal-landing-revision`.
- ATFM: 15 detail. ID 964242–964246 là 5 lịch gốc mang remark hủy; 964247–964251 là 5 lịch mới cũng bị mang remark hủy; 964252–964256 là 5 lịch mới được thêm lại.
- Theo logic tạo revision mùa hiện tại, hồ sơ này cần 5 dòng hủy lịch gốc và 5 dòng mới, tức 10 dòng; thừa 5 dòng 964247–964251.

Nguyên nhân: `scheduleSectionText()` ghép tiêu đề bảng với header và dòng dữ liệu. `ORIGINAL_SCHEDULE_CONTEXT` coi cụm `DU KIEN` là dấu hiệu lịch gốc, trong khi header thông thường có “Giờ dự kiến đi/đến”. Marker này có thể lấn marker “Lịch bay mới”. `selectOriginalScheduleTables()` vì vậy nhận cả bảng mới; `materializeRevisionRows()` tạo remark hủy cho tất cả các dòng bị nhận nhầm. Có cùng dấu hiệu tiềm ẩn ở Bamboo cấp mới: parser trả OLD=2 dù file không có bảng lịch gốc; hiện chưa gây thêm dòng trong hồ sơ Bamboo vì không đi nhánh revision.

Ưu tiên sửa bộ nhận diện phần lịch từ ngữ cảnh/tiêu đề thực tế, không để nhãn cột “dự kiến” quyết định OLD. Kiểm tra độc lập số lượng và định danh OLD/NEW trước khi tạo dòng hủy.

## 2. OF-6605: trường nguồn mâu thuẫn và mất dữ liệu

File: `duongvtt_20260915_082025_email_000_OF-6605.9.2026VN_FQA162__16SEP26_gw_a25d1eda.docx`.

| Trường | Word / danh mục | ATFM |
|---|---|---|
| Hãng | Tên QUIKJET, IATA QO, callsign FQA162; nhưng ô ICAO ghi QFA | OPER_ID=QFA |
| Đối chiếu M_OPER | FQA / QO = Quikjet; QFA / QF = Qantas | Liên kết tới hãng khác tên/IATA trong nguồn |
| ETA | 1115 | NULL, detail 534254 |
| Ngày cấp | HANOI, 14SEP2026 | PERMDATE=2026-09-15 |
| Aircraft | B737-85P | CRAFT_ID=6881, A000 |

Parser ưu tiên ICAO ghi trong Word nên đã lấy QFA; đây là mâu thuẫn của tài liệu nguồn mà hệ thống chưa chặn/hiển thị như một xung đột nhận diện hãng. Không tự thay ICAO chỉ theo callsign khi chưa có quy tắc xử lý mâu thuẫn.

Profile được chọn là `caav-english-overflight-scheduled`: `includeEta: false` làm mất giờ đến; regex ngày cấp chỉ nhận `HANOI, d-MMM-yy`, không nhận `14SEP2026`, dẫn tới dùng ngày hiện tại. Cảnh báo trong FILE_RECORDS xác nhận không giải được B737-85P và dùng A000 theo cơ chế dự phòng. A000 là dữ liệu chưa giải quyết, không phải bằng chứng loại tàu bay đã được xác định chính xác.

## 3. LD-3185 REV2: mất mục đích chuyến bay kế thừa

File: `anhkd_20260915_093048_email_001_LD-3185_rev2_HAI488_15sep_G17.44-260914-170129_22195371.docx`.

Phép gốc ghi “Commercial Business Aviation/Air Taxi”. Revision chỉ đổi ngày bay và ghi “Other details unchanged”. Dòng về HAI488 (detail 532941) đã chuyển sang ngày 15SEP2026, LFPB–VVNB, 1000–2225 đúng, nhưng PURPOSE_ID=PAX. Dòng đi không bị sửa (532940) vẫn BUS. Aircraft FA8X được giữ; remark revision là `15092026 R(2)`.

Nguyên nhân: YAML `hai-english-landing-revision` gán `purpose.defaultId: PAX` khi revision không có mục đích. `ScheduleFlight.inheritMissingDetails()` chỉ nhận purpose của baseline nếu purpose hiện tại rỗng; PAX mặc định chặn kế thừa BUS. Cần phân biệt trường nguồn thực sự khai báo với giá trị mặc định.

## 4. LD-3331 VASCO: mất 11 VIA

File: `duongvtt_20260915_082023_email_001_LD-3331.9.2026VN_-0V-_16SEP26_-_30SEP26_e7b9a3ea.docx`.

Đã đối chiếu đủ 50 dòng: callsign 0V→VFC, sân bay, khoảng ngày, ngày trong tuần, ETD/ETA khớp dữ liệu Word. Các dấu `+` qua ngày ở ETA vẫn được giữ. Aircraft được giải thành ATR72.

11 detail VIA=NULL: 964203, 964204, 964205, 964207, 964208, 964209, 964210, 964221, 964222, 964230, 964231. Đó là các chặng SGN↔CXR và SGN↔TBB, nguồn ghi `W15 hoặc W2`.

Parser giữ chuỗi này; `AtfmViaResolver.normalizeRoute()` xử lý OR tiếng Anh nhưng chưa xử lý “hoặc”. Bước kiểm tra token không chấp nhận ký tự tiếng Việt, ném `AtfmDocumentRouteException`; `ViaResolutionStep` chuyển VIA sang null và ghi cảnh báo. FILE_RECORDS của job 15869 có cảnh báo không đọc được đường bay, nhưng import vẫn SAVED. Cần giữ nghĩa hai lựa chọn tuyến, không ghép thành tuyến nối W15→W2 một cách tùy tiện.

## Giới hạn và bước sửa ưu tiên

1. Sửa nhầm OLD/NEW và xác định chính xác 5 dòng hủy thừa SPQ trước mọi hành động chỉnh DB.
2. Bảo toàn purpose khi revision không thay đổi trường này.
3. Bảo toàn ETA/ngày cấp, kiểm tra xung đột ICAO–IATA–tên hãng ở OF-6605.
4. Xử lý tuyến thay thế tiếng Việt và hiển thị rõ SAVED kèm dữ liệu chưa giải quyết (VIA null/A000).

Chưa cập nhật lại các bản ghi sai, chưa replay hồ sơ, chưa triển khai phiên bản mới. Các thay đổi mã nguồn/YAML tồn tại trước đợt audit không được coi là đã triển khai; chỉ những kết quả tái hiện và SELECT thực tế nêu trên được dùng làm bằng chứng.
