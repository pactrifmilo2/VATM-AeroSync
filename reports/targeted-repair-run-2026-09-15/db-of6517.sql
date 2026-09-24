SET PAGESIZE 300 FEEDBACK OFF LINESIZE 400 TRIMOUT ON
COLUMN L FORMAT A(380)
PROMPT ===SC MASTER 203912===
SELECT 'SCM|' || m.ID || '|perm=' || m.PERM_ID || '|pernbr=' || m.PERMNBR_ID || '|oper=' || m.OPER_ID ||
       '|date=' || TO_CHAR(m.PERMDATE,'YYYY-MM-DD') || '|ftype=' || m.FLIGHTTYPE ||
       '|ref=' || m.REFERENCE || '|ver=' || m.VERSION AS L
  FROM T_PERMMASTER_SC m WHERE m.ID = 203912;
PROMPT ===NO MASTER 203912===
SELECT 'NOM|' || m.ID || '|perm=' || m.PERM_ID || '|oper=' || m.OPER_ID || '|ftype=' || m.FLIGHTTYPE AS L
  FROM T_PERMMASTER_NO m WHERE m.ID = 203912;
PROMPT ===SC DETAILS 203418===
SELECT 'SCD|' || d.ID || '|flt=' || d.FLIGHTNBR || '|from=' || d.BEGINDATE || '|to=' || d.ENDDATE ||
       '|days=' || d.DAY1||d.DAY2||d.DAY3||d.DAY4||d.DAY5||d.DAY6||d.DAY7 ||
       '|dep=' || d.FROM_AIRP || '|arr=' || d.TO_AIRP || '|etd=' || d.ETD || '|eta=' || d.ETA ||
       '|craft=' || d.CRAFT_ID || '|via=' || d.VIA || '|purp=' || d.PURPOSE_ID || '|rmk=' || d.REMARK AS L
  FROM T_PERMDETAIL_SC d WHERE d.PERM_ID = 203418 ORDER BY d.ID;
PROMPT ===OPER KMI===
SELECT 'OPER|' || o.ID || '|' || o.IATA || '|' || o.ICAO || '|' || o.NAME AS L FROM M_OPER o WHERE o.ICAO = 'KMI' OR o.IATA = 'KMI';
EXIT;
