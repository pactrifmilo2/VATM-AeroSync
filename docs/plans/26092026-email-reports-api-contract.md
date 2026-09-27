# Plan: Hợp đồng API EmailReports backend (26092026)

## Mục tiêu

Triển khai hợp đồng backend cho `GET /api/reports/emails` và
`GET /api/reports/incoming`, summary trên cùng URL gốc, trạng thái DB trên
từng item, lọc file/phân trang ổn định, và API chi tiết Incoming nếu cần;
không thêm Web.config key hay thay đổi API jobs.

## Phạm vi dự kiến

- `aerosync-api`: controller, service, DTO, repository/specification và test.
- `aerosync-common`: chỉ khi cần truy vấn dùng chung hoặc thêm projection cần
  thiết.
- `aerosync-api/EMAIL-REPORT-API.md`: cập nhật hợp đồng công khai nếu schema
  thay đổi.
- `docs/change-log/26092026.md`: nhật ký sau khi một phần triển khai được kiểm
  tra.

Không bao gồm thay đổi UI tải trang, Web.config, API jobs, dữ liệu môi trường,
artifact `bin/obj/target`, log hoặc các thay đổi Git có sẵn ngoài phạm vi.

## Hiện trạng đã đối chiếu

- Email list đã có JPA filter/phân trang/sort nhưng summary chỉ hỗ trợ `from`/
  `to` và đếm status email.
- Incoming list hiện chỉ nhận `page`/`size`, đọc audit log theo trang; chưa có
  filter đầy đủ, summary, detail hoặc trạng thái DB.
- Row DTO chưa có `databaseState` và `hasDatabaseData`.
- Chưa thấy trong module hiện tại một repository/table SC/NO rõ ràng để xác
  minh phép bay; `flight_data`/ATFM gateway hiện nằm ở worker và cần đối chiếu
  với datasource/runtime thực tế.
- Working tree đang có nhiều thay đổi và file sinh tự động; các file đó được
  giữ nguyên và không đưa vào commit.

## Câu hỏi cần xác nhận

### 1. Nguồn xác minh `found/missing`

- **A (khuyến nghị):** Backend API truy vấn trực tiếp datasource/bảng SC và NO
  bằng một repository/read gateway dùng chung predicate định danh phép bay.
  Chính xác KPI hơn, nhưng cần chốt schema/cột/khóa nối thực tế.
- **B:** Dùng kết quả đã lưu trong `PermitImport`/worker hoặc `targetPermId`
  làm nguồn kết luận. Ít thay đổi hơn, nhưng không đáp ứng đầy đủ yêu cầu
  “found chỉ khi bản ghi thực sự tồn tại”.
- **C:** Chưa triển khai kết luận DB, trả `unknown` cho mọi item và bổ sung
  hook để nối datasource sau. An toàn về tính đúng, nhưng chưa đạt KPI.
- **Phương án khác do user nhập:**

**Lựa chọn của user:** A (1A + 2A + 3A, confirmed 27092026).

### 2. Đồng bộ snapshot giữa list và summary

- **A (khuyến nghị):** Summary tạo `snapshotId`; list nhận tùy chọn
  `snapshotId` và truy vấn cùng snapshot/read boundary. Đối chiếu chính xác,
  nhưng cần cơ chế snapshot/cache hoặc transaction phù hợp.
- **B:** Nhúng summary vào response list và giữ `/summary` cho count-only. Một
  request cho UI, nhưng làm thay đổi response list và client contract nhiều hơn.
- **C:** Không snapshot, ghi rõ dữ liệu có thể thay đổi giữa hai request. Dễ
  triển khai nhất nhưng không đảm bảo phép đối chiếu chính xác.
- **Phương án khác do user nhập:**

**Lựa chọn của user:** A (1A + 2A + 3A, confirmed 27092026).

### 3. Bộ lọc và tương thích API hiện hữu

- **A (khuyến nghị):** Thêm các filter bắt buộc `query`, `processingStatus`,
  `from`, `to`, `fileType` cho cả hai API, đồng thời giữ các filter Email cũ
  (`sender`, acknowledgement/job status) và áp dụng chúng nhất quán ở summary.
  Tương thích tốt nhất nhưng contract rộng hơn.
- **B:** Chỉ giữ đúng bộ filter mới, loại bỏ filter Email cũ. Contract gọn hơn,
  nhưng có nguy cơ phá client hiện tại.
- **C:** Giữ filter cũ chỉ trên Email, Incoming chỉ nhận bộ mới. Ít thay đổi
  hơn nhưng hai API không hoàn toàn đồng nhất.
- **Phương án khác do user nhập:**

**Lựa chọn của user:** A (1A + 2A + 3A, confirmed 27092026).

## Mặc định Codex đề xuất

User confirmed 1A + 2A + 3A on 27092026. The verified schema is
T_PERMMASTER_SC/T_PERMMASTER_NO; the backend will use the existing ATFM
datasource and match targetMasterId, targetPermId, and normalizedPermitId.
The API will retain filtered snapshots with an internal TTL and will not add a
Web.config key. Implementation order remains DB-state model, Email list/summary,
Incoming list/summary/detail, contract tests, documentation, and change log.
## Tiêu chí kiểm tra

- Filter được áp dụng trước count và page limit; `totalElements` sau lọc.
- File extension không phân biệt hoa thường, fallback từ `storedFileName` sang
  `attachmentName`, hỗ trợ `none` và extension không thuộc nhóm.
- Sort có khóa duy nhất, page size tối đa 100, ngày bao gồm hai đầu.
- Email và Incoming có summary/list schema tương thích; lỗi DB/count trả HTTP
  error, không trả số 0 giả.
- Fixture bao phủ `found`, `missing`, `error`, `pending`, `unknown` và kiểm tra
  các điều kiện nghiệm thu trong yêu cầu bàn giao.
