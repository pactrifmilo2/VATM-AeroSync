# Phieu plan 02112026 - Thong bao loi dinh dang file cu the

## Muc tieu

Khi xu ly file that bai do format/cau truc, thong bao cho biet file khac mau o dau va cach sua; neu khong co mau tuong ung thi noi ro chua co format mau.

## Pham vi du kien

- Word DOC/DOCX: chon profile YAML, phan tich bang/header/cot va loi parse cau truc.
- Excel XLSX: sheet/header/cot va loi cau truc bang.
- Worker luu error, API dich thong bao va UI hien thi thong bao.
- Khong doi cach xu ly loi nghiep vu cua gia tri du lieu (vi du ma san bay, ngay bay) thanh loi format.
- Ghi change log va commit local rieng sau khi test; khong dua thay doi dang co cua user vao commit.

## Cau hoi can xac nhan

### 1. Pham vi loai file

- [ ] A. DOC/DOCX va XLSX (khuyen nghi): tap trung vao format co mau va cau truc bang.
- [ ] B. Them CSV/JSON/XML: can mo rong thong diep mau/schema ca file dang text.
- [ ] C. Chi DOC/DOCX: Excel giu thong bao hien tai.
- [ ] Phuong an khac do user nhap: ____________________

### 2. Chi tiet khi co mau gan nhat

- [ ] A. Neu ro profile/mau, vi tri (bang/sheet, dong/cot/truong), gia tri dang co, gia tri/alias mong doi va cach sua khi co the suy ra (khuyen nghi).
- [ ] B. Chi neu vi tri va truong mong doi, khong hien gia tri trong file.
- [ ] C. Chi dua ra ten profile va loi tong quat; khong diff cau truc.
- [ ] Phuong an khac do user nhap: ____________________

Luu y: Neu khong the xac dinh duy nhat mau gan nhat hoac vi tri khac, thong bao phai noi ro gioi han, khong duoc suy doan gia tri mong doi.

### 3. Loi khong co mau phu hop

- [ ] A. Noi ro chua co format YAML/mau phu hop va huong dan gui file mau cho quan tri bo sung (khuyen nghi).
- [ ] B. Noi ro chua co mau, khong dua huong dan tiep theo.
- [ ] C. Chi ghi "khong nhan dang duoc format", khong ket luan thieu mau.
- [ ] Phuong an khac do user nhap: ____________________

## Lua chon mac dinh Codex de xuat

1A, 2A, 3A. Loi chi tiet dua theo kha nang nhan dien co bang chung; loi noi dung du lieu giu nguyen nhom loi hien tai. Neu ban muon lua chon nay, phan hoi: "xac nhan mac dinh". Neu chon khac, ghi lua chon/bo sung phia tren va gui lai duong dan phieu.

## Lua chon cua user

Chua xac nhan.

## Ke hoach sau khi xac nhan

1. Trace cac diem phat sinh va luu error cho Word/Excel; chot thong diep co du lieu vi tri thuc te.
2. Them classifier/diff format voi fallback an toan khi khong co mau hoac khong du bang chung.
3. Bao dam thong diep duoc giu nguyen qua worker -> API -> UI; bo sung test tung tang.
4. Chay test worker/API/UI pham vi lien quan, cap nhat change log va kiem tra staged diff truoc commit local.

## Rang buoc hien trang

- Git dang co thay doi user o `aerosync-ui/obj/*` va `aerosync-worker/debug-1208ff.log`; khong sua, stage hoac commit cac file nay.
- Chua co thay doi code trong phan viec nay.
