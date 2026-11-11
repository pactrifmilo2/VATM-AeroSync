WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET ECHO OFF
SET FEEDBACK ON
SET VERIFY OFF
SET SERVEROUTPUT ON

PROMPT
PROMPT Creating ATFM revision backup tables when missing...

DECLARE
    PROCEDURE create_backup_table(
        source_table_in VARCHAR2,
        backup_table_in VARCHAR2
    ) IS
        source_count NUMBER;
        backup_count NUMBER;
    BEGIN
        SELECT COUNT(*)
          INTO source_count
          FROM user_tables
         WHERE table_name = UPPER(source_table_in);

        IF source_count = 0 THEN
            RAISE_APPLICATION_ERROR(
                -20001,
                'Required ATFM source table is missing: ' || UPPER(source_table_in));
        END IF;

        SELECT COUNT(*)
          INTO backup_count
          FROM user_tables
         WHERE table_name = UPPER(backup_table_in);

        IF backup_count = 0 THEN
            EXECUTE IMMEDIATE
                'CREATE TABLE ' || UPPER(backup_table_in) ||
                ' AS SELECT * FROM ' || UPPER(source_table_in) || ' WHERE 1 = 0';
            DBMS_OUTPUT.PUT_LINE('Created ' || UPPER(backup_table_in));
        ELSE
            DBMS_OUTPUT.PUT_LINE('Kept existing ' || UPPER(backup_table_in));
        END IF;
    END;
BEGIN
    create_backup_table('T_PERMMASTER_NO', 'T_PERMMASTER_NO_BACKUP');
    create_backup_table('T_PERMDETAIL_NO', 'T_PERMDETAIL_NO_BACKUP');
    create_backup_table('T_PERMMASTER_SC', 'T_PERMMASTER_SC_BACKUP');
    create_backup_table('T_PERMDETAIL_SC', 'T_PERMDETAIL_SC_BACKUP');
END;
/

PROMPT ATFM revision backup tables are ready.

SELECT table_name
  FROM user_tables
 WHERE table_name IN (
       'T_PERMMASTER_NO_BACKUP',
       'T_PERMDETAIL_NO_BACKUP',
       'T_PERMMASTER_SC_BACKUP',
       'T_PERMDETAIL_SC_BACKUP'
 )
 ORDER BY table_name;

EXIT SUCCESS
