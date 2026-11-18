# Kế hoạch - 18112026 - Đọc đường bay nhiều chặng và tàu bay theo tuyến HVN/VJC

## Mục tiêu

Đọc an toàn đường hàng không và loại tàu bay trong sáu tài liệu phép bay HVN/VJC được cung cấp. Hỗ trợ nội dung viết trực tiếp như `SGN-HYD-SGN: R468` và quy tắc loại tàu bay áp dụng riêng cho từng tuyến, không ghép thông tin vào các dòng lịch bay chỉ dựa trên thứ tự xuất hiện.

## Phạm vi

- Sáu tài liệu được cung cấp: LD 4281, LD 4282, LD 4289, LD 4300, LD 4302 và LD 4301.
- Các hồ sơ YAML của hãng và những thay đổi tối thiểu cần thiết cho bộ phân tích dùng chung để đọc chuỗi tuyến trực tiếp và quy tắc tàu bay theo tuyến.
- Kiểm thử hồi quy bằng sáu tài liệu và dữ liệu tổng hợp cho tuyến nhiều chặng, chiều ngược, quy tắc theo tuyến và quy tắc dự phòng.
- Không phát lại email, không ghi vào Oracle/ATFM, không triển khai.

## Phát hiện ban đầu

- **LD 4301 HVN:** `SGN-HYD-SGN: R468; PQC-HYD-PQC: R334`. Lịch có các chặng SGN→HYD, HYD→SGN, PQC→HYD và HYD→PQC. Cần tách mỗi chuỗi khứ hồi thành các cặp sân bay có hướng và giữ đúng airway tương ứng. Loại tàu bay `321/320` áp dụng chung theo nội dung phép.
- **LD 4302 HVN:** Các tuyến có hướng được ghi trực tiếp: `HAN-VNS: W21 B214; VNS-HAN: B214 W21`. Cần giữ danh sách airway riêng theo từng chiều trong quá trình chuẩn hóa.
- **LD 4300 HVN:** Loại tàu bay riêng cho tuyến `HAN-SGN-HAN: 787/350/321/320`; quy tắc dự phòng cho các đường bay khác là `321/320`. Lịch có nhiều tuyến khác, nên phải so khớp theo cặp sân bay, không theo thứ tự dòng.
- **LD 4282 VJC:** Bảng lịch có các dòng tiêu đề tuyến xen kẽ các chuyến bay. Một bảng riêng ánh xạ chặng với airway; một bảng khác liệt kê các loại tàu bay áp dụng chung.
- **LD 4281 VJC:** Phép sửa đổi có các bảng OLD/NEW và tiêu đề tuyến; không có bảng tàu bay riêng. Không áp dụng thông tin NEW cho OLD hoặc tự suy ra loại tàu bay mới.
- **LD 4289 HVN:** Ghi chú của phép sửa đổi cho biết đường bay/loại tàu bay được kế thừa từ phép mùa LD-69/A/W/2026VN, đồng thời một số nhóm lịch nêu loại riêng. Cần giữ cơ chế baseline/revision hiện có; không biến ghi chú tham chiếu thành quy tắc tĩnh áp dụng cho mọi chuyến.
- Parser hỗ trợ `staticAirways` trong YAML và bảng route. Hiện việc suy luận chuỗi `A-B-C` nằm trong nhánh xử lý bảng không có tiêu đề; các bảng tàu bay phụ hiện gộp các loại tàu bay thành danh sách chung, chưa biểu diễn được ánh xạ `tuyến → loại tàu bay`.

## Các giả định đề xuất

- Chuỗi tuyến `A-B-C` biểu thị các chặng có hướng `A→B` và `B→C`; chuỗi `A-B-A` bao gồm cả hai chiều. Airway gắn với chuỗi áp dụng cho từng chặng, trừ khi tài liệu có khai báo riêng cụ thể hơn cho chặng đó.
- Quy tắc tàu bay khớp chính xác với tuyến được ưu tiên hơn quy tắc dự phòng tường minh “các đường bay khác”. Danh sách tàu bay chung chỉ được dùng khi hồ sơ YAML khai báo nó là phương án dự phòng. Không ghép thông tin dựa trên thứ tự dòng lịch.
- Với phép sửa đổi, chỉ sử dụng bằng chứng route/aircraft thuộc đúng phần NEW. Nếu NEW không nêu một trường thì dùng cơ chế kế thừa baseline hiện có; không suy diễn từ OLD.

## Các câu hỏi cần người dùng lựa chọn

### 1. Thứ tự ưu tiên áp dụng quy tắc tàu bay

- [x] A - **Khuyến nghị:** Ưu tiên tuyến cụ thể, tiếp theo là quy tắc dự phòng tường minh “các đường bay khác”, sau đó mới dùng danh sách chung của phép. Ưu điểm: giữ được ngoại lệ tuyến trong LD 4300. Nhược điểm: cần phân tích và so khớp dữ liệu theo tuyến.
- [ ] B - Chỉ dùng một danh sách loại tàu bay chung cho toàn phép. Ưu điểm: đơn giản hơn. Nhược điểm: mất phân biệt giữa HAN-SGN-HAN và các tuyến khác trong LD 4300.
- [ ] C - Chỉ dùng quy tắc theo tuyến, không có dự phòng. Ưu điểm: tránh gán loại tàu bay quá rộng. Nhược điểm: các chặng không liệt kê sẽ thiếu loại tàu bay dù văn bản có nêu “các đường bay khác”.
- Phương án khác do người dùng nhập: ____________________

### 2. Cách biểu diễn mẫu trong YAML

- [x] A - **Khuyến nghị:** Bổ sung khai báo có cấu trúc trong hồ sơ HVN cho chuỗi route/airway trực tiếp và quy tắc tàu bay theo tuyến; xử lý bằng logic dùng chung của parser. Ưu điểm: quy tắc định dạng thể hiện rõ trong YAML và có thể kiểm thử trên nhiều tài liệu. Nhược điểm: cần mở rộng schema hồ sơ và parser.
- [ ] B - Viết riêng logic HVN trong Java, YAML chỉ dùng để nhận diện tài liệu. Ưu điểm: ít phải thay đổi schema YAML. Nhược điểm: quy tắc định dạng bị ẩn trong code, khó rà soát và mở rộng.
- [ ] C - Khai báo thủ công từng tuyến quan sát được của từng phép bằng YAML `staticAirways`. Ưu điểm: không cần sửa parser. Nhược điểm: không áp dụng được cho phép mới trong tương lai và có nguy cơ không khớp lịch bay.
- Phương án khác do người dùng nhập: ____________________

## Phạm vi triển khai mặc định

1. Mở rộng định nghĩa hồ sơ cho quy tắc route trực tiếp và tàu bay theo tuyến; kiểm tra các nhóm bắt buộc trong regex khi tải hồ sơ.
2. Tách chuỗi tuyến thành các chặng có hướng, chuẩn hóa airway bằng logic hiện có và so khớp riêng từng dòng lịch.
3. Chỉ dùng “các đường bay khác” làm quy tắc dự phòng khi hồ sơ khai báo tường minh.
4. Phân tích danh sách loại tàu bay như `321/320` và `787/350/321/320` bằng `AircraftTypeNotation`; tuyệt đối không suy ra tàu bay từ callsign, tên file hoặc thứ tự lịch.
5. Thêm kiểm thử hồi quy cho cả sáu DOCX. Xác nhận LD 4301 ánh xạ R468 cho SGN↔HYD và R334 cho PQC↔HYD; LD 4302 giữ đúng danh sách airway theo chiều; LD 4300 phân biệt HAN↔SGN với tuyến dự phòng; VJC giữ nguyên cách xử lý bảng route và revision.
6. Chạy các kiểm thử worker trọng tâm và kiểm thử hồi quy tập hồ sơ; ghi kết quả thực tế vào nhật ký thay đổi.
7. Chỉ dùng tài liệu làm dữ liệu kiểm thử chỉ đọc. Không import hoặc ghi vào ATFM thật.

## Lựa chọn đã xác nhận

- Câu hỏi 1: A - ưu tiên loại tàu bay theo tuyến cụ thể, sau đó fallback “các đường bay khác”, rồi nguồn chung hiện có.
- Câu hỏi 2: A - khai báo pattern có cấu trúc trong YAML hãng và xử lý bằng parser dùng chung.
- Trạng thái: Các lựa chọn đã được xác nhận; parser, YAML và regression tests đã được cập nhật. Kết quả kiểm thử được ghi trong nhật ký thay đổi.
