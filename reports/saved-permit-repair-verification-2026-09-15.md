# Kết quả xác minh parser & repair preview — 15/09/2026 (bổ sung)

Tệp này bổ sung cho `saved-permit-repair-preview-2026-09-15.md` sau khi chạy kiểm chứng thật.

## Phạm vi kiểm chứng

- Repo: `fix/email-to-atfm-flow-integrity`, parser/profile sửa sáng 15/09.
- Chạy `WordPermitCorpusRegressionTest` với manifest 7 file Word gốc trong
  `D:\vatm-storage\processed\2026\09\15` (và `2026\09\11` cho OF-6517), bật
  `-Dpermit.corpus.resolve-aircraft=true` (kết nối ATFM 172.29.187.90 read-path).
- Kịch bản chạy: `scripts/run-targeted-corpus-20260915.sh`;
  manifest: `reports/targeted-repair-run-2026-09-15/manifest.txt`;
  log + văn bản parse: `reports/targeted-repair-run-2026-09-15/`.
- Test hồi quy mới: `SavedPermitRepair20260915Test` (6/6 pass), chạy với
  `-Dpermit.repair.sample.directory=D:\vatm-storage\processed`.
- Đối chiếu SELECT trực tiếp Oracle: `db-check2.sql` / `db-final-check.sql` (read-only).

## Kết quả 7 hồ sơ mục tiêu (parser hiện tại)

| Hồ sơ | Profile | Kết quả parse | Kết luận |
|---|---|---|---|
| LD-3331 VASCO | vfc-vietnamese-landing-change | 50 flight, import-ready | Đủ 50 dòng; route `W15 hoặc W2` chuẩn hóa thành `W15/W2` (2 lựa chọn, không ghép giả). VIA null cũ: 964203–964231 (11 dòng) |
| OF-6605 FQA | fqa-english-overflight-issued | detect đúng, ETA 1115, ngày cấp 14SEP2026, operator FQA | Còn chặn ở bước aircraft: `B737-85P` không có trong `M_CRAFT_TYPE` (đã xác nhận DB). Cần duyệt tra cứu aircraft trước khi cập nhật Oracle |
| OF-6517 KMI | kmi-english-overflight-issued | 2 flight, import-ready | Phát hiện mới: bản saved trong DB (detail 964094/964095) bị ETA null vì profile generic `includeEta: false`. Đã bật `includeEta: true` cho KMI; cần cập nhật 2 dòng ETA (1800/2235) khi sửa |
| LD-3185 REV2 HAI | hai-english-landing-revision | purpose BUS đúng, ngày 15SEP, LFPB→VVNB | Chặn ở bước route: `M_AIRPORT_ROUTE` chỉ có chiều VVNB→LFPB (R474-R471, ID 14071), chưa có chiều LFPB→VVNB. Cần bổ sung route hai chiều (hoặc quy ước) trong ATFM, không phải lỗi parser |
| LD-32/B/S/2026 REV15 SPQ | spa017-vietnamese-seasonal-landing-revision | NEW=5, OLD=5 | Tách bảng 2.1/2.2 đúng; không còn OLD=10 |
| OF-6431 REV1 VTAKV | caav-generic-overflight-issued-na-operator | 1 flight, import-ready | ICAO ghi WSSS→đổi WSSL đúng; operator N/A giữ PRV |
| OF-6516 REV1 VTASR | caav-generic-overflight-issued-na-operator | 1 flight, import-ready | WSSS→WSSL đúng, giữ 0900–1225, GL7T, VT-ASR |

Hai lỗi còn lại trong corpus run **không phải hồi quy parser**:

1. `BR-AIRCRAFT-NOT-FOUND B737-85P` — FQA: thiếu dữ liệu tham chiếu M_CRAFT_TYPE
   (đúng mục cần duyệt #4 của preview cũ). corpus harness chạy strict mode;
   pipeline thật dùng `resolveAllowingMissingForNonSeasonal` nên NO vẫn ghi được
   kèm cảnh báo A000.
2. `BR-ROUTE-NOT-FOUND LFPB -> VVNB` — HAI: thiếu route M_AIRPORT_ROUTE chiều
   thuận (chiều nghịch VVNB→LFPB đã có). Đây là dữ liệu ATFM cần bổ sung;
   hành vi reject route không có trong từ điển là đúng thiết kế hiện tại.

## Thay đổi code trong lượt này

- `kmi-english-overflight-issued.yaml`: thêm `schedule.includeEta: true`
  (giữ ETA cho KMI; gốc của lỗi ETA null trên OF-6517 đã saved).
- `SavedPermitRepair20260915Test.java`: 6 test hồi quy — resolver tiếng Việt
  `W15 hoặc W2 → W15/W2`, FQA (operator/ETA/ngày), HAI BUS + normalize
  LBG→LFPB (theo airport-codes.yaml, khớp DB), SPQ 5/5, OF-6517 KMI + ETA.
  Vẫn chạy được với file Word gốc qua `-Dpermit.repair.sample.directory`.
- Scripts hỗ trợ: `scripts/run-targeted-corpus-20260915.sh`,
  `scripts/run-sql-read-only.sh` (sqlplus -L, file SELECT được kiểm soát).

## Việc còn lại trước khi cập nhật Oracle

1. Duyệt tra cứu `B737-85P` (FQA) — bổ sung alias hoặc M_CRAFT_TYPE, rồi quyết
   định craft_id thay A000 (detail 534254).
2. Duyệt route LFPB→VVNB (HAI, detail 532941) — thêm M_AIRPORT_ROUTE hoặc cho
   phép kế thừa route từ baseline; không tự đảo R474-R471.
3. LD-3331: replay/upsert 50 detail với VIA mới (`W15/W2` cho 11 dòng null cũ)
   trên target SC `203921/203427`; giữ SC theo nghiệp vụ.
4. OF-6517: cập nhật ETA 1800/2235 cho 964094/964095 (hoặc replay sau khi
   deploy KMI includeEta).
5. SPQ REV15: xóa 5 detail hủy thừa (964247–964251), giữ 964242–964246 (hủy
   gốc) + 964252–964256 (mới) theo preview cũ.
6. OF-6605: xác nhận xử lý xung đột ICAO QFA vs tên QUIKJET/IATA QO trong
   tài liệu nguồn (operator hiện đã đúng FQA theo callsign+profile).
7. Lưu ý ngoài phạm vi 7 hồ sơ: hai lỗi tồn tại từ trước, không liên quan đợt
   sửa này — `OpenApiDocumentationTest` (runtimeConfigService init fail, có vẻ
   phụ thuộc DB local) và `AlertServiceTest` (từ khóa English lọt vào chuỗi
   Việt ngữ đã dịch). Cần xử lý riêng.
