# Phiếu plan — 25092026 — Phần còn lại sau audit phép bay

## Mục tiêu và trạng thái

Tiếp tục các trường hợp chưa đạt trong `reports/permit-audit-25092026.md`, không thay đổi các lựa chọn nghiệp vụ đã xác nhận ngày 24/09. Lượt audit/sửa hiện tại đã tạo commit local; còn 74 nội dung cần xử lý tiếp. Phiếu này **không** tự cho phép replay, ghi DB, sửa file Word gốc hoặc deploy.

## Phạm vi độc lập, không cần chờ quyết định dữ liệu

- Test/triển khai format còn thiếu có thông tin nguồn rõ ràng: inline schedule, Unicode dash, bảng gộp/thiếu header, cancellation được cấp, cột ngày/giờ biến thể. Tách sửa kỹ thuật khỏi sửa giá trị nguồn.
- Đối chiếu sáu mẫu UNCLASSIFIED; không tự loại khỏi mẫu số để tăng tỷ lệ.
- Test chống nhầm carrier, mục đích theo chuyến, OLD/NEW và idempotency cho mỗi format mới. Không bật replay.
- Đọc danh mục/metadata chỉ khi có phạm vi kết nối phù hợp; không coi READABLE là resolve/import thành công.

## Câu hỏi cần xác nhận trước khi sửa dữ liệu mơ hồ

### Q1 — Ngày/giờ hoặc số phép nguồn sai/thiếu xử lý thế nào?

- [ ] A — **Khuyến nghị:** tiếp tục giữ lỗi và yêu cầu bản phép đính chính/nguồn xác thực; chỉ triển khai parser cho format rõ ràng. Lợi: không tạo dữ liệu bay sai; hại: các file lỗi nguồn chưa tự động nhập được.
- [ ] B — User cung cấp bảng đính chính từng SHA-256 + trường + giá trị đúng + nguồn phê duyệt; chỉ sau xác nhận mới xây cơ chế correction tường minh và test. Lợi: phục hồi được các bản xác thực; hại: cần chuẩn bị/kiểm soát dữ liệu sửa.
- [ ] Phương án khác do user nhập: …
- **Lựa chọn user: CHƯA XÁC NHẬN.** Không coi im lặng là đồng ý. Mặc định an toàn trong lúc chờ là không sửa dữ liệu nguồn, không ghi DB.

Các ví dụ: `00115`, `11430`, `20000`; `25JUIL26`; winter bắt đầu tháng 10/2026 nhưng kết thúc tháng 03/2026; QLB thiếu thành phần số phép; route gộp hai chặng trong một ô không có ranh giới rõ. Danh sách chi tiết/đường dẫn nằm trong Excel local; không sao chép thông tin cá nhân vào phiếu.

## Hoàn tất mỗi phần

Ghi nhật ký `docs/change-log/ddMMyyyy.md`, test fail-before/pass-after nếu tái hiện được, kiểm tra staged diff, commit local chọn lọc. Không amend/reset/push; không đưa Word/Excel/binary/build/artifact hoặc thay đổi có sẵn ngoài phạm vi vào commit.
