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
        table_ddl CLOB;
        ddl_cursor INTEGER;
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
            DBMS_METADATA.SET_TRANSFORM_PARAM(
                DBMS_METADATA.SESSION_TRANSFORM, 'CONSTRAINTS', FALSE);
            DBMS_METADATA.SET_TRANSFORM_PARAM(
                DBMS_METADATA.SESSION_TRANSFORM, 'REF_CONSTRAINTS', FALSE);
            DBMS_METADATA.SET_TRANSFORM_PARAM(
                DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', FALSE);
            DBMS_METADATA.SET_TRANSFORM_PARAM(
                DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
            DBMS_METADATA.SET_TRANSFORM_PARAM(
                DBMS_METADATA.SESSION_TRANSFORM, 'TABLESPACE', FALSE);

            table_ddl := DBMS_METADATA.GET_DDL(
                'TABLE', UPPER(source_table_in), USER);
            table_ddl := REPLACE(
                table_ddl,
                '"' || UPPER(source_table_in) || '"',
                '"' || UPPER(backup_table_in) || '"');

            IF DBMS_LOB.INSTR(
                    table_ddl,
                    '"' || UPPER(backup_table_in) || '"') = 0 THEN
                RAISE_APPLICATION_ERROR(
                    -20002,
                    'Could not derive backup DDL for ' || UPPER(source_table_in));
            END IF;

            ddl_cursor := DBMS_SQL.OPEN_CURSOR;
            BEGIN
                DBMS_SQL.PARSE(ddl_cursor, table_ddl, DBMS_SQL.NATIVE);
                DBMS_SQL.CLOSE_CURSOR(ddl_cursor);
            EXCEPTION
                WHEN OTHERS THEN
                    IF DBMS_SQL.IS_OPEN(ddl_cursor) THEN
                        DBMS_SQL.CLOSE_CURSOR(ddl_cursor);
                    END IF;
                    RAISE;
            END;
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
