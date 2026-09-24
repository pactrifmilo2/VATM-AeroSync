# Báo cáo audit phép bay — 25092026

## Phạm vi và kết quả

- Tiếp tục phiên `01a0c709-937b-7862-a0fc-4c5710319051` theo lựa chọn đã ghi trong `docs/plans/24092026-permit-mapping-audit.md`; giữ nguyên phần user đã sửa ở phiếu cũ, không đưa thay đổi cũ đó vào commit mới.
- Kho local `D:/vatm-storage`, từ **01/09/2026 đến 25/09/2026**, gồm incoming/processed/error/quarantine/error-format. Snapshot: `2026-09-25T00:55:25.655842+07:00`. Không đại diện các email chưa tải xuống hoặc máy deploy khác.
- **3,993** bản vật lý, **1,915** nội dung riêng biệt SHA-256; inventory không báo lỗi. Ngày lấy theo thư mục attachment hoặc tên ingest, 1 file fallback mtime; không dùng ngày bay. Chưa đối soát ngày kho với bảng EmailMetadata.
- Excel local: `reports/permit-format-audit-25092026.xlsx` (**không commit**).
- Workbook được đọc lại bằng POI và kiểm tra ZIP/XML độc lập: 6 sheet, 282 profiles, 213 nhóm ICAO (gồm UNKNOWN), 1.915 file, 351 dòng phân loại/lỗi, 3.993 aliases. Không có công thức ngoài hoặc macro.

| Kết quả | Số nội dung |
|---|---:|
| READABLE — parse + kiểm tra trường nguồn | 1564 |
| NON_PERMIT — đơn/biểu mẫu/kế hoạch/tài liệu khác | 277 |
| NO_PROFILE — chưa chọn được YAML | 11 |
| PARSE_ERROR — có format nhưng parse chưa được | 42 |
| FIELD_ERROR — lỗi trường/ngày/giờ | 14 |
| UNCLASSIFIED — cần phân loại thêm | 6 |
| READ_ERROR — file rỗng | 1 |

**Tỷ lệ đọc được: 1564/(1915 − 277) = 95.48%.** Đây không phải tỷ lệ nhập ATFM thành công. Có 470 mẫu READABLE còn trường CRAFT_ID/IATA-to-ICAO cần lookup; tất cả cột resolve/import là `NOT_TESTED`. A000 không được tính là resolve thành công. Hãng/profile không có mẫu ghi N/A.

20 nội dung Word trong error-format từ 15/09 đến snapshot đều được phân loại không phải phép đã cấp: đơn xin phép, kế hoạch bay, điện phối hợp slot hoặc tài liệu hành chính. Không tạo YAML để nhập nhầm những tài liệu này. Toàn bộ 282 profile hiện có đã được nạp/kiểm tra cấu hình và chạy detector trên corpus, **không có nghĩa mỗi profile đều có mẫu thực tế**; xem sheet Profiles.

## Đã sửa và kiểm chứng

1. **LD-79 RVS(7):** file thật có 2 OLD + 2 NEW. Regex cũ lấy thêm NEW vào OLD do tiêu đề cột “Giờ dự kiến đi”. Sửa ưu tiên heading gốc/mới; test thật và synthetic giữ đúng hai khoảng ngày. Chặn materialize lại cùng context để không tăng 4 thành 6 dòng. Không sửa dữ liệu đã lưu.
2. **Aircraft:** không lấy bảng lịch OLD làm bảng Aircraft sau khi registration trở thành optional; hồi quy KAR trả B772 thay vì B772/B739.
3. **Nhận diện:** thiếu ICAO chỉ nới điều kiện khi IATA ánh xạ duy nhất đúng hãng; không dùng SINGAPORE/SKY FUEL ở billing để nhận AIR7.
4. **Format:** thêm 47 YAML carrier-specific có test, sửa 5 YAML cũ; nhận EOBT/EIBT có (UTC), ETA (+1), ngày IGO năm bốn chữ số. Các profile mới giữ reviewOnly. Corpus gắn profile mới: 85 READABLE, 9 FIELD_ERROR, 2 PARSE_ERROR, 3 NON_PERMIT.
5. **Reference ngày email:** thêm regression cho email trước nửa đêm/ingest ngày sau, retry ổn định và fallback ingest sớm nhất. Giữ logic ngày email từ commit trước.
6. **Audit:** manifest đọc BOM, inventory có kiểm thử biên ngày/hash/attachment resend, bảo toàn nguồn và rehash trước parse; Excel có mẫu số, lỗi, minh chứng theo hash/path và giới hạn rõ ràng.

## Kiểm tra thực tế

- Worker offline: **373 test, 0 failure/error, 30 skipped** (`*,!WorkerIntegrationTest`); module common cũng đạt. 48 test profile mới/biến thể nằm trong bộ này.
- Test local LD-79 RVS(7) đã chạy không skip: 2 OLD + 2 NEW.
- Python inventory: **4/4** test đạt.
- Corpus opt-in: **2/2** test đạt, duyệt đủ 1.915 nội dung; pass của audit nghĩa là xuất đủ báo cáo, **không** phải mọi file parse được.
- Package worker/common thành công với `-DskipTests`; không deploy hoặc khởi động service.
- WorkerIntegrationTest từng lỗi vì Docker không hoạt động; chưa kiểm chứng tích hợp RabbitMQ/Docker.
- Lệnh tái chạy, định nghĩa mẫu số và bảo mật dữ liệu cục bộ: `docs/permit-corpus-audit.md`.

## Các phần chưa hoàn tất

Còn **74** nội dung ngoài NON_PERMIT chưa đạt. Không được coi đây là toàn bộ 100% format đã hỗ trợ. Một số là backlog kỹ thuật (format inline, bảng gộp/thiếu header, dấu gạch Unicode, phiên bản cancellation); một số là nguồn thật sai hoặc thiếu như ngày `25JUIL26`, giờ `00115`/`11430`/`20000`, ngày kết thúc trước ngày bắt đầu, thiếu tiền tố/số phép, tuyến gộp không thể gán an toàn và file rỗng.

Không tự bịa ngày, giờ, số phép hoặc route để tăng tỷ lệ. Quyết định sửa dữ liệu nguồn mơ hồ nằm ở `docs/plans/25092026-permit-audit-follow-up.md`. Các lỗi kỹ thuật xác định được không phụ thuộc quyết định đó nhưng vẫn còn cần triển khai/test tiếp. Chưa đối soát live catalog/DB cho toàn corpus, chưa replay/deploy, chưa sửa sáu dòng cũ của RVS(7).

### Backlog theo nội dung SHA-256

Không đưa tên người gửi, nội dung hành khách hoặc tài liệu nhị phân vào Git. Đối chiếu SHA-256 đầy đủ trong sheet Files/Errors để mở file local.

| SHA-256 (12 ký tự đầu) | ICAO | Nhóm | Profile |
|---|---|---|---|
| `8c3dc59e974f` | TVJ | NO_PROFILE | — |
| `3653ccfa7ece` | CLX | PARSE_ERROR | airline-generic-overflight-issued |
| `8fbeff419c91` | UNKNOWN | PARSE_ERROR | vna-vietstar-landing-issued |
| `4822ba531be7` | GIA | NO_PROFILE | — |
| `808b9cc1c77d` | UNKNOWN | PARSE_ERROR | vna-vietstar-landing-issued |
| `bb9d3ee2511b` | KMI | NO_PROFILE | — |
| `e3b0c44298fc` | UNKNOWN | READ_ERROR | — |
| `c5f990110cfc` | UNKNOWN | NO_PROFILE | — |
| `d031ba9bdf08` | UNKNOWN | PARSE_ERROR | prv-jetex-overflight-issued |
| `1571083980de` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued-na-operator |
| `d356da1a0385` | UNKNOWN | PARSE_ERROR | caav-generic-overflight-issued-na-operator |
| `7cf70ba40f27` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued-na-operator |
| `06b8aae7069f` | UNKNOWN | PARSE_ERROR | prv-hong-kong-jet-overflight-issued |
| `ac60a6bf1009` | TVJ | NO_PROFILE | — |
| `d158a3199a07` | MYU | NO_PROFILE | — |
| `c46a20786c9c` | PRV | FIELD_ERROR | caav-generic-overflight-issued-na-operator |
| `6a10db1d4ec6` | UNKNOWN | PARSE_ERROR | awc-english-landing-issued |
| `a7567a091d68` | TWB | PARSE_ERROR | airline-generic-seasonal-landing-revision |
| `1f254d4d0654` | ADZ | PARSE_ERROR | airline-generic-landing-revision |
| `246ef94501e0` | IGO | NO_PROFILE | — |
| `4811540a8194` | IGO | NO_PROFILE | — |
| `52f663d2ecb8` | UNKNOWN | PARSE_ERROR | prv-hong-kong-jet-overflight-issued |
| `c225afc86138` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued-na-operator |
| `5b1b74f80805` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued-na-operator |
| `f1c277a086a3` | SMR | UNCLASSIFIED | — |
| `4d71ba5ebae6` | TVJ | NO_PROFILE | — |
| `85ba895d373b` | LEA | NO_PROFILE | — |
| `29fdd9e6a4da` | PRV | FIELD_ERROR | caav-generic-overflight-issued-na-operator |
| `11f4eb42639e` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued |
| `c52a31149cf8` | JJA | UNCLASSIFIED | — |
| `09a59bf866c3` | MMA | PARSE_ERROR | airline-generic-overflight-scheduled |
| `958d1609b63b` | CAL | PARSE_ERROR | cal-caav-seasonal-overflight-issued |
| `95c5a4cadd24` | GTI | PARSE_ERROR | gti-caav-seasonal-overflight-issued |
| `666c1d352d4e` | SMR | UNCLASSIFIED | — |
| `4886a228ad70` | GTI | PARSE_ERROR | gti-caav-seasonal-overflight-issued |
| `393fe869b335` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued-na-operator |
| `cd23c116d173` | CSZ | PARSE_ERROR | airline-generic-overflight-scheduled |
| `7a3b61d652d0` | VJC | FIELD_ERROR | vjc-vietnamese-landing-issued |
| `7dced2a541bb` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `1520526a037c` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `884f1b7a4632` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `61282c6d4c05` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `40bada3b3454` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `76dcaef8c549` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `0412ad26cc65` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `ac583ceb27e3` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `a08ec8d74e18` | CSC | FIELD_ERROR | csc-seasonal-overflight-issued-audit |
| `31378b7a11c4` | VJC | PARSE_ERROR | vjc-seasonal-landing-revision-audit |
| `416f038c126e` | FDX | UNCLASSIFIED | — |
| `1922e81fc07c` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued |
| `c418c58ee604` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued |
| `6abddf2881d2` | UNKNOWN | PARSE_ERROR | caav-generic-landing-issued |
| `be851809c299` | UNKNOWN | PARSE_ERROR | caav-generic-overflight-issued-na-operator |
| `be1c32257593` | CXA | PARSE_ERROR | cxa-english-seasonal-overflight-issued |
| `9b346749720a` | GTI | PARSE_ERROR | gti-english-overflight-issued |
| `30d7c2f490d6` | ABD | PARSE_ERROR | prv-jetex-overflight-issued |
| `2f41b299b6af` | UNKNOWN | PARSE_ERROR | prv-jetex-overflight-issued |
| `888e96be2a8a` | UNKNOWN | PARSE_ERROR | prv-jetex-overflight-issued |
| `5203a200b0f9` | UNKNOWN | PARSE_ERROR | vjt-vistajet-overflight-revision |
| `45b323c1438d` | ADZ | FIELD_ERROR | adz-english-overflight-issued |
| `03995e0eccc9` | CLX | PARSE_ERROR | airline-generic-overflight-issued |
| `3be5d412af42` | VJC | UNCLASSIFIED | — |
| `a51f49613f2e` | CLX | PARSE_ERROR | caav-english-landing-revision |
| `15d75eae74a5` | UNKNOWN | PARSE_ERROR | vjt-vistajet-overflight-revision |
| `99b8cb7373da` | PRV | FIELD_ERROR | prv-sinojet-overflight-issued |
| `65b0694d2871` | TZP | PARSE_ERROR | airline-generic-overflight-issued |
| `c66f8c262315` | UNKNOWN | PARSE_ERROR | prv-sinojet-overflight-revision |
| `c3e843ab3e9f` | SPQ | PARSE_ERROR | spq-vietnamese-landing-issued |
| `38bba8d1e85c` | VJC | NO_PROFILE | — |
| `d2e8a9993572` | UNKNOWN | PARSE_ERROR | bsf-english-landing-issued |
| `dcf247514121` | JST | UNCLASSIFIED | — |
| `e60d85b9b111` | UEA | PARSE_ERROR | uea-seasonal-landing-issued-audit |
| `a909b0c498b6` | UNKNOWN | PARSE_ERROR | caav-generic-overflight-issued-na-operator |
| `9a28e2b08252` | UNKNOWN | PARSE_ERROR | prv-hong-kong-jet-overflight-issued |
