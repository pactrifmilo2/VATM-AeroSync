# Bổ sung YAML cho các hãng trong error-format — 09/09/2026

Project: `E:/ATFM-AEROSYNC/VATM-AeroSync`.
Nguồn hồ sơ: `D:/vatm-storage/error-format`.
YAML được nạp từ `aerosync-worker/src/main/resources/permit-formats`, không phải thư mục lưu hồ sơ lỗi.

## Các mẫu đã thêm

| Hãng (ICAO) | File YAML | Dạng hồ sơ |
|---|---|---|
| AHK | `ahk-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| AIC | `aic-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| ALK | `alk-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| CEB | `ceb-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| CSZ | `csz-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| GFA | `gfa-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| ICV | `icv-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa |
| JJA | `jja-caav-seasonal-overflight-issued.yaml` | Phép mùa; nhận cả dấu gạch ngang Unicode trong số phép |
| FFM | `ffm-caav-inline-overflight-issued.yaml` | Lịch bay trong đoạn văn |
| LAO | `lao-caav-inline-overflight-issued.yaml` | Lịch bay trong đoạn văn; ngày cấp dạng chữ hoặc số |
| UAL | `ual-caav-headerless-overflight-issued.yaml` | Bảng lịch bay không có tiêu đề; khai báo vị trí 9 cột |
| TNU | `tnu-caav-inline-overflight-revision.yaml` | Revision có Original/New trong đoạn văn; review-only |

Các mẫu kế thừa cấu trúc có sẵn, giới hạn nhận diện bằng ICAO và số phép tương ứng, ưu tiên 40 để đứng trên fallback nhưng dưới các mẫu chuyên biệt có độ ưu tiên cao hơn.

`DocxSchedulePermitParser` được bổ sung tách lịch dạng đoạn văn theo `originalTableContextPatterns` và `preferredTableContextPatterns` khi không tìm thấy bảng lịch. Với TNU, trước đây cả 4 dòng Original/New được đọc thành lịch mới; hiện tại 2 dòng Original vào `originalFlights`, 2 dòng New vào `flights`. Mẫu TNU giữ `reviewOnly: true` từ mẫu revision gốc, không tự cho phép ghi ATFM. Không gán mặc định GLEX cho các mẫu mới FFM/LAO/UAL/TNU khi nguồn thiếu loại tàu bay.

## Kiểm chứng

- `ErrorFormatMissingCarrierProfilesTest` và `WordPermitFormatDetectorTest`: **16 test đạt, không lỗi, không bỏ qua** khi truyền thư mục mẫu thực tế.
- Trong đó một test đọc trực tiếp **18 hồ sơ Word đại diện cho đủ 12 hãng**, kiểm tra profile, operator và số chuyến. Với TNU còn kiểm tra danh sách chuyến mới và 2 chuyến gốc.
- Test độc lập kiểm tra UAL giữ dòng đầu của bảng và ETA; TNU không trộn Original/New; mẫu phép mùa không nhận đơn xin phép chưa có số phép.
- Kiểm tra corpus **977 file có đuôi .doc/.docx**: trước thay đổi 528 đọc được, 296 lỗi, 153 bị bộ lọc corpus bỏ qua; sau thay đổi 529 đọc được, 295 lỗi, 153 bị bỏ qua. Đối chiếu tên file xác nhận không mất hồ sơ nào trong 528 hồ sơ trước đó. Hồ sơ đọc thêm là `sontt_20260812_182726_email_001_OF-786.02.2024VN_UAL_12FEB24.docx`.
- Corpus vẫn báo FAILURE vì còn 295 lỗi; không coi đây là toàn bộ bộ kiểm thử đã xanh. Nhiều hồ sơ đã đọc được trước đó bằng fallback nay được chọn đúng mẫu riêng của hãng.
- Corpus bỏ qua JJA `OF-91_A_S_2024VN_JJA.doc` vì bộ lọc số phép chưa nhận dấu `–`. Hồ sơ này được kiểm tra riêng trong 18 mẫu ở trên: đọc thành công **12 chuyến** bằng YAML JJA mới.

Chạy lại kiểm thử có mẫu thực tế từ thư mục gốc project:

```powershell
.\aerosync-worker\mvnw.cmd -f pom.xml test -pl aerosync-worker -am `
  "-Dtest=ErrorFormatMissingCarrierProfilesTest,WordPermitFormatDetectorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dpermit.missing-carriers.dir=D:/vatm-storage/error-format"
```

## Giới hạn và hồ sơ còn lại

- AJX, CSH, CKK, AJV có tài liệu yêu cầu/application trong tập đã rà soát nhưng chưa có mẫu giấy phép được cấp phù hợp để tạo YAML nhập liệu. Không dùng đơn yêu cầu để giả lập phép đã cấp.
- 295 lỗi còn lại gồm trường hợp không có profile, bảng thiếu trường, biến thể phép/sửa đổi và tài liệu khác. Bổ sung 12 mẫu này không đồng nghĩa xử lý hết thư mục lỗi.
- Có thêm **27 file có hậu tố hash sau .doc/.docx**. Bộ đọc Word và corpus hiện không hỗ trợ trực tiếp đuôi này, nên chúng không nằm trong thống kê 977 file. Đã xem nhận diện trong các file DOCX dạng này; các mã nhận được là LTX, PFA, LAO, đều có YAML. Chưa chạy kiểm thử parse toàn bộ nhóm hậu tố hash.
- Không thay đổi hồ sơ nguồn, không replay email, không ghi Oracle/ATFM, không đóng gói hoặc khởi động lại dịch vụ. Muốn áp dụng YAML cho dịch vụ đang chạy cần build và triển khai worker.
