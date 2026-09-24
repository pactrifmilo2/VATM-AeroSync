SET PAGESIZE 500 FEEDBACK OFF LINESIZE 500 TRIMOUT ON
COLUMN L FORMAT A220
PROMPT ===PERMIT_IMPORTS hom nay (sau 08:00)==
SELECT 'IMP|' || pi.ID || '|job=' || pi.SYNC_JOB_ID || '|norm=' || pi.NORMALIZED_PERMIT_ID ||
       '|master=' || pi.TARGET_MASTER_ID || '|perm=' || pi.TARGET_PERM_ID ||
       '|details=' || pi.DETAIL_COUNT || '|status=' || pi.STATUS ||
       '|at=' || TO_CHAR(pi.UPDATED_AT,'YYYY-MM-DD HH24:MI:SS') AS L
  FROM PERMIT_IMPORTS pi
 WHERE pi.UPDATED_AT >= TO_DATE('2026-09-15 08:00:00','YYYY-MM-DD HH24:MI:SS')
 ORDER BY pi.ID;
PROMPT ===SPQ perm 203429 detail count/remark==
SELECT 'SPQ|' || COUNT(*) || ' rows; cancel-remark=' ||
       SUM(CASE WHEN d.REMARK LIKE '%HUY%' THEN 1 ELSE 0 END) AS L
  FROM T_PERMDETAIL_SC d WHERE d.PERM_ID = 203429;
PROMPT ===FQA perm 232991 detail==
SELECT 'FQA|' || d.ID || '|oper=' || (SELECT m.OPER_ID FROM T_PERMMASTER_NO m WHERE m.PERM_ID = d.PERM_ID) ||
       '|flt=' || d.FLIGHTNBR || '|etd=' || d.ETD || '|eta=' || d.ETA || '|craft=' || d.CRAFT_ID AS L
  FROM T_PERMDETAIL_NO d WHERE d.PERM_ID = 232991 ORDER BY d.ID;
PROMPT ===LD-3331 VIA null hom nay con khong==
SELECT 'VIANULL3331|' || COUNT(*) AS L FROM T_PERMDETAIL_SC d WHERE d.PERM_ID = 203427 AND d.VIA IS NULL;
EXIT;
