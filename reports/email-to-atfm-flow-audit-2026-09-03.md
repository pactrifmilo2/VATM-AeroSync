# Audit luồng Email → ATFM

- Thời điểm kiểm tra: 2026-09-03
- Phạm vi: nhận email/thư mục, chống trùng, hàng đợi RabbitMQ, đọc Word/YAML,
  chuẩn hóa, đối chiếu dữ liệu ATFM, revision, ghi DB, lưu trạng thái, archive,
  retry/replay và acknowledge email.
- Phương pháp: đọc code, chạy kiểm thử trọng tâm và SELECT read-only trên DB.
- Không thay đổi code hoặc dữ liệu trong đợt audit này.

## Phát hiện cần sửa chung

| Ưu tiên | Khu vực | Vấn đề | Bằng chứng | Hướng sửa |
|---|---|---|---|---|
| P0 | Revision phép đột xuất | Dòng NEW được tìm trong ATFM bằng chính định danh NEW. Khi ngày/chặng thay đổi và callsign có nhiều dòng, hệ thống insert NEW thay vì update OLD. | ID 31807: file OLD `TBJ23 ZSNB-WSSL 07SEP`, NEW `TBJ23 VHHH-WSSL 06SEP`; DB giữ OLD và thêm NEW. | Mang cặp `OLD → NEW` tới gateway. Tìm dòng ATFM bằng OLD, sau đó ghi đè bằng NEW. Chỉ fallback theo callsign khi duy nhất; không chắc chắn phải quarantine. |
| P0 | Revision phép đột xuất | Remark `ddMMyyyy R(n)` chỉ được gắn khi map được baseline theo NEW/unique callsign. Lỗi P0 làm dòng NEW insert có remark thường (ví dụ `PAX`) thay vì `R(1)`. | ID 31807 có dòng mới remark `PAX`; yêu cầu nghiệp vụ là `02092026 R(1)`. | Sau khi map OLD, luôn thay `REMARK` dòng được sửa bằng `permit.revisionRemark()`; `n` là revision mới nhất. |
| P0 | Retry API | `SyncJobService.retryJob` publish RabbitMQ trước commit transaction. Worker có thể chạy trước khi trạng thái PENDING/RESERVED được commit, rồi transaction retry ghi đè trạng thái cuối. | DB có 620 job PENDING/IN_PROGRESS trong khi file đã FAILED/QUARANTINED/SAVED/SKIPPED; ví dụ job 14122, 14092, 14107. | Publish sau commit giống `TestReplayService`, hoặc dùng transactional outbox. Thêm test race và cơ chế recovery cho job PENDING quá ngưỡng. |
| P0 | Nhận file → hàng đợi | Email/FileSystem tạo job và lưu record trước khi publish RabbitMQ nhưng không có outbox/recovery. Nếu publish lỗi, email đã có metadata hoặc folder path đã marked-seen, nên scheduler không tự đưa lại job vào queue. | `EmailIngestService`/`FileSystemIngestService` gọi publisher trực tiếp sau khi persist. | Transactional outbox hoặc job quét PENDING/DOWNLOADED quá ngưỡng rồi publish lại có idempotency. |
| P1 | Aircraft revision | Việc kế thừa tàu bay ở revision phụ thuộc câu “other details unchanged”. Nhiều form OLD/NEW không có câu này nhưng cũng không có bảng aircraft mới; dữ liệu cũ vẫn phải được kế thừa. | ID 31807 báo không tìm được tàu bay và tạo A000, trong khi dòng OLD có craft GLEX. | Với revision có cặp OLD → NEW và New không có aircraft, lấy aircraft từ OLD/baseline đã map. Chỉ dùng A000 cho phép đột xuất nếu cả hai nguồn đều không có. |
| P1 | Mapping OLD/NEW nhiều dòng | Code positional mapping hiện chỉ có cho phép mùa; phép đột xuất không có mapping khi OLD/NEW khác route/date/callsign. | `RevisionReconciliationStep` chỉ fallback theo vị trí khi `seasonalPermit()`. | Áp dụng mapping một-một có kiểm soát cho phép đột xuất: exact OLD identity → unique callsign → paired table position khi số dòng tương ứng → quarantine nếu mơ hồ. |
| P1 | Replay/reset | Reset target dùng chung chưa có workflow an toàn. Khi target của phép gốc đã được revision dùng chung hoặc target tracking cũ không còn, replay trả unsafe/conflict. | Đã gặp ở các job revision trong đợt audit trước; `AtfmTestResetService` chỉ kiểm tra target tracking trực tiếp. | Không xóa target dùng chung. Thêm chế độ requeue/update-in-place có lock và kiểm tra ownership; phân biệt replay base/revision. |
| P1 | Trạng thái lịch sử | Có nhiều PermitImport `RESERVED` và SyncJob PENDING đã quá lâu, trong đó file đã terminal. Điều này làm UI, retry và báo cáo hiểu sai tình trạng. | SELECT read-only trả 458 PermitImport RESERVED và 620 state lệch. | Sau khi sửa retry, tạo công cụ preview rồi migration có backup/audit để đồng bộ trạng thái job/file/permit; không tự động sửa mù. |
| P1 | Archive | Tên archive chỉ có sender + thời gian đến giây + tên file, sau đó `REPLACE_EXISTING`. Hai file cùng tên/cùng sender xử lý trong một giây có thể ghi đè file đã archive. | `FileArchiverStep.moveTo`. | Bổ sung syncJobId hoặc hash ngắn vào tên archive; không `REPLACE_EXISTING`, tạo tên duy nhất. |
| P2 | Archive failure | Sau khi ghi ATFM thành công, archive lỗi chỉ đánh dấu archive FAILED; job vẫn SUCCESS và email có thể được acknowledge processed. | `FileProcessingPipeline.archiveSafely`. | Tách trạng thái write/archival, có retry archive; không mất khả năng resend khi archive thất bại. |
| P2 | Regression tests | Test coordinator cũ đang trái với quy tắc hiện hành: kỳ vọng insert revision khi base thiếu, trong khi luồng hiện tại quarantine non-scheduled revision. | `PermitImportCoordinatorTest.importPermit_insertsRevisionWhenReferencedBaseIsMissing` fail; 44 tests chạy, 1 error, 1 skipped. | Cập nhật fixture đúng phép mùa/đột xuất và thêm matrix test base missing. |

## Quy tắc cần giữ sau khi sửa

1. Chỉ phép đột xuất revision dùng OLD để tìm dòng ATFM rồi ghi đè NEW vào đúng dòng đó.
2. Chỉ dòng được thay đổi mới có remark `ddMMyyyy R(n)`; các dòng không nằm trong bảng OLD/NEW không bị thay đổi.
3. Nếu mapping OLD → target không duy nhất, không insert hoặc update; chuyển quarantine với errorMessage chỉ rõ các ứng viên.
4. Không tìm được tàu bay/đường bay ở phép đột xuất vẫn lưu theo quy tắc A000/via-error hiện hành, đồng thời giữ errorMessage cụ thể.
5. Mọi retry/replay phải publish sau commit, idempotent, có audit log và có recovery cho pending bị kẹt.

## Kiểm thử đã chạy

- Ingest: Deduplication 11/11, acknowledgement 6/6, email ingest đạt.
- Worker gateway/pipeline/revision: các test được chọn đạt; gateway 8 test, 1 skipped.
- API resend/replay/reset: 9/9 đạt.
- Bộ chọn toàn luồng dừng tại 1 test stale của PermitImportCoordinator nêu ở trên; cần sửa fixture trước khi coi suite xanh hoàn toàn.
