# Audit hồ sơ lỗi định dạng Word — 14/09/2026

- Phạm vi đọc: `D:/vatm-storage/error`, `D:/vatm-storage/error-format` và `D:/vatm-storage/quarantine`.
- Đã quét 10.301 file Word, gồm cả `.doc`, `.docx` và tên file có hậu tố hash; có 9.637 nội dung khác nhau theo SHA-256.
- Không sửa, di chuyển, replay hoặc ghi dữ liệu vào các thư mục nguồn.

## YAML bổ sung

| ICAO | YAML | Dạng tài liệu |
|---|---|---|
| MPH | `mph-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa, cấp mới |
| SBI | `sbi-caav-seasonal-overflight-issued.yaml` | Phép quá cảnh mùa, cấp mới |

Hai profile kế thừa `airline-generic-seasonal-overflight-issued`, có nhận diện ICAO/tên hãng, số phép `OF-.../A/W/...VN`, lịch bay và loại tài liệu đã cấp. Không tạo YAML cho tài liệu application thiếu số phép bay.

## Xử lý dữ liệu Word

Một hồ sơ SBI có ô `Day(s) of services` trống ở dòng thứ hai dù dòng lịch tiếp tục cùng bảng. Parser nay kế thừa ngày khai thác từ dòng trước khi ô bị trống; nếu khoảng hiệu lực chỉ có một ngày, parser vẫn tính ngày trong tuần từ ngày đó.

## Kiểm chứng

```powershell
mvn -f pom.xml test -pl aerosync-worker -am `
  '-Dtest=ErrorFormatMissingCarrierProfilesTest,WordPermitFormatDetectorTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dpermit.missing-carriers.dir=D:/vatm-storage/error-format'
```

Kết quả: 16 test đạt, 0 lỗi, 0 bỏ qua.

Các mã ICAO còn xuất hiện trong hồ sơ thiếu profile không được tự động thêm chỉ từ việc thấy mã hãng. Nhiều hồ sơ là application, revision có cấu trúc khác, tài liệu thiếu số phép, hoặc hồ sơ lỗi bảng; cần mẫu phép đã cấp đầy đủ trước khi tạo YAML riêng.
