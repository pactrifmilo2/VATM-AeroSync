# Hướng dẫn tạo và sửa format giấy phép bay Word

## 1. Nguyên tắc an toàn

Mỗi YAML mô tả một hãng và một biến thể tài liệu có cấu trúc thực sự khác nhau, ví dụ cấp mới, sửa đổi, hạ cánh hoặc bay qua. Không tạo một regex quá rộng để “ăn” tài liệu của hãng khác.

Luôn dùng một file Word có đầy đủ thông tin làm mẫu gốc. Các file thiếu trường chỉ dùng để bổ sung alias hoặc biến thể regex, không dùng làm chuẩn duy nhất.

Một tài liệu không có số phép bay (`LD`, `OF`, `O/F` hoặc `QLB`) không phải giấy phép bay. Không tạo số giả và không cho phép insert; tài liệu đó phải được giữ ở luồng lỗi/quarantine để người dùng kiểm tra.

## 2. Tạo profile mới

1. Chạy regression với thư mục tài liệu để sinh báo cáo văn bản:

   ```powershell
   .\aerosync-worker\mvnw.cmd -f .\aerosync-worker\pom.xml `
     -Dtest=WordPermitCorpusRegressionTest `
     "-Dpermit.corpus.dir=E:\ATFM-AEROSYNC\VATM-AeroSync\NGAY 31JUL" `
     "-Dpermit.corpus.report.dir=E:\ATFM-AEROSYNC\VATM-AeroSync\.tmp\permit-reports" `
     test
   ```

2. Mở báo cáo `.txt` tương ứng, xác định tên hãng, IATA, ICAO, cách viết số phép bay, ngày cấp, tiêu đề bảng lịch bay, bảng đường bay và bảng tàu bay.
3. Chọn file chung gần nhất để kế thừa bằng `extends`. Chỉ ghi đè phần khác biệt.
4. Đặt tên theo dạng `<icao-hoặc-prv>-<ngôn-ngữ>-<loại>-<issued/revision>.yaml`.
5. Chạy kiểm thử profile và toàn bộ corpus. Không chỉ thử đúng một file mẫu.

## 3. Các phần bắt buộc trong YAML

### `id`, `extends`, `priority`

- `id` phải duy nhất trong toàn bộ thư mục.
- `extends` kế thừa format chung; các object được merge sâu, còn list được thay toàn bộ.
- Profile hãng phải có `priority` lớn hơn profile chung. Profile càng đặc thù thì priority càng cao.

### `detectionPatterns`

Tất cả pattern trong danh sách đều phải khớp. Nên có tối thiểu:

- loại/số phép bay;
- IATA, ICAO hoặc tên hãng;
- dấu hiệu issued/revision;
- một tiêu đề bảng đặc trưng.

Ví dụ:

```yaml
detectionPatterns:
  - '(?iu)\bLD\s*-\s*\d+'
  - '(?iu)(?:ICAO\s*CODE\s*:\s*ABC\b|IATA\s*CODE\s*:\s*XY\b|AIRLINE NAME)'
  - '(?iu)(?:/|-)?REV\d+\b'
```

Không chỉ dùng tên file để nhận diện vì tên file có thể do người gửi tự đặt.

### `permit`

Regex phải bắt được năm bốn chữ số trong group `year`. `normalizedTemplate` cũng phải chứa `{year}` để việc tìm bản ghi ATFM luôn so sánh cả số phép bay và năm.

```yaml
permit:
  pattern: '(?iu)\bLD-(?<number>\d{1,5})/(?<month>\d{1,2})/(?<year>20\d{2})VN\b'
  numberGroup: number
  sourceTemplate: 'LD-{number}/{month}/{year}VN'
  normalizedTemplate: 'LD {number}/S/CHK/{year}'
  zeroPadGroups:
    number: 5
```

Không bỏ group `year`, không dùng ngày nhận email làm năm của số phép bay.

### `permitDate`

Khai báo mọi cách viết ngày đã xuất hiện trong tài liệu:

```yaml
permitDate:
  source: PARAGRAPH
  pattern: '(?iu)(?<date>\d{1,2}/\d{1,2}/20\d{2}|\d{1,2}[A-Z]{3}\d{2})'
  group: date
  formats: [d/M/uuuu, dMMMyy]
  locale: en
```

Nếu không tìm thấy ngày, parser hiện dùng `LocalDate.now(clock)`. Không đặt ngày `01-01-1970` và không tự tạo ngày từ số phép bay.

### `operator`

Ưu tiên ICAO ghi trực tiếp trong Word. Nếu Word chỉ có IATA, resolver lấy các dòng cùng IATA trong `ATFM.M_OPER`, so tên hãng với `OPER_NAME`, rồi chọn ICAO phù hợp. Không tìm được kết quả duy nhất thì dùng `PRV`.

Với profile của một hãng đã xác định chắc chắn có thể dùng:

```yaml
operator:
  fixedValue: ABC
```

Mã chuyến bay có prefix IATA hai ký tự sẽ được đổi sang ICAO ba ký tự khi `inferIataPrefix: true`. Prefix ICAO ba ký tự được giữ nguyên. Nếu hãng được giải quyết thành `PRV`, ví dụ `XY123` trở thành `PRV123`.

### `schedule`

Mỗi semantic field có thể có nhiều tiêu đề:

```yaml
schedule:
  columns:
    flightNumber: [Flight number, Flt number, Số hiệu chuyến bay]
    effectiveFrom: [Effective from, Hiệu lực từ]
    effectiveTo: [Effective to, Hiệu lực đến]
    serviceDays: [Days of services, Ngày trong tuần]
    fromAirport: [Departure Airport, Sân bay cất cánh]
    etd: [ETD, Giờ dự kiến đi]
    toAirport: [Arrival Airport, Sân bay hạ cánh]
    eta: [ETA, Giờ dự kiến đến]
    aircraftType: [Aircraft Type, Loại tàu bay]
  requiredColumns: [flightNumber, effectiveFrom, effectiveTo, serviceDays, fromAirport, etd, toAirport]
  dateFormats: [dMMMyy, d-MMM-yy, d/M/uuuu]
  timeFormats: [HHmm, H:mm, HH:mm]
  includeEta: true
  inferIataPrefix: true
```

Quy tắc chọn bảng:

- Có lịch gốc và lịch sửa đổi: cấu hình `preferredTableContextPatterns` khớp “new/revised/sửa đổi/thay đổi”; parser bỏ lịch gốc khỏi danh sách insert và giữ lịch gốc riêng để đối chiếu revision.
- Có nhiều bảng cùng nhóm, ví dụ quốc tế và quốc nội: tất cả bảng khớp nhóm được ghép lại.
- Bảng bổ sung cần insert cùng bảng chính: cấu hình `supplementalTableContextPatterns` cho tiêu đề “additional/supplemental/bổ sung”.
- Chỉ có một bảng: bảng đó được chọn bình thường.
- `lastMatchingTable: true` chỉ nên dùng khi tài liệu luôn đặt bảng mới sau bảng gốc và không có nhiều bảng con cần ghép.

Không dùng `excludeColumns` để loại bảng chỉ vì một mẫu có thêm cột; chỉ dùng khi cột đó chứng minh chắc chắn bảng là lịch gốc hoặc loại bảng khác.

### `route` và `aircraft`

`route.columns` ánh xạ bảng chặng bay/đường hàng không. `filterSchedule: true` chỉ giữ chuyến có sector tương ứng; dùng `false` nếu bảng airway là tuyến tham khảo chung. `fallbackToFirst` chỉ bật khi nghiệp vụ cho phép dùng tuyến đầu tiên cho các chuyến không khớp.

Nếu lịch bay không có loại tàu bay, khai báo bảng tàu bay phụ bằng `auxiliaryColumns`. Chỉ dùng `defaultType` khi mẫu chính thức quy định một loại cố định; không dùng để che lỗi thiếu dữ liệu.

### `validation`

```yaml
validation:
  allowIataAirports: false
  reviewOnly: false
```

Khi `allowIataAirports: false`, mã IATA sân bay phải có trong `permit-reference/airport-codes.yaml` để đổi sang ICAO trước khi insert. Mapping mới phải được đối chiếu với `ATFM.M_AERO`, không tự đoán.

`reviewOnly: true` dành cho format chưa đủ chắc chắn và sẽ không được coi là sẵn sàng insert tự động.

## 4. Revision, lịch bổ sung và dữ liệu ATFM

- Tra cứu phép bay dùng cả `PERMNBR_ID` chuẩn hóa và bốn chữ số năm.
- Revision không xóa detail gốc. Hệ thống đối chiếu lịch gốc trong Word với bản ghi ATFM và append các dòng lịch sửa đổi/bổ sung.
- Không dùng riêng số thứ tự phép bay vì số này được sử dụng lại ở năm sau.
- Nếu lịch gốc trong Word không khớp ATFM, dừng/quarantine để tránh cập nhật nhầm giấy phép.

## 5. Checklist trước khi đưa vào chạy

- Profile chỉ nhận đúng hãng và đúng loại tài liệu.
- Số phép bay chuẩn hóa có năm.
- Ngày cấp đúng; tài liệu thiếu ngày dùng day-now.
- Đúng số lượng chuyến ở tất cả bảng quốc tế, quốc nội, bổ sung và sửa đổi.
- Khi có lịch mới, danh sách insert không chứa lịch gốc.
- Flight number đã chuyển IATA sang ICAO/PRV đúng quy tắc.
- Sân bay đã đổi sang ICAO và tồn tại trong `M_AERO`.
- Purpose, aircraft, registration, airway, billing address và reference không bị lấy từ nhầm bảng.
- Regression toàn corpus không có lỗi; tài liệu không phải giấy phép được ghi rõ là non-permit, không tạo dữ liệu giả.

## 6. Lệnh kiểm thử nhanh

```powershell
# Kiểm tra catalog, parser và các rule liên quan
.\aerosync-worker\mvnw.cmd -f .\aerosync-worker\pom.xml test

# Kiểm tra toàn bộ Word thực tế
.\aerosync-worker\mvnw.cmd -f .\aerosync-worker\pom.xml `
  -Dtest=WordPermitCorpusRegressionTest `
  "-Dpermit.corpus.dir=E:\ATFM-AEROSYNC\VATM-AeroSync\NGAY 31JUL" `
  test
```

Kết quả cần có `WORD PERMIT CORPUS FAILURES (0)`. Danh sách `WORD NON-PERMIT DOCUMENTS` được phép có file, nhưng các file đó phải thực sự không chứa số giấy phép.
