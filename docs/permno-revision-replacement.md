# Revision đột xuất: thay thế OLD/NEW trong PERMNO

## Phạm vi

Áp dụng khi `SchedulePermit.replacesNonScheduledRows()` đúng: revision được đánh số, thực sự ghi NO, không phải revision tạo phép mùa mới. PERMNO tương ứng `T_PERMMASTER_NO`/`T_PERMDETAIL_NO` trong code. PERMSC tương ứng các bảng `_SC`.

Không đổi routing SC/NO hiện có. Phép nhiều ngày đang route SC, phép mùa, hoặc phép đột xuất tìm thấy baseline SC tiếp tục dùng parser/reconcile/gateway SC cũ. Hai phương thức `insertScWithinTransaction` và `updateScWithinTransaction` được so sánh với HEAD trước sửa và giữ nguyên nội dung.

## Luồng mới

1. Parse theo YAML hiện có; xác định nơi lưu thực tế qua baseline.
2. Nếu NO: ánh xạ lại OLD/NEW bằng alias, date/time format và section markers của profile. Chế độ NO bổ sung markers chung, đọc cả ETA/EIBT và không lọc mất NEW vì route của OLD. Bảng route/aircraft OLD không được dùng làm dữ liệu NEW; các route mới cùng sector lấy giá trị xuất hiện sau. Không đổi YAML dùng chung vì điều đó có thể tác động PERMSC.
3. OLD đối chiếu phép gốc: callsign, ngày, sân bay đi/đến, ETD và ETA nếu OLD có ETA. Chỉ một dòng khớp mới hợp lệ. Các field thuộc lịch NEW giữ theo bảng NEW (ngày, day flags, departure/arrival, EOBT/EIBT, callsign); không tự sửa weekday hoặc airport bằng route table.
4. PURPOSE/CRAFT không có thay đổi mới kế thừa từ đúng baseline; PURPOSE mới phải đọc từ NEW/common, không gộp purpose OLD. Aircraft riêng dòng NEW ưu tiên bảng Aircraft chung. Giữ operator của master gốc thay vì suy đoán lại từ tiền tố callsign.
5. VIA lấy bảng route NEW theo sector NEW. Chỉ kế thừa VIA baseline khi cùng sector; đổi sector mà không có VIA mới phải review. VIA mới lỗi/không đọc được không được chuyển thành null rồi tiếp tục xóa OLD.
6. Khóa master/dòng NO; kiểm tra toàn bộ OLD, NEW trùng, baseline thay đổi, master thiếu trước khi xóa. `DELETE ... WHERE PERM_ID=? AND ID=?`, rồi INSERT mọi NEW cùng PERM_ID; master gốc không tạo lại. Thay thế nhánh UPDATE theo callsign cũ.
7. Delete + insert + append reference/content trong cùng transaction. Lỗi rollback cả ba. Retry chỉ no-op khi nội dung revision đã lưu và NEW khớp đầy đủ; không bỏ OLD còn dư chỉ vì NEW đã tồn tại.

## Mapping ví dụ OF-6916 REV1

File cùng hash `e42a02d6` nằm local trong processed ngày 24/09/2026, timestamp filename `170129`. Tên user nêu có timestamp `165620`; nội dung được kiểm thử theo file hash phù hợp.

| Field | OLD | NEW ghi vào NO |
|---|---|---|
| Callsign | TBJ90 | TBJ90 |
| Effective from/to | 26/09/2026 | 26/09/2026 |
| Days | Thứ 7 | Thứ 7 |
| Departure | VHHH | VHHH |
| EOBT | 1800 | 2000 |
| Arrival | LFPB | LFLL |
| EIBT | 0655+1 | 0830+1 (chuẩn lưu `0830+`) |
| VIA | R474-W21-B214 | R474-W21-B214, lấy từ bảng route NEW của VHHH–LFLL |
| CRAFT/PURPOSE | Giá trị baseline | Giữ baseline vì revision không thay đổi |

File này không đổi ngày; test synthetic riêng đã kiểm tra đổi ngày 26→27/09, đổi callsign TBJ90→TBJ91, đổi airport/time/VIA. Baseline trong test H2 là dữ liệu giả có kiểm soát, không khẳng định giá trị CRAFT_ID/PURPOSE thực tế của production.

## Trường hợp dừng an toàn

- Không có bảng OLD hoặc baseline/master gốc không tồn tại.
- OLD không khớp duy nhất, khớp một phần hoặc lặp trong Word; NEW trùng dòng.
- NEW đã tồn tại ngoài tập OLD nhưng chưa chứng minh là retry đã áp dụng.
- Một dòng NO vật lý chứa nhiều ngày và xóa nó sẽ mất ngày ngoài phạm vi.
- Nhiều OLD/NEW đổi cả callsign/sector/date không có định danh duy nhất để kế thừa: không ghép theo vị trí tùy tiện.
- Đổi sector thiếu VIA mới; nguồn VIA không đọc được.
- Lọc LD ngoài Việt Nam sẽ làm mất một phần NEW trong một revision: không xóa toàn bộ OLD cho danh sách NEW đã bị lọc.
- Bản parse đầy đủ cho thấy phải lưu SC trong khi parse cũ thiếu dòng đã chỉ NO: dừng review, không tự chuyển kho.

Những trường hợp này phải được đối chiếu nguồn trước khi chạy lại; code không xóa dữ liệu để “thử”. Chưa sửa dữ liệu đã lưu, chưa replay/deploy.

## Kiểm thử

- `NoRevisionReplacementMappingTest`: file thật, ETA/VIA OLD/NEW, đổi ngày, purpose mới, aircraft mới/riêng dòng, bảng Aircraft NEW rỗng kế thừa baseline, giữ day flags, từ chối ghép mơ hồ, không thay policy SC.
- `NoRevisionReplacementGatewayTest`: H2 Oracle mode; OLD ID bị xóa và NEW ID mới, các chuyến khác còn nguyên; đổi callsign/date/sector, rollback insert lỗi, thiếu master, trùng/mất OLD, NEW tồn tại, retry và thay purpose-only. Có test từ Word thật → reconcile → gateway → đọc lại DB H2 → retry.
- Bộ worker offline có cả regression SC, coordinator, purpose/aircraft/routes. Test Docker/RabbitMQ không chạy trên máy chưa có Docker hoạt động.

Ổ C: hết dung lượng trong lượt test đầu; chỉ đổi TEMP/TMP/java.io.tmpdir cho tiến trình test sang `.tmp/permno-java-temp` trên E:, không xóa dữ liệu người dùng. Khi chạy lại:

```powershell
$temp = Join-Path (Get-Location) '.tmp/permno-java-temp'
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$env:TEMP=$temp; $env:TMP=$temp; $env:MAVEN_OPTS="-Djava.io.tmpdir=$temp"
mvn -pl aerosync-worker -am test '-Dtest=*,!WorkerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Djava.io.tmpdir=$temp" '-Dpermit.of6916.sample=<local Word path>'
mvn -pl aerosync-worker -am package -DskipTests
```
