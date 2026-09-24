WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET ECHO OFF
SET FEEDBACK ON
SET SERVEROUTPUT ON

DECLARE
    PROCEDURE add_purpose_token(p_code VARCHAR2, p_token VARCHAR2) IS
        current_name M_FLY_PURPOSE.PURPOSE_NAME%TYPE;
    BEGIN
        SELECT PURPOSE_NAME
          INTO current_name
          FROM M_FLY_PURPOSE
         WHERE PURPOSE_CODE = p_code
         FOR UPDATE;

        IF INSTR(';' || UPPER(current_name) || ';', ';' || UPPER(p_token) || ';') = 0 THEN
            IF LENGTH(current_name) + LENGTH(p_token) + 2 > 100 THEN
                RAISE_APPLICATION_ERROR(
                    -20081,
                    'PURPOSE_NAME is too short for token ' || p_code || ': ' || p_token);
            END IF;
            UPDATE M_FLY_PURPOSE
               SET PURPOSE_NAME = RTRIM(current_name) || '; ' || p_token
             WHERE PURPOSE_CODE = p_code;
        END IF;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20082, 'Missing M_FLY_PURPOSE code: ' || p_code);
    END;
BEGIN
    -- Keep the original Vietnamese label and append searchable Vietnamese and
    -- English phrases. The parser reads every semicolon-separated token.
    add_purpose_token('PAX', UNISTR('ch\1EDF kh\00E1ch'));
    add_purpose_token('PAX', 'transporting passengers');
    add_purpose_token('PAX', 'passenger');
    add_purpose_token('PAX', 'passengers');

    add_purpose_token('CAR', UNISTR('ch\1EDF h\00E0ng'));
    add_purpose_token('CAR', 'transporting cargo');
    add_purpose_token('CAR', 'cargo');
    add_purpose_token('CAR', 'freighter');

    add_purpose_token('FER', 'ferry');
    add_purpose_token('FER', 'ferry flight');
    add_purpose_token('FER', UNISTR('chuy\1EBFn s\00E2n'));

    add_purpose_token('BUS', 'business flight');
    add_purpose_token('BUS', 'business');

    add_purpose_token('CHT', 'charter');
    add_purpose_token('CHT', 'charter flight');

    add_purpose_token('OTH', 'other');
    add_purpose_token('OTH', 'technical stop');
    add_purpose_token('OTH', 'refuel');

    add_purpose_token('TECH', UNISTR('h\1EA1 c\00E1nh k\1EF9 thu\1EADt'));
    add_purpose_token('TECH', 'technical landing');

    INSERT INTO M_CRAFT_TYPE (MA, SOHIEU, SOHIEU2, TAITRONG)
    SELECT 'A000', 'A000', NULL, 0
      FROM DUAL
     WHERE NOT EXISTS (
               SELECT 1
                 FROM M_CRAFT_TYPE
                WHERE UPPER(TRIM(MA)) = 'A000'
                   OR UPPER(TRIM(SOHIEU)) = 'A000'
                   OR UPPER(TRIM(SOHIEU2)) = 'A000');

    COMMIT;
    DBMS_OUTPUT.PUT_LINE('ATFM purpose synonyms and fallback aircraft A000 are ready.');
END;
/

SELECT PURPOSE_CODE, PURPOSE_NAME
  FROM M_FLY_PURPOSE
 WHERE PURPOSE_CODE IN ('PAX', 'CAR', 'FER', 'BUS', 'CHT', 'OTH', 'TECH')
 ORDER BY PURPOSE_CODE;

SELECT CRAFT_ID, MA, SOHIEU, SOHIEU2, TAITRONG
  FROM M_CRAFT_TYPE
 WHERE UPPER(TRIM(MA)) = 'A000'
    OR UPPER(TRIM(SOHIEU)) = 'A000'
    OR UPPER(TRIM(SOHIEU2)) = 'A000';

EXIT SUCCESS
