# Kết quả kiểm tra batch `ngay0307_edited_9021`

Thời điểm kiểm tra: 31/07/2026.

## Phạm vi

- Thư mục có 62 file Word: 53 `.docx` và 9 `.doc`.
- Email trong hệ thống được ghi nhận với sender `tu1412000@gmail.com`, không phải
  `tu141200@gmail.com`.
- Batch mới lúc 16:03 có 53 file `.docx`. Chín file `.doc` được đối chiếu với
  các job cũ.
- Trạng thái email trước khi sửa code: 22 `SUCCESS`, 39 `QUARANTINED`,
  1 `FAILED`.
- Kết quả regression sau khi sửa: 62/62 file nhận đúng format riêng, parse đủ
  dữ liệu lõi và resolve thành công với dữ liệu tham chiếu ATFM thật.

Danh sách chi tiết từng file và job tương ứng nằm tại
`.tmp/ngay0307-email-audit.json`.

## Lỗi project đã sửa

- Ưu tiên VIA ghi trong văn bản; chỉ tra `M_VIA` khi văn bản không có VIA.
- Cho phép VIA trống khi policy của format cho phép, thay vì quarantine vì
  `M_VIA` thiếu hoặc có nhiều dòng.
- Chuẩn hóa dấu `/` ở VIA.
- Bổ sung aircraft aliases `32X`, `73Y/73P/73K`, `B747-400`, `73W`,
  `747-400F/747-8F/777-200F`.
- Chọn rõ bản ghi trùng trong `M_CRAFT_TYPE` cho `A32X` và `73Y`.
- Bổ sung `DRW: YPDN`.
- Giới hạn dữ liệu ghi Oracle theo 4.000 byte UTF-8, không cắt theo số ký tự,
  để tránh `ORA-01461`.

## Lỗi dữ liệu trong file Word chưa thể tự sửa bằng parser

Chuỗi số phép bay dự kiến của 53 file `.docx` là `9021` đến `9073`. Tuy nhiên,
chỉ 17 file thực sự đổi số ở trường **Phép bay số**. Có 36 file vẫn giữ số cũ
hoặc đổi nhầm trường **Tham chiếu phép bay**.

Ví dụ:

- `LD 2353 HAI rev2.docx`: trường hiện tại vẫn là `LD-2353`; `LD-9022` nằm ở
  trường tham chiếu.
- `LD 2517 HVN.docx`: trường hiện tại vẫn là `LD-2517`; `LD-9034` nằm ở
  trường tham chiếu.
- `OF 4840 RVS 1.docx`: trường hiện tại vẫn là `OF-4840`; `OF-9043` nằm ở
  trường tham chiếu.

Danh sách 36 file cần sửa đúng trường **Phép bay số**:

| Số mới | File |
|---:|---|
| 9022 | `LD 2353 HAI rev2.docx` |
| 9026 | `LD 2502 BAV.docx` |
| 9027 | `LD 2503 MYU.docx` |
| 9028 | `LD 2504 SPA C26_06JUL-13JUL_ND.docx` |
| 9029 | `LD 2505  SPA C26_04JUL-12JUL_FrIN-FrOut_VNA921.docx` |
| 9030 | `LD 2507  9MTMJ.docx` |
| 9032 | `LD 2511 TUA CORR.DOCX` |
| 9033 | `LD 2511 TUA.DOCX` |
| 9034 | `LD 2517 HVN.docx` |
| 9038 | `OF 4794_RCR3373.docx` |
| 9039 | `OF 4794_VQBOY.docx` |
| 9040 | `OF 4795_B65AP.docx` |
| 9041 | `OF 4795_VJT861.docx` |
| 9042 | `OF 4796_FDX9080.docx` |
| 9043 | `OF 4840 RVS 1.docx` |
| 9044 | `OF 4868 N111AP.docx` |
| 9045 | `OF 4869 TAY400.docx` |
| 9046 | `OF 4870 ABD4800.docx` |
| 9047 | `OF 4871.docx` |
| 9048 | `OF 4872 FDX.docx` |
| 9049 | `OF 4873 VPBFP.docx` |
| 9050 | `OF 4875.docx` |
| 9051 | `OF 4876.docx` |
| 9052 | `OF 4877.docx` |
| 9053 | `OF 4878.docx` |
| 9054 | `OF 4879.docx` |
| 9055 | `OF 4881.docx` |
| 9057 | `OF 4883.docx` |
| 9059 | `OF 4885.docx` |
| 9064 | `OF 4890.docx` |
| 9065 | `OF 4891.docx` |
| 9066 | `OF 4892 T7BOSS.docx` |
| 9067 | `OF 4893.docx` |
| 9068 | `OF 4894 HSDID.docx` |
| 9069 | `OF 4895_ABD3571.docx` |
| 9071 | `REV1 LD 2467 AZG.docx` |

Hai mươi file bị chặn đúng bởi cơ chế chống ghi đè vì số phép bay hiện tại đã
tồn tại nhưng lịch bay khác:

1. `LD 2353 HAI rev2.docx`
2. `LD 2504 SPA C26_06JUL-13JUL_ND.docx`
3. `LD 2505  SPA C26_04JUL-12JUL_FrIN-FrOut_VNA921.docx`
4. `LD 2507  9MTMJ.docx`
5. `LD 2511 TUA CORR.DOCX`
6. `LD 2511 TUA.DOCX`
7. `LD 2516 HKC327.doc`
8. `LD 2517 HVN.docx`
9. `OF 4794_VQBOY.docx`
10. `OF 4870 ABD4800.docx`
11. `OF 4876.docx`
12. `OF 4877.docx`
13. `OF 4881.docx`
14. `OF 4883.docx`
15. `OF 4885.docx`
16. `OF 4890.docx`
17. `OF 4891.docx`
18. `OF 4892 T7BOSS.docx`
19. `OF 4893.docx`
20. `REV1 LD 2467 AZG.docx`

Không nên đổi parser để lấy số trong trường tham chiếu, vì như vậy sẽ làm sai
ý nghĩa của giấy phép và có thể ghi đè nhầm dữ liệu.

## Các bản ghi đã lưu với số cũ cần kiểm tra thủ công

Tám file `.docx` đã qua kiểm tra kỹ thuật nhưng số phép bay trong tài liệu vẫn
là số cũ, nên ATFM đã lưu dưới số cũ:

| File | Đã lưu | Số dự kiến | masterId | permId |
|---|---|---|---:|---:|
| `OF 4796_FDX9080.docx` | `O/F 04796/S/CHK/2026` | `O/F 09042/S/CHK/2026` | 203255 | 202761 |
| `OF 4868 N111AP.docx` | `O/F 04868/S/CHK/2026` | `O/F 09044/S/CHK/2026` | 203254 | 202760 |
| `OF 4871.docx` | `O/F 04871/S/CHK/2026` | `O/F 09047/S/CHK/2026` | 203253 | 202759 |
| `OF 4872 FDX.docx` | `O/F 04872/S/CHK/2026` | `O/F 09048/S/CHK/2026` | 203252 | 202758 |
| `OF 4873 VPBFP.docx` | `O/F 04873/S/CHK/2026` | `O/F 09049/S/CHK/2026` | 203251 | 202757 |
| `OF 4878.docx` | `O/F 04878/S/CHK/2026` | `O/F 09053/S/CHK/2026` | 203250 | 202756 |
| `OF 4879.docx` | `O/F 04879/S/CHK/2026` | `O/F 09054/S/CHK/2026` | 203249 | 202755 |
| `OF 4895_ABD3571.docx` | `O/F 04895/S/CHK/2026` | `O/F 09069/S/CHK/2026` | 203244 | 202750 |

Không tự động xóa hoặc sửa các bản ghi này vì đây là thay đổi dữ liệu ATFM cần
được người có thẩm quyền xác nhận.

## Chín file `.doc`

Chín file `.doc` trong thư mục hiện giống byte-for-byte với file gốc, tức chưa
được đổi số. Bảy job email cũ có SHA-256 không khớp với file đang có trong thư
mục, nên phải gửi lại đúng bản sau khi sửa mới có thể đối chiếu chính xác.

## Kiểm thử

- Regression 62 file với ATFM thật: 62 thành công, 0 lỗi.
- Unit/service tests không cần Docker: 126 test, 0 failure, 0 error,
  8 skipped.
- Test tích hợp RabbitMQ duy nhất chưa chạy vì Docker Desktop trên máy không
  hoạt động; đây không phải lỗi code.
