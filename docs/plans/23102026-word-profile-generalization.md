# Phieu plan 23102026 - Chuan hoa doc permit Word theo cau truc bang dung chung

## Muc tieu

Mo rong parser va cac YAML profile de doc nhat quan cac bang thong tin hang, lich bay, muc dich, tau bay va duong bay cua permit Word. Muc tieu do duoc la trich xuat dung hoac dua vao review co ly do ro rang tren corpus da chon; khong doan du lieu mo ho de ghi sai vao ATFM.

File KAL `OF-105/B/W/2026VN` la mau dai dien: bang lich bay, tau bay va airway tuan theo cau truc CAAV pho bien, nhung so permit mua, header `Sectors` va `AircraftType` khong khop profile hien tai.

## Pham vi du kien

- Audit read-only cac file `.doc`/`.docx` trong `D:\vatm-storage` theo pham vi duoc chon; lap manifest hash, duong dan, profile da chon, ket qua parse va ly do loi.
- Chuan hoa semantic table model trong parser: header alias, normalizer cho heading/cell, table context, bang nhieu dong, route grouped sectors, aircraft type/registration va purpose.
- Mo rong profile goc theo ho permit va cac profile hang chi giu guard hang, permit identity, bat thuong rieng va priority.
- Ho tro cac so permit seasonal/non-seasonal, version/season, separator va cach bieu dien aircraft/airway quan sat duoc trong corpus.
- Them regression tests per profile family va corpus fixtures. Khong tu dong retry, ghi ATFM hay di chuyen file trong `D:\vatm-storage`.

## Rang buoc ky thuat va an toan

- Catalog hien tai co 359 YAML va ho tro `extends`; object duoc deep-merge nhung list bi ghi de, do do khong duoc rut gon profile hang loat ma khong co test bao ve.
- Khi nhieu profile cung co the match, detector phai bao ambiguous thay vi chon ngau nhien.
- Header/route/aircraft khong du bang chung de map duy nhat phai tao ket qua review/quarantine, khong tu dien gia tri mac dinh de ghi ATFM.
- Tieu chi `99%` duoc do tren corpus da chon va tach rieng: profile selection, field extraction, business validation, va ATFM-reference validation. Khong the bao dam cho moi Word chua tung quan sat.

## Cau hoi can xac nhan

### 1. Corpus dung de do muc phu

**A. Dung toan bo corpus lich su, bo qua incoming dang hoat dong (khuyen nghi)**

Scan read-only `processed`, `error`, `error-format` va `quarantine`; tao manifest hash de ket qua lap lai duoc. `incoming` khong duoc doc de tranh file dang copy/doi.

Uu: mau da dang va an toan van hanh. Nhuoc: audit dau tien co the mat thoi gian.

**B. Bao gom ca incoming tai thoi diem chup snapshot**

Scan them `incoming` sau khi chup danh sach/hash, chi doc cac file on dinh.

Uu: bao phu file moi nhat. Nhuoc: can co quy tac bo qua file dang ghi va ket qua co the thay doi.

**C. Chi dung error va quarantine**

Uu tien sua cac file dang loi.

Uu: nhanh. Nhuoc: de bo sot cac bien the dang xu ly thanh cong nhung trich xuat sai field.

**Phuong an khac do user nhap:**

### 2. Chinh sach khi cau truc bang mo ho

**A. Tu dong chi khi high-confidence, con lai REVIEW (khuyen nghi)**

Can profile duy nhat va cac cot bat buoc map duy nhat; route/aircraft co nhieu cach hieu thi bao review co chi tiet cot/dong.

Uu: bao ve du lieu ATFM. Nhuoc: van con file can xu ly thu cong khi mau qua la.

**B. Generic fallback uu tien ty le tu dong**

Cho phep suy luan tu noi dung bang ngay ca khi header/context yeu.

Uu: giam quarantine. Nhuoc: nguy co gan sai route, aircraft hoac profile.

**C. Chi chap nhan profile hang da xac nhan**

Khong them generic fallback; them profile rieng cho tung hang.

Uu: thay doi de kiem soat. Nhuoc: khong dat muc tieu tai su dung cau truc chung.

**Phuong an khac do user nhap:**

### 3. Cach rollout thay doi YAML/parser

**A. Nen dung chung + migrate theo lo profile, co regression corpus (khuyen nghi)**

Lam semantic alias/normalizer va seasonal permit parser truoc; sau do migrate profile theo ho, chay audit va test sau moi lo. Ket qua audit la dieu kien de mo rong rollout.

Uu: tim duoc regression som, truy vet duoc thay doi. Nhuoc: can nhieu commit nho.

**B. Refactor toan bo 359 YAML trong mot dot**

Uu: cau truc dong bo ngay. Nhuoc: diff lon, kho quy ket regression cho mot hang cu the.

**C. Sua KAL va cac file loi tuong tu truoc**

Uu: co ket qua nhanh. Nhuoc: chua dat muc tieu dung chung cho da so hang.

**Phuong an khac do user nhap:**

## Ke hoach sau khi xac nhan

1. Tao manifest audit va classifier read-only; thong ke cac header, permit pattern, aircraft/route layout va loi theo profile.
2. Dinh nghia canonical table semantics va them test cho common CAAV layout, gom mau KAL nay.
3. Mo rong parser/profile base de xu ly permit number version/season, aliases va continuation rows ma khong lam rong profile detection.
4. Migrate theo lo profile, chay corpus audit so sanh truoc/sau va chi chap nhan thay doi co giai thich.
5. Chay Maven test pham vi worker; lap bao cao ty le parse dung/review/error theo corpus.

## Lua chon cua user

Chua xac nhan.
