# Phiếu plan — 24092026 — Purpose, remark, reference và audit format

Trạng thái: khảo sát xong bước đầu; chờ lựa chọn cho các quy tắc còn mơ hồ. Chưa sửa code ứng dụng, chưa ghi database.

## Yêu cầu đã xác định

1. Đối chiếu mục đích bay với ATFM.M_FLY_PURPOSE, lưu PURPOSE_CODE.
2. Chỉ CAR, FER, TECH, AMB, VIP được thêm vào remark; áp dụng phép mới/revision, mùa/đột xuất.
3. Revision đột xuất, dòng NEW: [purpose đặc biệt] EM ddmmyyyy R(n) [craft nếu >=2 loại].
4. Revision mùa, dòng OLD: HUY REF <số phép đến hết /S hoặc /W> [craft nếu >=2 loại].
5. Revision mùa, dòng NEW: [purpose đặc biệt] EM LAY <số phép mới> REF <số phép gốc> [craft nếu >=2 loại].
6. Reference đột xuất: EM ddmmyyyy; mỗi lần sửa thêm dòng EM ddmmyyyy R(n), theo refer.txt. Reference mùa: EM ddmmyyyy, không kèm R(n).
7. Aircraft đọc/ánh xạ thất bại: ghi nguyên nhân cụ thể, resolve mã A000 sang CRAFT_ID trong danh mục và tiếp tục lưu. Không đưa chuỗi A000 vào cột numeric CRAFT_ID; nếu danh mục không có A000 thì phải báo lỗi cấu hình.
8. Phép đột xuất LD: chỉ lưu chặng có ít nhất một ICAO bắt đầu VV, sau chuẩn hóa sân bay; không áp dụng lọc này cho phép mùa hay O/F.
9. Điều tra LD-79/A/S/2026VN RVS(7), phân biệt dòng OLD đánh dấu hủy có chủ đích với duplicate; sửa chống lặp dùng chung các hãng.
10. Rà toàn bộ Word trong error-format từ 15/09/2026 đến ngày chạy audit, thêm format thiếu; kiểm tra các YAML bằng corpus và kiểm tra trường parse.
11. Xuất Excel danh sách hãng có profile riêng, mẫu số/số đọc được/tỷ lệ, lỗi và file minh chứng. Excel không commit theo yêu cầu loại tài liệu nhị phân trước đó.

## Khảo sát ban đầu

- Đã đọc refer.txt và via.txt, giữ nguyên hai file.
- PurposeResolutionStep hiện không nhận tiền tố số mục và Flight(s) trong một số tiêu đề; fallback về purpose từ parser/YAML.
- Danh mục ATFM có 29 mã, gồm CHT/PAX, OTH/TECH, VIP/VIP+P: chưa thể coi các mã này là tương đương.
- Kho local D:/vatm-storage/error-format/2026/09: ngày 15 có 1 Word, 16 có 1, 17 có 2, 18 có 5, 24 có 11; tổng 20. Chưa khảo sát toàn bộ kho khác hoặc máy deploy khác.
- Chưa xác định chính xác file/job RVS(7); cần tra metadata/API/DB bằng phép và năm, không chỉ tên file.

## Câu hỏi cần lựa chọn

### Q1. Khi mục đích khớp nhiều mã hoặc không đủ thông tin

- A (khuyến nghị): dùng nhãn mục đích từng chặng trước, sau đó mục đích chung; chỉ nhận mã danh mục khi rõ nghĩa. Trường hợp không rõ ghi lỗi yêu cầu kiểm tra, không tự đoán PAX/OTH. “Thuê chuyến chở khách” ưu tiên CHT nếu không có purpose riêng theo chặng.
- B: khi không rõ dùng OTH kèm cảnh báo và vẫn lưu; “Thuê chuyến chở khách” ưu tiên PAX.
- Phương án khác user nhập: ...
- Lựa chọn user: CHƯA XÁC NHẬN.

### Q2. Độ dài số phép trong remark dòng NEW của revision mùa

- A (khuyến nghị): cả số phép mới và số phép gốc đều rút đến /S hoặc /W, giống dòng OLD; giữ R(n) thuộc số phép mới. Dùng khoảng trắng: HUY REF, EM LAY.
- B: dòng OLD rút gọn như yêu cầu; dòng NEW dùng số phép đầy đủ gồm cơ quan/năm cho cả phép mới và gốc.
- Phương án khác user nhập: ...
- Lựa chọn user: CHƯA XÁC NHẬN.

### Q3. “Ngày đọc file” khi retry cùng job và khi resend

- A (khuyến nghị): cố định ngày xử lý lần đầu của job theo Asia/Saigon; retry cùng job không đổi ngày, email resend tạo job mới dùng ngày mới. Tránh mỗi lần retry thêm một dòng reference khác nhau.
- B: mỗi lần xử lý lại dùng ngày thực tế hiện tại, kể cả retry cùng job.
- Phương án khác user nhập: ...
- Lựa chọn user: CHƯA XÁC NHẬN.

### Q4. Mẫu số thống kê tỷ lệ đọc được theo hãng

- A (khuyến nghị): quét Word của tất cả trạng thái trong khoảng 15/09 đến ngày audit trên kho local, khử trùng bằng SHA-256; đọc được = detect + parse + kiểm tra các trường bắt buộc đạt. Tách tỷ lệ resolve danh mục và kết quả nhập thực tế thành cột khác; hãng không có mẫu ghi N/A.
- B: chỉ tính trên file error-format cùng khoảng ngày; tỷ lệ này là tỷ lệ phục hồi corpus lỗi, không đại diện toàn bộ email hãng.
- Phương án khác user nhập: ...
- Lựa chọn user: CHƯA XÁC NHẬN.

## Các phần triển khai, kiểm chứng và commit

1. Purpose: sửa extraction trường được gắn nhãn, mapping danh mục, chống lấy từ tên hãng/ghi chú. Test tiếng Việt/Anh, đa mục đích và dữ liệu thiếu.
2. Remark/reference: gom quy tắc dùng chung, đồng hồ có thể kiểm thử, giữ lịch sử reference, không append trùng khi retry. Test đầy đủ SC/NO, OLD/NEW, các mã đặc biệt, 0/1/nhiều loại tàu.
3. Aircraft và lọc LD: tách đọc aircraft khỏi registration; nhận 321 -> A321; fallback A000 có nguyên nhân cụ thể; lọc chặng ngoài VN sau resolve. Kiểm tra trường hợp tất cả chặng bị loại.
4. Revision duplicate: đọc file và các dòng của phép RVS(7), tái hiện bằng test; chống lặp theo định danh business và vai trò OLD/NEW, không xóa nhầm các chặng khác ngày/giờ.
5. Corpus/profile: giữ bản gốc, chỉ parse/dry-run; thêm YAML và test theo lỗi thực tế; kiểm tra toàn catalog không mơ hồ. Chưa replay/ghi dữ liệu production hoặc sửa dữ liệu đã lưu.
6. Excel: các sheet hãng/profile, tỷ lệ và mẫu số, chi tiết file, lỗi trường, phương pháp. Không tính A000 là resolve aircraft thành công.
7. Mỗi phần hoàn tất: nhật ký docs/change-log/24092026.md, test đúng phạm vi, staged diff chọn lọc và commit local; không push. Chưa đóng gói/deploy vì yêu cầu này không yêu cầu.
