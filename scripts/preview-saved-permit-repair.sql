-- Run with SQLPlus using the read-only ATFM account.
-- This file intentionally contains SELECT statements only. LD-3331 remains SC.
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF VERIFY OFF ECHO OFF LINESIZE 32767
SET TRANSACTION READ ONLY;

SELECT 'MASTER_NO|' || m.ID || '|' || m.PERM_ID || '|' || m.PERMNBR_ID || '|' ||
       m.OPER_ID || '|' || TO_CHAR(m.PERMDATE, 'YYYY-MM-DD') || '|' || m.FLIGHTTYPE || '|' ||
       m.REFERENCE || '|' || m.VERSION
  FROM T_PERMMASTER_NO m
 WHERE m.ID IN (261486, 261620, 261751, 261865);

SELECT 'DETAIL_NO|' || d.ID || '|' || d.PERM_ID || '|' || d.FLIGHTNBR || '|' ||
       d.DAYSFLIGHT || '|' || d.FROM_AIRP || '|' || d.TO_AIRP || '|' || d.ETD || '|' ||
       d.ETA || '|' || d.CRAFT_ID || '|' || d.VIA || '|' || d.PURPOSE_ID || '|' || d.REMARK
  FROM T_PERMDETAIL_NO d
 WHERE d.PERM_ID IN (232612, 232746, 232877, 232991)
 ORDER BY d.PERM_ID, d.ID;

SELECT 'MASTER_SC|' || m.ID || '|' || m.PERM_ID || '|' || m.PERMNBR_ID || '|' ||
       m.OPER_ID || '|' || TO_CHAR(m.PERMDATE, 'YYYY-MM-DD') || '|' || m.FLIGHTTYPE || '|' ||
       m.REFERENCE || '|' || m.VERSION
  FROM T_PERMMASTER_SC m
 WHERE m.ID IN (203921, 203922, 203923);

SELECT 'DETAIL_SC|' || d.ID || '|' || d.PERM_ID || '|' || d.FLIGHTNBR || '|' ||
       TO_CHAR(d.BEGINDATE, 'YYYY-MM-DD') || '|' || TO_CHAR(d.ENDDATE, 'YYYY-MM-DD') || '|' ||
       d.DAY1 || d.DAY2 || d.DAY3 || d.DAY4 || d.DAY5 || d.DAY6 || d.DAY7 || '|' ||
       d.FROM_AIRP || '|' || d.TO_AIRP || '|' || d.ETD || '|' || d.ETA || '|' ||
       d.CRAFT_ID || '|' || d.VIA || '|' || d.PURPOSE_ID || '|' || d.REMARK
  FROM T_PERMDETAIL_SC d
 WHERE d.PERM_ID IN (203427, 203428, 203429)
 ORDER BY d.PERM_ID, d.ID;

ROLLBACK;
