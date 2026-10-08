# Plan: Allowlist địa chỉ email ingest (08102026)

## Mục tiêu

Thêm cơ chế cấu hình danh sách địa chỉ email được phép đọc. UI có thể thêm/xóa
từng địa chỉ; ingest chỉ tải và xử lý email có sender khớp allowlist. Email
không khớp không được tải attachment, không tạo job và không publish event.

## Hiện trạng đã đối chiếu

- API đã có `GET/PUT /api/config` và lưu cấu hình singleton trong
  `runtime_config`.
- UI `ConfigPage` và `ConfigViewModel` đã có nhóm quản lý `blacklistSenders`.
- Ingest `EmailIngestService` hiện nhận mọi sender, chỉ bỏ qua sender trong
  blacklist sau khi IMAP đã trả message.
- `JavaMailEmailClient` đã có predicate `shouldDownload` ở bước envelope; đây
  là điểm phù hợp để lọc sender trước khi đọc body/attachment.
- `RuntimeIngestionConfig` đang đọc cấu hình mode từ DB nhưng chưa đọc danh
  sách sender.

## Câu hỏi cần xác nhận

### Q1. Khi allowlist rỗng

- **A (khuyến nghị theo yêu cầu hiện tại):** không đọc email nào. Ưu điểm là
  fail-closed, đúng nghĩa “chỉ đọc địa chỉ có trong danh sách”; nhược điểm là
  sau khi bật tính năng phải cấu hình ít nhất một địa chỉ.
- **B:** allowlist rỗng nghĩa là cho phép tất cả sender. Ưu điểm tương thích
  với hành vi cũ; nhược điểm là một cấu hình thiếu có thể làm ingest đọc nhầm
  toàn bộ mailbox.
- **Phương án khác do user nhập:**

**Lựa chọn của user:** A (08/10/2026).

### Q2. Phạm vi tương tác với blacklist hiện tại

- **A (khuyến nghị):** giữ `blacklistSenders` để tương thích, nhưng allowlist
  được áp dụng trước; sender phải nằm trong allowlist và không nằm trong
  blacklist mới được đọc. Ưu điểm không phá cấu hình cũ; nhược điểm tồn tại
  hai lớp cấu hình.
- **B:** thay thế blacklist bằng allowlist, migration dữ liệu cũ sang danh
  sách mới theo quy tắc do user cung cấp. Ưu điểm mô hình đơn giản; nhược điểm
  có nguy cơ thay đổi hành vi blacklist hiện tại.
- **Phương án khác do user nhập:**

**Lựa chọn của user:** A (08/10/2026).

### Allowlist mặc định ban đầu

User cung cấp danh sách trong ảnh và yêu cầu dùng làm allowlist mặc định:

- `hiepdongbay@vatm.vn`
- `atd@caa.gov.vn`
- `dangbm@caa.gov.vn`
- `thaopt@caa.gov.vn`
- `nhithao169@mail.com`
- `dungdn@caa.gov.vn`
- `anhkd@caa.gov.vn`
- `giangpth@caa.gov.vn`
- `sontt@caa.gov.vn`
- `haanhlv@caa.gov.vn`
- `haanhlv@gmail.com`
- `thanhntm@caa.gov.vn`
- `anhld@caa.gov.vn`
- `duongvtt@caa.gov.vn`
- `lynk@caa.gov.vn`
- `tupt@caa.gov.vn`

## Phương án Codex đề xuất

- Thêm `allowedSenders` vào `RuntimeConfigEntity`, `RuntimeConfigDto`,
  `RuntimeConfigService` và bảng collection riêng `runtime_config_allowlist`.
- UI đổi nhóm hiện tại thành “Địa chỉ email được đọc”, có ô nhập, nút thêm,
  danh sách từng email và nút xóa từng dòng; giữ blacklist ở khu vực riêng nếu
  chọn Q2-A.
- Mở rộng `RuntimeIngestionConfig` để đọc allowlist từ DB với fallback an toàn
  theo lựa chọn Q1.
- Thêm predicate case-insensitive, trim và chuẩn hóa địa chỉ ở
  `JavaMailEmailClient` hoặc lớp dùng chung; lọc envelope trước khi parse body.
- `EmailIngestService` vẫn giữ lớp kiểm tra thứ hai để bảo vệ khi có caller
  khác gọi trực tiếp hoặc cấu hình thay đổi giữa lúc fetch và ingest.
- Email bị loại bởi allowlist không tạo `EmailMetadata`, không tạo `SyncJob`,
  không lưu attachment incoming và không acknowledge như processed; hành vi
  mailbox đối với email ngoài allowlist cần chốt khi triển khai.
- Thêm test API/UI model, runtime config, IMAP predicate và ingest service.

## Ràng buộc an toàn

- Không log password hoặc body email.
- Không tải attachment trước khi kiểm tra sender allowlist.
- Không tự coi im lặng là xác nhận Q1/Q2.
- Không deploy/push khi chưa có xác nhận và kiểm thử.

## Phạm vi file dự kiến

- `aerosync-api`: entity, DTO, service, tests.
- `aerosync-ingest`: properties/runtime config, IMAP filtering, ingest tests.
- `aerosync-ui`: model, API client, ViewModel, `ConfigPage.xaml`.
- `docs/change-log/08102026.md` sau khi phần triển khai được xác nhận.
