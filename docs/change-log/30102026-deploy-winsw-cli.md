# Change Log 30102026 - Sua lenh WinSW trong deploy

- Muc tieu: sua deploy va rollback that bai khi wrapper bao `Unrecognized command or argument 'stopwait'`.
- File sua: `deployment/deploy/AeroSync-Deploy.ps1`.
- Truoc: script goi `stopwait`, va go/cai service khong truyen XML config path; khong tuong thich voi WinSW 3.0 dong trong goi deploy.
- Sau: dung `stop <xml>`, doi toi da 60 giay cho SCM bao `Stopped`, fallback `sc.exe stop` neu can, sau do go va cai lai bang `uninstall <xml>` va `install <xml>`. Deploy va rollback cung dung chung Install-Or-RefreshServices nen cung duoc sua.
- Kiem tra: PowerShell Parser API khong bao loi; WinSW trong `dist/aerosync-0.0.1-20261030-121534` bao version `3.0.0+a6ba41681d84d84d95eb7a377c369d709e32225b`; `--help` xac nhan cac command va cu phap nhan XML path; `git diff --check` sach.
- Gioi han: chua thuc hien deploy tren may dich vu production.
