# Plan: Ghi log chi tiết khi ghi/chỉnh sửa ATFM (19102026)

## Mục tiêu
Khi AeroSync thực hiện `INSERT` hoặc `UPDATE` vào các bảng ATFM, phải ghi nhận cả thành công và thất bại với thời gian bắt đầu/kết thúc, thời lượng, giấy phép, bảng đích, số dòng, khóa sinh ra và lỗi kỹ thuật đầy đủ.

## Phạm vi
- `JdbcAtfmScheduleGateway`: log ứng dụng tại điểm giao dịch JDBC ATFM, bao gồm start/success/failure và rollback.
- `PermitImportCoordinator`: ghi bản ghi audit vào bảng nội bộ `audit_logs` cho từng insert/update ATFM thực tế.
- Giữ nguyên schema hiện có; tái sử dụng `audit_logs`, không ghi mật khẩu hoặc nội dung Word thô.
- Bổ sung test cho audit success/failure và giữ các test gateway hiện có.
- Không ghi dữ liệu ATFM thật, không replay, không deploy/restart trong lượt phát triển.

## Giả định và lựa chọn mặc định
- “Log” gồm hai lớp: log ứng dụng của worker (WinSW thu vào log worker) và audit DB (`audit_logs`) để tra cứu qua API `/api/audit-logs`.
- Timestamp dùng thời gian có offset trong log/output; `audit_logs.timestamp` tiếp tục được tạo bởi `@PrePersist`.
- Chi tiết lỗi giữ message/cause chain/Oracle code; không ghi stack trace vào CLOB audit nhưng stack trace vẫn có trong log ứng dụng.
- Thao tác duplicate/no-op không được coi là insert/update thành công; luồng audit hiện có vẫn ghi trạng thái duplicate.

## Thiết kế
1. Gateway ghi `ATFM_DB_WRITE_START`, `ATFM_DB_WRITE_SUCCESS` hoặc `ATFM_DB_WRITE_FAILURE` với operation, permit, nhóm bảng SC/NO, thời gian, duration, IDs và exception.
2. Coordinator bao quanh mỗi lệnh gateway `insert/update`, ghi action `ATFM_INSERT`, `ATFM_UPDATE`, `ATFM_INSERT_FAILED`, `ATFM_UPDATE_FAILED` vào `audit_logs`.
3. Việc ghi audit là best-effort: nếu audit DB lỗi thì không che kết quả ghi ATFM; logger ứng dụng ghi nhận lỗi audit.
4. Không đưa password, raw permit content hoặc dữ liệu nhạy cảm vào log.

## Kiểm tra
- Unit test coordinator xác nhận success/failure đều gọi audit với thời gian/thao tác/chi tiết.
- Test gateway hiện có vẫn đạt.
- Chạy `git diff --check`, staged diff và commit local riêng; không push.

## Status
Completed on 19102026.

- Gateway records `ATFM_DB_WRITE_START`, `ATFM_DB_WRITE_SUCCESS`, and `ATFM_DB_WRITE_FAILURE`.
- Coordinator writes best-effort `ATFM_INSERT`, `ATFM_UPDATE`, `ATFM_INSERT_FAILED`, and `ATFM_UPDATE_FAILED` records to `audit_logs`.
- API exposes detail for `ATFM_*` actions and the UI preserves that technical detail.
- Compile and targeted tests were run; no ATFM data was written and no deployment/restart was performed.
