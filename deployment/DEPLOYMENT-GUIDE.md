# VATM AeroSync â€“ HÆ°á»›ng dáº«n Ä‘Ã³ng gÃ³i vÃ  cÃ i Ä‘áº·t

## 1. ÄÃ³ng gÃ³i

Táº¡i thÆ° má»¥c gá»‘c repository:

```powershell
Set-Location E:\ATFM-AEROSYNC\VATM-AeroSync
.\scripts\release.ps1 -WinSwPath 'C:\Tools\WinSW-x64.exe'
```

Náº¿u JAR Ä‘Ã£ build sáºµn, dÃ¹ng `-SkipBuild`. ZIP Ä‘Æ°á»£c táº¡o trong `dist`. KhÃ´ng sá»­a JAR hoáº·c `SHA256SUMS.txt` sau khi Ä‘Ã³ng gÃ³i.

## 2. Äiá»u kiá»‡n mÃ¡y má»›i

- Windows PowerShell 5.1 vÃ  quyá»n Administrator.
- Java 21 trong `PATH`, `JAVA_HOME`, hoáº·c vá»‹ trÃ­ Java Ä‘Æ°á»£c script há»— trá»£.
- Káº¿t ná»‘i Ä‘Æ°á»£c tá»›i Oracle; RabbitMQ (`5672`) vÃ  Redis (`6379`) Ä‘ang cháº¡y.
- Cá»•ng API cáº¥u hÃ¬nh chÆ°a bá»‹ á»©ng dá»¥ng khÃ¡c chiáº¿m.

KhÃ´ng cáº§n Maven, Node.js hoáº·c .NET SDK Ä‘á»ƒ cháº¡y gÃ³i ZIP. UI Ä‘Æ°á»£c Ä‘Ã³ng gÃ³i tá»± chá»©a cho Windows x64.

Kiá»ƒm tra:

```powershell
java -version
Test-NetConnection 192.168.131.15 -Port 1521
Test-NetConnection localhost -Port 5672
Test-NetConnection localhost -Port 6379
```

## 3.1. Cáº­p nháº­t tá»« Ä‘iá»ƒn ATFM láº§n Ä‘áº§u

Sau khi kiá»ƒm tra káº¿t ná»‘i Oracle, cháº¡y má»™t láº§n migration trong thÆ° má»¥c `tools` báº±ng tÃ i khoáº£n ATFM:

```powershell
sqlplus -L -S atfm/<mat-khau>@//<oracle-host>:1521/<service-name> `
  "@C:\AeroSync\tools\migrate-atfm-purpose-craft.sql"
```

Migration cÃ³ thá»ƒ cháº¡y láº¡i an toÃ n: bá»• sung cÃ¡c cá»¥m tá»« purpose khÃ´ng trÃ¹ng vÃ  táº¡o loáº¡i tÃ u bay dá»± phÃ²ng `A000` náº¿u chÆ°a cÃ³. `A000` chá»‰ Ä‘Æ°á»£c dÃ¹ng cho phÃ©p Ä‘á»™t xuáº¥t khi khÃ´ng Ä‘á»c/Ä‘á»‘i chiáº¿u Ä‘Æ°á»£c tÃ u bay; phÃ©p mÃ¹a váº«n kiá»ƒm tra nghiÃªm ngáº·t. KhÃ´ng Ä‘Æ°a máº­t kháº©u tháº­t vÃ o file lá»‡nh hoáº·c log.

## 3. Cáº¥u hÃ¬nh

Giáº£i nÃ©n toÃ n bá»™ ZIP. Táº¡o file tháº­t táº¡i `C:\AeroSync\config\.env`:

```powershell
New-Item -ItemType Directory -Force C:\AeroSync\config | Out-Null
Copy-Item '.\config\.env.example' 'C:\AeroSync\config\.env'
notepad 'C:\AeroSync\config\.env'
```

CÃ¡c biáº¿n tá»‘i thiá»ƒu:

```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//<oracle-host>:1521/<service-name>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<password>
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=5672
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
SERVER_PORT=8081
```

TÃªn biáº¿n pháº£i dÃ¹ng `_` bÃ¬nh thÆ°á»ng, khÃ´ng thÃªm `\`. KhÃ´ng Ä‘Æ°a máº­t kháº©u tháº­t vÃ o Git hoáº·c log.

## 4. Kiá»ƒm tra trÆ°á»›c khi deploy

Má»Ÿ PowerShell báº±ng Administrator táº¡i thÆ° má»¥c Ä‘Ã£ giáº£i nÃ©n:

```powershell
.\AeroSync-Deploy.cmd check
```

Chá»‰ tiáº¿p tá»¥c khi khÃ´ng cÃ²n dÃ²ng `[X]`. Script kiá»ƒm tra quyá»n, Java, file gÃ³i, `.env`, Oracle/RabbitMQ/Redis, cá»•ng API vÃ  tráº¡ng thÃ¡i service.

## 5. CÃ i Ä‘áº·t hoáº·c cáº­p nháº­t

```powershell
.\AeroSync-Deploy.cmd
```

Script sáº½ dá»«ng ba service, chá» chÃºng chuyá»ƒn sang `Stopped`, sao lÆ°u báº£n cÅ©, cáº­p nháº­t JAR/WinSW, cÃ i hoáº·c refresh service rá»“i khá»Ÿi Ä‘á»™ng vÃ  kiá»ƒm tra API. KhÃ´ng xÃ³a `C:\AeroSync`; `.env` vÃ  báº£n sao lÆ°u Ä‘Æ°á»£c giá»¯ láº¡i.

Sau khi API san sang, script cai UI vao `C:\AeroSync\ui`, tao shortcut `AeroSync` tren Desktop va Start Menu, roi mo UI. UI doc truc tiep `C:\AeroSync\config\.env` dung chung voi service va tu lay `SERVER_PORT` de ket noi API.

## 6. Kiá»ƒm tra sau cÃ i Ä‘áº·t

```powershell
.\AeroSync-Deploy.cmd status
Get-Service AeroSyncApi,AeroSyncIngest,AeroSyncWorker
Invoke-WebRequest http://localhost:8081/api/dashboard/stats -UseBasicParsing
```

Cáº£ ba service cáº§n á»Ÿ tráº¡ng thÃ¡i `Running`.

## 7. Lá»‡nh ká»¹ thuáº­t

```powershell
.\AeroSync-Deploy.cmd check
.\AeroSync-Deploy.cmd status
.\AeroSync-Deploy.cmd restart
.\AeroSync-Deploy.cmd rollback
```

## 8. Log vÃ  lá»—i thÆ°á»ng gáº·p

Log triá»ƒn khai: `C:\AeroSync\logs\deploy-*.log`.

Log á»©ng dá»¥ng:

```text
C:\AeroSync\logs\api\AeroSyncApi.out.log
C:\AeroSync\logs\ingest\AeroSyncIngest.out.log
C:\AeroSync\logs\worker\AeroSyncWorker.out.log
```

- `ORA-12514`: sai service Oracle hoáº·c Listener chÆ°a Ä‘Äƒng kÃ½ service.
- `ORA-01017`: sai username/password Oracle.
- `Unable to connect`: dependency chÆ°a cháº¡y hoáº·c firewall cháº·n.
- Cá»•ng API bá»‹ chiáº¿m: Ä‘á»•i `SERVER_PORT` trong `C:\AeroSync\config\.env`.
- Service cháº¡y rá»“i dá»«ng: xem log `.out.log` tÆ°Æ¡ng á»©ng.

Khi gá»­i log cho ká»¹ thuáº­t, pháº£i che máº­t kháº©u, token vÃ  dá»¯ liá»‡u nháº¡y cáº£m.
