WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
WHENEVER OSERROR EXIT FAILURE ROLLBACK
SET ECHO OFF
SET FEEDBACK ON
SET HEADING OFF
SET VERIFY OFF
SET SERVEROUTPUT ON

PROMPT Applying AeroSync runtime-config schema migration...

DECLARE
    object_count NUMBER;

    PROCEDURE create_table_if_missing(
        table_name_in VARCHAR2,
        table_ddl_in  VARCHAR2
    ) IS
        table_count NUMBER;
    BEGIN
        SELECT COUNT(*)
          INTO table_count
          FROM user_tables
         WHERE table_name = UPPER(table_name_in);

        IF table_count = 0 THEN
            EXECUTE IMMEDIATE table_ddl_in;
            DBMS_OUTPUT.PUT_LINE('Created table ' || UPPER(table_name_in));
        ELSE
            DBMS_OUTPUT.PUT_LINE('Kept existing table ' || UPPER(table_name_in));
        END IF;
    END;

    PROCEDURE add_column_if_missing(
        table_name_in  VARCHAR2,
        column_name_in VARCHAR2,
        column_ddl_in  VARCHAR2
    ) IS
        column_count NUMBER;
    BEGIN
        SELECT COUNT(*)
          INTO column_count
          FROM user_tab_columns
         WHERE table_name = UPPER(table_name_in)
           AND column_name = UPPER(column_name_in);

        IF column_count = 0 THEN
            EXECUTE IMMEDIATE column_ddl_in;
            DBMS_OUTPUT.PUT_LINE(
                'Added ' || UPPER(table_name_in) || '.' || UPPER(column_name_in));
        ELSE
            DBMS_OUTPUT.PUT_LINE(
                'Kept existing ' || UPPER(table_name_in) || '.' || UPPER(column_name_in));
        END IF;
    END;

    PROCEDURE create_index_if_missing(
        index_name_in VARCHAR2,
        index_ddl_in  VARCHAR2
    ) IS
        index_count NUMBER;
    BEGIN
        SELECT COUNT(*)
          INTO index_count
          FROM user_indexes
         WHERE index_name = UPPER(index_name_in);

        IF index_count = 0 THEN
            EXECUTE IMMEDIATE index_ddl_in;
            DBMS_OUTPUT.PUT_LINE('Created index ' || UPPER(index_name_in));
        ELSE
            DBMS_OUTPUT.PUT_LINE('Kept existing index ' || UPPER(index_name_in));
        END IF;
    END;
BEGIN
    SELECT COUNT(*)
      INTO object_count
      FROM user_tables
     WHERE table_name = 'RUNTIME_CONFIG';

    IF object_count = 0 THEN
        RAISE_APPLICATION_ERROR(
            -20091,
            'RUNTIME_CONFIG is missing. Run init-aerosync-oracle.sql before deploying.');
    END IF;

    add_column_if_missing(
        'runtime_config',
        'allowlist_initialized',
        'ALTER TABLE runtime_config ADD ' ||
        'allowlist_initialized NUMBER(1,0) DEFAULT 0 NOT NULL'
    );

    create_table_if_missing('runtime_config_allowlist', q'[
        CREATE TABLE runtime_config_allowlist (
            config_id  NUMBER(19,0) NOT NULL,
            sender     VARCHAR2(255 CHAR),
            CONSTRAINT fk_runtime_allowlist_config
                FOREIGN KEY (config_id) REFERENCES runtime_config(id)
        )
    ]');

    create_index_if_missing(
        'ix_runtime_allowlist_config',
        'CREATE INDEX ix_runtime_allowlist_config ' ||
        'ON runtime_config_allowlist(config_id)'
    );
END;
/

DECLARE
    valid_column_count NUMBER;
    valid_table_count  NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO valid_column_count
      FROM user_tab_columns
     WHERE table_name = 'RUNTIME_CONFIG'
       AND column_name = 'ALLOWLIST_INITIALIZED'
       AND data_type = 'NUMBER'
       AND data_precision = 1
       AND data_scale = 0
       AND nullable = 'N';

    SELECT COUNT(*)
      INTO valid_table_count
      FROM user_tab_columns
     WHERE table_name = 'RUNTIME_CONFIG_ALLOWLIST'
       AND (
            (column_name = 'CONFIG_ID' AND data_type = 'NUMBER'
             AND data_precision = 19 AND data_scale = 0 AND nullable = 'N')
         OR (column_name = 'SENDER' AND data_type = 'VARCHAR2'
             AND char_length = 255 AND char_used = 'C')
       );

    IF valid_column_count <> 1 OR valid_table_count <> 2 THEN
        RAISE_APPLICATION_ERROR(
            -20092,
            'Runtime allowlist schema validation failed after migration.');
    END IF;

    DBMS_OUTPUT.PUT_LINE('Validated runtime allowlist schema.');
END;
/

PROMPT AeroSync runtime-config schema migration completed.
EXIT SUCCESS
