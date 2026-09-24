SET PAGESIZE 500 FEEDBACK OFF LINESIZE 500 TRIMOUT ON
COLUMN L FORMAT A300
PROMPT ===HAI REV2 detail (perm 232612)===
SELECT 'D|' || d.ID || '|flt=' || d.FLIGHTNBR || '|dep=' || d.FROM_AIRP || '|arr=' || d.TO_AIRP ||
       '|etd=' || d.ETD || '|eta=' || d.ETA || '|via=' || d.VIA || '|purp=' || d.PURPOSE_ID ||
       '|craft=' || d.CRAFT_ID || '|rmk=' || d.REMARK AS L
  FROM T_PERMDETAIL_NO d WHERE d.PERM_ID = 232612 ORDER BY d.ID;
PROMPT ===HAI baseline masters LD-3185===
SELECT 'M|' || m.ID || '|perm=' || m.PERM_ID || '|oper=' || m.OPER_ID || '|ftype=' || m.FLIGHTTYPE ||
       '|ref=' || m.REFERENCE || '|ver=' || m.VERSION AS L
  FROM T_PERMMASTER_NO m WHERE m.PERMNBR_ID IN
    (SELECT p.ID FROM T_PERMNBR_ID p WHERE p.NEWNO LIKE '%3185%')
  ORDER BY m.ID DESC FETCH FIRST 8 ROWS ONLY;
PROMPT ===OF-6517 SC master 203912===
SELECT 'SM|' || m.ID || '|perm=' || m.PERM_ID || '|pernbr=' || m.PERMNBR_ID || '|oper=' || m.OPER_ID ||
       '|date=' || TO_CHAR(m.PERMDATE,'YYYY-MM-DD') || '|ftype=' || m.FLIGHTTYPE ||
       '|ref=' || m.REFERENCE || '|ver=' || m.VERSION AS L
  FROM T_PERMMASTER_SC m WHERE m.ID = 203912;
PROMPT ===OF-6517 SC details perm 203418===
SELECT 'SD|' || d.ID || '|flt=' || d.FLIGHTNBR || '|from=' || TO_CHAR(d.BEGINDATE,'YYYY-MM-DD') ||
       '|to=' || TO_CHAR(d.ENDDATE,'YYYY-MM-DD') || '|days=' || d.DAY1||d.DAY2||d.DAY3||d.DAY4||d.DAY5||d.DAY6||d.DAY7 ||
       '|dep=' || d.FROM_AIRP || '|arr=' || d.TO_AIRP || '|etd=' || d.ETD || '|eta=' || d.ETA ||
       '|craft=' || d.CRAFT_ID || '|via=' || d.VIA || '|purp=' || d.PURPOSE_ID AS L
  FROM T_PERMDETAIL_SC d WHERE d.PERM_ID = 203418 ORDER BY d.ID;
PROMPT ===M_CRAFT B73 family===
SELECT 'CRAFT|' || c.ID || '|' || c.CODE || '|' || c.NAME AS L
  FROM M_CRAFT_TYPE c WHERE c.CODE LIKE 'B73%' ORDER BY c.CODE;
PROMPT ===M_OPER KMI===
SELECT 'OPER|' || o.ID || '|iata=' || o.IATA || '|icao=' || o.ICAO || '|name=' || o.NAME AS L
  FROM M_OPER o WHERE o.ICAO = 'KMI' OR o.IATA = '8K';
PROMPT ===Routes touching LFPB/VVNB pair===
SELECT 'RT|' || r.ID || '|' || r.FROM_AIRP || '-' || r.TO_AIRP || '|' || SUBSTR(r.ROUTE,1,120) AS L
  FROM M_AIRPORT_ROUTE r
 WHERE (UPPER(TRIM(r.FROM_AIRP))='LFPB' AND UPPER(TRIM(r.TO_AIRP)) IN ('VVNB','HAN'))
    OR (UPPER(TRIM(r.TO_AIRP))='LFPB' AND UPPER(TRIM(r.FROM_AIRP)) IN ('VVNB','HAN'));
PROMPT ===LD-3331 VIA-null detail ids (perm 203427)===
SELECT 'VIANULL|' || d.ID AS L FROM T_PERMDETAIL_SC d WHERE d.PERM_ID = 203427 AND d.VIA IS NULL ORDER BY d.ID;
EXIT;
