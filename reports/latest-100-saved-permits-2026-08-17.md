# Latest 100 SAVED permit audit

- Generated: 2026-08-17 09:22:16 +07:00
- Scope: latest 100 tracking imports with status SAVED
- Result: PASS 2, REVIEW 31, FAIL 67
- Database access: read-only SELECT statements

## Current code retest

- The exact 100 source files in this audit were rerun through profile detection, parsing, live ATFM reference resolution, revision reconciliation, aircraft/airport/route resolution, and business validation.
- Result after the code fixes: **100 passed, 0 failed, 0 non-permit files**.
- The PASS/REVIEW/FAIL counts above describe the existing target-database snapshot. This audit is read-only, so historical rows created before the fixes were not rewritten.

## Checks

The audit checks target existence and identity, SC/NO routing, detail counts, required schedule fields, reference, billing address, stored source content, remarks, source-file readability, and revision reference markers.

## Findings

| Result | Import | Job | Saved at | File | Permit | Target | Details | Revision | Finding |
|---|---:|---:|---|---|---|---|---:|---|---|
| REVIEW | 1758 | 10073 | 2026-08-17 09:11:02 | OF-5831-MEDIC79-17AUG-DHT.docx | O/F 05831/CHK/2026 | NO:260728/231854 | 2/2 | False | original reference is blank |
| REVIEW | 1757 | 10066 | 2026-08-17 08:27:26 | OF-5829-P4BVM-16AUG-DHT.docx | O/F 05829/CHK/2026 | NO:260727/231853 | 1/1 | False | original reference is blank |
| REVIEW | 1756 | 10061 | 2026-08-17 08:27:21 | LD-2983_EO3659_17AUG26.docx | LD 02983/CHK/2026 | NO:260726/231852 | 28/28 | False | original reference is blank |
| REVIEW | 1755 | 10060 | 2026-08-17 08:27:20 | LD-2984_N43687_16AUG26.docx | LD 02984/CHK/2026 | NO:260725/231851 | 28/28 | False | original reference is blank |
| REVIEW | 1754 | 10057 | 2026-08-17 08:27:16 | LD-2985_VAJ99K 17AUG26.docx | LD 02985/CHK/2026 | NO:260724/231850 | 4/4 | False | original reference is blank |
| REVIEW | 1753 | 10056 | 2026-08-17 08:27:14 | OF-5830-VQBOY_17AUG26.docx | O/F 05830/CHK/2026 | NO:260723/231849 | 1/1 | False | original reference is blank |
| REVIEW | 1752 | 10048 | 2026-08-15 21:23:58 | OF-5828-NCR832-16AUG26.docx | O/F 05828/CHK/2026 | NO:260722/231848 | 1/1 | False | original reference is blank |
| PASS | 1751 | 10047 | 2026-08-15 19:33:25 | REV1-LD-2797_K4_16-30AUG.docx | LD 02797R1/CHK/2026 | NO:260721/231847 | 15/15 | True |  |
| REVIEW | 1750 | 10046 | 2026-08-15 17:03:52 | OF-5827.docx | O/F 05827/CHK/2026 | NO:260720/231846 | 1/1 | False | original reference is blank |
| FAIL | 1749 | 10045 | 2026-08-15 16:04:08 | OF-5736-REVISE ABD4303_15AUG26_1.docx | O/F 05736/CHK/2026 | NO:260432/231558 | 1/2 | True | detail count mismatch; revision reference is blank |
| REVIEW | 1733 | 10012 | 2026-08-14 17:02:13 | OF-5820.8.2026VN-MFX7634-18-20AUG26 vsa.docx | O/F 05820/CHK/2026 | NO:260710/231836 | 2/2 | False | original reference is blank |
| FAIL | 1622 | 9731 | 2026-08-12 19:52:46 | (10dec)-RVS1-LD.3697.10.2024VN-qh9168_01feb.docx | LD 03697/CHK/2024 | NO:260649/231775 | 4/4 | True | revision reference is blank |
| FAIL | 1621 | 9730 | 2026-08-12 19:52:41 | OF-7440 (RVS1).docx | O/F 07440/CHK/2024 | NO:244635/215220 | 1/5 | True | billing address is blank |
| FAIL | 1618 | 9683 | 2026-08-12 19:50:05 | OF-7087 (RVS2).docx | O/F 07087/CHK/2024 | NO:244157/214723 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1617 | 9674 | 2026-08-12 19:48:07 | (02MAR)_RVS_LD635_QH802_03MAR.docx | LD 00635/CHK/2024 | NO:260647/231773 | 1/1 | True | revision reference is blank; billing address is blank |
| FAIL | 1615 | 9660 | 2026-08-12 19:47:46 | LD-3767 RVS1.doc | LD 03767/CHK/2024 | NO:260646/231772 | 3/3 | True | revision reference is blank; billing address is blank |
| FAIL | 1614 | 9623 | 2026-08-12 19:45:31 | OF-7305 (RVS1).docx | O/F 07305/CHK/2024 | NO:244445/215031 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1612 | 9609 | 2026-08-12 19:43:52 | OF_23.B.S.2024VN_MAS-PAX_01MAR24.docx | O/F 0023B/S/CHK/2024 | SC:185153/186860 | 38/82 | False | detail count mismatch |
| FAIL | 1611 | 9601 | 2026-08-12 19:43:38 | REVISE 1_OF-87BW.docx | O/F 0087R1B/W/CHK/2023 | SC:203822/203328 | 4/4 | True | revision reference is blank |
| FAIL | 1610 | 9600 | 2026-08-12 19:43:35 | LD-763 (rvs).docx | LD 00763/CHK/2024 | NO:260645/231771 | 2/2 | True | revision reference is blank; billing address is blank |
| REVIEW | 1609 | 9530 | 2026-08-12 19:39:07 | LD-744.02.2024VN- 6P701-702_29FEB (HAN).DOCX | LD 00744/CHK/2024 | NO:260644/231770 | 2/2 | False | original reference is blank |
| FAIL | 1607 | 9519 | 2026-08-12 19:38:51 | LD-763_29FEB.docx | LD 0048B/W/CHK/2023 | SC:183822/185238 | 2/45 | False | billing address is blank |
| FAIL | 1606 | 9507 | 2026-08-12 19:38:32 | LD-4004 (RVS1).doc | LD 04004/CHK/2024 | NO:260643/231769 | 4/4 | True | revision reference is blank; billing address is blank |
| FAIL | 1598 | 9450 | 2026-08-12 19:36:19 | LD-4169_IO861_12DEC24.docx | LD 04169/CHK/2024 | NO:260641/231767 | 8/8 | False | billing address is blank |
| FAIL | 1596 | 9397 | 2026-08-12 19:32:06 | OF-11A-S24-TLM.DOCX | O/F 0011A/S/CHK/2024 | SC:185065/186772 | 2/38 | False | detail count mismatch; billing address is blank |
| FAIL | 1595 | 9394 | 2026-08-12 19:32:00 | OF-1175.docx | O/F 01175/CHK/2024 | NO:260639/231765 | 2/2 | False | billing address is blank |
| REVIEW | 1594 | 9338 | 2026-08-12 19:29:38 | (22FEB)_LD_720.02.2024VN_QH224_29FEB-30Mar.docx | LD 00720/CHK/2024 | NO:260638/231764 | 3/3 | False | original reference is blank |
| FAIL | 1593 | 9334 | 2026-08-12 19:29:31 | 4. OZ CHANGE OF CHG LD-48BW2023VN W23 - OZ383384.docx | LD 0048B/W/CHK/2023 | SC:183822/185238 | 3/45 | False | billing address is blank |
| FAIL | 1591 | 9327 | 2026-08-12 19:29:19 | LD-4180.12.2024VN._.docx | LD 0022R1A/W/CHK/2024 | SC:203821/203327 | 17/17 | False | billing address is blank |
| FAIL | 1587 | 9287 | 2026-08-12 19:27:14 | OF-7473 (RVS1).docx | O/F 07473/CHK/2024 | NO:244673/215258 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1586 | 9285 | 2026-08-12 19:27:10 | OF-7377 (RVS1).docx | O/F 07377/CHK/2024 | NO:244551/215154 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1585 | 9283 | 2026-08-12 19:27:06 | OF-7467 (RVS1).docx | O/F 07467/CHK/2024 | NO:244664/215249 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1584 | 9276 | 2026-08-12 19:26:50 | OF-7440 (RVS2).docx | O/F 07440/CHK/2024 | NO:244635/215220 | 1/5 | True | billing address is blank |
| FAIL | 1583 | 9274 | 2026-08-12 19:26:46 | OF-7009 (RVS1).docx | O/F 07009/CHK/2024 | NO:244048/214614 | 1/11 | True | detail count mismatch; billing address is blank |
| FAIL | 1582 | 9258 | 2026-08-12 19:25:21 | LD-699_26FEB24.docx | LD 00699/CHK/2024 | NO:260636/231762 | 2/2 | False | billing address is blank |
| FAIL | 1580 | 9232 | 2026-08-12 19:24:49 | RVS2-LD.3718.11.2024VN-AK-19DEC.docx | LD 03718/CHK/2024 | NO:260634/231760 | 2/2 | True | revision reference is blank; billing address is blank |
| FAIL | 1579 | 9229 | 2026-08-12 19:24:38 | LD-4182.docx | LD 04182/CHK/2024 | NO:260633/231759 | 10/10 | False | billing address is blank |
| FAIL | 1578 | 9228 | 2026-08-12 19:24:32 | CHG OF-95_A_W_2024VN.docx | O/F 0095A/W/CHK/2024 | SC:189254/189947 | 2/28 | False | detail count mismatch |
| FAIL | 1577 | 9219 | 2026-08-12 19:24:20 | LD-4183.docx | LD 0050B/W/CHK/2024 | SC:189274/189967 | 1/54 | False | billing address is blank |
| REVIEW | 1576 | 9207 | 2026-08-12 19:21:27 | LD_11.C.W.2023VN_IO_000.00.17.G04-240222-0054.doc | LD 0011C/W/CHK/2023 | SC:203820/203326 | 4/4 | False | legacy DOC was not text-inspected; original reference is blank |
| FAIL | 1575 | 9161 | 2026-08-12 19:19:04 | OF-7477 (RVS1).docx | O/F 07477/CHK/2024 | NO:244801/215387 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1571 | 9111 | 2026-08-12 19:16:31 | OF-7476 (RVS1).docx | O/F 07476/CHK/2024 | NO:244797/215383 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1570 | 9110 | 2026-08-12 19:16:29 | OF-7522 (RVS1).docx | O/F 07522/CHK/2024 | NO:244756/215342 | 1/3 | True | detail count mismatch; billing address is blank |
| FAIL | 1568 | 9086 | 2026-08-12 19:14:45 | Application for Updating Aircraft Registration Marks on LD546 Feb2024.docx | LD 00546/CHK/2024 | NO:260630/231756 | 3/3 | False | billing address is blank |
| REVIEW | 1567 | 9058 | 2026-08-12 19:14:08 | (13dec)_LD-4200.12.2024VN-qh263_19dec.docx | LD 04200/CHK/2024 | NO:260629/231755 | 14/14 | False | original reference is blank |
| REVIEW | 1566 | 9027 | 2026-08-12 19:12:35 | OF_06.A.S.2024VN_UPS_PAX_22.02.2024.docx | O/F 0006A/S/CHK/2024 | SC:203819/203325 | 18/18 | False | original reference is blank |
| FAIL | 1563 | 9011 | 2026-08-12 19:12:09 | (19FEB)_RVS_LD.3794, 635_QH1547.docx | LD 03794/CHK/2023 | NO:260491/231617 | 3/5 | True | revision reference is blank |
| REVIEW | 1561 | 8995 | 2026-08-12 19:11:47 | LD-4206.12.2024VN_EK9854-EK9855-26DEC.docx | LD 04206/CHK/2024 | NO:260627/231753 | 2/2 | False | original reference is blank |
| FAIL | 1559 | 8967 | 2026-08-12 19:09:52 | LD-647.02.2024VN-HY3211-22FEB-08MAR24-(PQC).docx | LD 00647/CHK/2024 | NO:260626/231752 | 4/4 | True | revision reference is blank; billing address is blank |
| REVIEW | 1558 | 8948 | 2026-08-12 19:09:31 | LD-.658.02.=?utf-8?B?MjAyNFZOX+G6oEtfMjJGRUJfMjI=?=.02.2024.docx | LD 00658/CHK/2024 | NO:260625/231751 | 1/1 | False | original reference is blank |
| REVIEW | 1557 | 8904 | 2026-08-12 19:05:28 | (18FEB)_LD_646.02.2024VN_QH9550_21FEB-03Mar.docx | LD 00646/CHK/2024 | NO:260624/231750 | 14/14 | False | original reference is blank |
| FAIL | 1555 | 8896 | 2026-08-12 19:05:13 | OF-7532 (RVS1).docx | O/F 07532/CHK/2024 | NO:244815/215391 | 1/4 | True | billing address is blank |
| FAIL | 1551 | 8858 | 2026-08-12 19:02:08 | LD_80_A_W_2023VN_DR.doc | LD 0080A/W/CHK/2024 | SC:203818/203324 | 2/2 | False | billing address is blank |
| REVIEW | 1550 | 8855 | 2026-08-12 19:01:57 | LD-4214.doc | LD 0053R1A/W/CHK/2024 | SC:203817/203323 | 14/14 | False | legacy DOC was not text-inspected; original reference is blank |
| FAIL | 1547 | 8829 | 2026-08-12 19:00:47 | RVS2.OF-24A.W24_MAS038_renumber-and-retiming.docx | O/F 0024R2A/W/CHK/2024 | SC:203816/203322 | 111/111 | True | revision reference is blank |
| FAIL | 1545 | 8801 | 2026-08-12 18:58:11 | LD-625-CI6595_24FEB24.doc | LD 00625/CHK/2024 | NO:260621/231747 | 3/3 | False | billing address is blank |
| FAIL | 1544 | 8767 | 2026-08-12 18:56:47 | OF-7576 (RVS1).docx | O/F 07576/CHK/2024 | NO:244855/215430 | 1/2 | True | detail count mismatch; billing address is blank |
| REVIEW | 1543 | 8766 | 2026-08-12 18:54:58 | (15FEB)_LD-584.02.2024VN_QH261_19-29FEB.docx | LD 00584/CHK/2024 | NO:260620/231746 | 15/15 | False | original reference is blank |
| FAIL | 1542 | 8759 | 2026-08-12 18:54:40 | OF-04A-S24-NOK.docx | O/F 0004A/S/CHK/2024 | SC:203815/203321 | 4/4 | False | billing address is blank |
| FAIL | 1540 | 8742 | 2026-08-12 18:54:16 | OF-971.docx | O/F 00971/CHK/2024 | NO:235375/205465 | 1/3 | False | detail count mismatch; billing address is blank |
| FAIL | 1538 | 8731 | 2026-08-12 18:54:00 | OF-7437 (RVS1).docx | O/F 07437/CHK/2024 | NO:244638/215223 | 1/4 | True | billing address is blank |
| FAIL | 1537 | 8730 | 2026-08-12 18:53:57 | OF-7178 (RVS1).docx | O/F 07178/CHK/2024 | NO:244302/214888 | 1/2 | True | detail count mismatch; billing address is blank |
| REVIEW | 1535 | 8695 | 2026-08-12 18:51:47 | (16FEB)_RVS_LD341, 542_QH181_17-23FEB24.docx | LD 00341/CHK/2024 | NO:260585/231711 | 2/5 | True | shared target has accumulated details; revision signal found but reference has no RVS/Rn marker |
| REVIEW | 1531 | 8671 | 2026-08-12 18:51:08 | LD4224.12.2024VN.docx | LD 04224/CHK/2024 | NO:260618/231744 | 4/4 | False | original reference is blank |
| FAIL | 1530 | 8657 | 2026-08-12 18:50:43 | OF-7613 (RVS1).docx | O/F 07613/CHK/2024 | NO:244908/215465 | 1/2 | True | detail count mismatch; billing address is blank |
| REVIEW | 1527 | 8644 | 2026-08-12 18:50:09 | (14dec)_LD-4228.12.2024VN-QH101_23dec.docx | LD 04228/CHK/2024 | NO:260617/231743 | 21/21 | False | original reference is blank |
| REVIEW | 1526 | 8643 | 2026-08-12 18:50:02 | (16dec)_LD-4229.12.2024VN_QH245_24dec.docx | LD 04229/CHK/2024 | NO:260616/231742 | 11/11 | False | original reference is blank |
| FAIL | 1525 | 8642 | 2026-08-12 18:49:57 | (15dec)_LD-4231.12.2024VN-QH1320_22dec-RVS.LD38B.docx | LD 04231/CHK/2024 | NO:260615/231741 | 4/4 | True | revision reference is blank |
| REVIEW | 1522 | 8582 | 2026-08-12 18:44:38 | (14FEB)_LD_576_QH16_16FEB.docx | LD 00576/CHK/2024 | NO:260614/231740 | 2/2 | False | original reference is blank |
| FAIL | 1521 | 8573 | 2026-08-12 18:44:26 | OF-03_A_S_2024_APZ.doc | O/F 0003A/S/CHK/2024 | SC:203814/203320 | 8/8 | False | billing address is blank |
| FAIL | 1520 | 8547 | 2026-08-12 18:43:55 | OF-7437 (RVS2).docx | O/F 07437/CHK/2024 | NO:244638/215223 | 1/4 | True | billing address is blank |
| REVIEW | 1517 | 8517 | 2026-08-12 18:41:42 | (12FEB)_RVS_LD480, 3484_QH182_16-20FEB24.docx | LD 00480/CHK/2024 | NO:260613/231739 | 9/9 | True | revision signal found but reference has no RVS/Rn marker |
| FAIL | 1516 | 8503 | 2026-08-12 18:41:17 | OF-7655 (RVS1).docx | O/F 07655/CHK/2024 | NO:244973/215515 | 1/3 | True | billing address is blank |
| FAIL | 1515 | 8502 | 2026-08-12 18:41:14 | OF-7619 (RVS1).docx | O/F 07619/CHK/2024 | NO:244956/215513 | 1/2 | True | detail count mismatch; billing address is blank |
| PASS | 1514 | 8483 | 2026-08-12 18:40:51 | OF-7681.docx | O/F 07681/CHK/2024 | NO:260612/231738 | 1/1 | False |  |
| FAIL | 1513 | 8437 | 2026-08-12 18:38:37 | OF-7532 (RVS2).docx | O/F 07532/CHK/2024 | NO:244815/215391 | 1/4 | True | billing address is blank |
| REVIEW | 1510 | 8266 | 2026-08-12 18:28:55 | (18dec)_RVS1-LD-4010.12.2024vn_qh9568_24dec.docx | LD 04010/CHK/2024 | NO:260610/231736 | 2/2 | True | revision signal found but reference has no RVS/Rn marker |
| REVIEW | 1507 | 8252 | 2026-08-12 18:28:32 | OF-807.02.2024VN-OD8685-10FEB2024.docx | O/F 00807/CHK/2024 | NO:260608/231734 | 2/2 | False | original reference is blank |
| FAIL | 1506 | 8245 | 2026-08-12 18:28:24 | (09FEB)_LD_542_QH290_09-21FEB.docx | LD 00542/CHK/2024 | NO:260607/231733 | 35/35 | True | revision reference is blank |
| FAIL | 1505 | 8244 | 2026-08-12 18:28:20 | (10FEB)_LD_543-QH1041_13-21FEB.docx | LD 00543/CHK/2024 | NO:260606/231732 | 4/4 | True | revision reference is blank |
| FAIL | 1504 | 8243 | 2026-08-12 18:28:18 | (11FEB)_RVS-LD.285_QH9510_09-14FEB24.docx | LD 00285/CHK/2024 | NO:260579/231705 | 2/3 | True | revision reference is blank |
| FAIL | 1503 | 8232 | 2026-08-12 18:28:04 | OF-7688 (RVS1).docx | O/F 07688/CHK/2024 | NO:245033/215555 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1502 | 8201 | 2026-08-12 18:27:21 | LD_537_CI6595 10FEB24.docx | LD 00537/CHK/2024 | NO:260605/231731 | 3/3 | False | billing address is blank |
| FAIL | 1501 | 8184 | 2026-08-12 18:26:53 | OF-7655 (RVS2).docx | O/F 07655/CHK/2024 | NO:244973/215515 | 1/3 | True | billing address is blank |
| FAIL | 1500 | 8155 | 2026-08-12 17:40:11 | Application for Changing cargo flight-OZ393-394-day 6 on Feb2024_UTC .docx | LD 0048B/W/CHK/2023 | SC:183822/185238 | 3/45 | True | revision reference is blank; billing address is blank |
| FAIL | 1499 | 8116 | 2026-08-12 17:39:29 | OF-7697 (RVS1).docx | O/F 07697/CHK/2024 | NO:245108/215616 | 1/2 | True | detail count mismatch; billing address is blank |
| FAIL | 1495 | 8078 | 2026-08-12 17:37:37 | LD-510_07FEB.docx | LD 0030A/W/CHK/2023 | SC:183794/185220 | 2/18 | False | detail count mismatch; billing address is blank |
| FAIL | 1494 | 8058 | 2026-08-12 17:37:20 | (19dec)_LD-4286.12.2024VN_qh255_25dec-RVS.LD.38A.docx | LD 04286/CHK/2024 | NO:260604/231730 | 2/2 | True | revision reference is blank |
| FAIL | 1492 | 8020 | 2026-08-12 17:35:40 | LD-497_CZ SGN.docx | LD 00497/CHK/2024 | NO:260603/231729 | 6/6 | False | billing address is blank |
| REVIEW | 1491 | 8013 | 2026-08-12 17:35:33 | (06Feb)_LD_496_QH1547_08-25Feb.docx | LD 00496/CHK/2024 | NO:260602/231728 | 16/16 | False | original reference is blank |
| REVIEW | 1489 | 8011 | 2026-08-12 17:35:28 | (07FEB)_LD_501_QH9550_11-21FEB.docx | LD 00501/CHK/2024 | NO:260600/231726 | 27/27 | False | original reference is blank |
| FAIL | 1487 | 7971 | 2026-08-12 17:34:44 | LD-4296.12.2024VN-AK8920_02-30jan.docx | LD 04296/CHK/2024 | NO:260505/231631 | 2/6 | False | billing address is blank |
| FAIL | 1484 | 7928 | 2026-08-12 17:32:58 | OF-7794.docx | O/F 07794/CHK/2024 | NO:245221/215717 | 1/3 | False | detail count mismatch; billing address is blank |
| FAIL | 1483 | 7920 | 2026-08-12 17:32:52 | LD-4304.12.2024VN_EK9854-EK9855-28DEC.docx | LD 04304/CHK/2024 | NO:260598/231724 | 2/2 | False | billing address is blank |
| REVIEW | 1481 | 7914 | 2026-08-12 17:32:44 | (20dec)_LD-4294.12.2024VN_QH1521_27dec.docx | LD 04294/CHK/2024 | NO:260597/231723 | 14/14 | False | original reference is blank |
| FAIL | 1480 | 7913 | 2026-08-12 17:32:42 | (23dec)_LD-4305.12.2024VN_QH255_29dec-RVS.LD.38A.docx | LD 04305/CHK/2024 | NO:260596/231722 | 2/2 | True | revision reference is blank |
| FAIL | 1479 | 7867 | 2026-08-12 17:30:32 | LD-479_05FEB.docx | LD 0016A/W/CHK/2023 | SC:183846/185272 | 4/28 | False | detail count mismatch; billing address is blank |
| FAIL | 1477 | 7845 | 2026-08-12 17:30:04 | RVS3.OF24A_MAS064_31dec24_retimed-renumbered.docx | O/F 0024R3A/W/CHK/2024 | SC:203811/203317 | 4/4 | True | revision reference is blank |
| REVIEW | 1474 | 7826 | 2026-08-12 17:28:22 | (03Feb)_LD463.02.2024VN_QH_05-07Feb.docx | LD 00463/CHK/2024 | NO:260595/231721 | 31/31 | False | original reference is blank |
| FAIL | 1473 | 7819 | 2026-08-12 17:28:09 | REVISE OF-84_G_W_2023_CHH.DOC | O/F 0084G/W/CHK/2023 | SC:184415/185912 | 1/4 | True | detail count mismatch; revision reference is blank |
