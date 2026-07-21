WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO OFF
SET FEEDBACK ON

DECLARE
    PROCEDURE add_column_if_missing(column_ddl VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE 'ALTER TABLE email_metadata ADD (' || column_ddl || ')';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -1430 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_column_if_missing('mailbox_folder VARCHAR2(255)');
    add_column_if_missing('uid_validity NUMBER(19)');
    add_column_if_missing('message_uid NUMBER(19)');
    add_column_if_missing('attachment_index NUMBER(10)');
    add_column_if_missing('attachment_name VARCHAR2(255)');
    add_column_if_missing('processing_status VARCHAR2(32) DEFAULT ''DISCOVERED'' NOT NULL');
    add_column_if_missing('acknowledgement_status VARCHAR2(32) DEFAULT ''PENDING'' NOT NULL');
    add_column_if_missing('ingest_complete NUMBER(1) DEFAULT 0 NOT NULL');
    add_column_if_missing('acknowledged_at TIMESTAMP(6)');
    add_column_if_missing('acknowledgement_error VARCHAR2(2000)');
END;
/

DECLARE
    constraint_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO constraint_count
      FROM user_constraints
     WHERE constraint_name = 'UK_EMAIL_METADATA_MESSAGE_ID';
    IF constraint_count > 0 THEN
        EXECUTE IMMEDIATE
            'ALTER TABLE email_metadata DROP CONSTRAINT uk_email_metadata_message_id';
    END IF;
END;
/

DECLARE
    constraint_count NUMBER;
BEGIN
    SELECT COUNT(*)
      INTO constraint_count
      FROM user_constraints
     WHERE constraint_name = 'UK_EMAIL_METADATA_MAILBOX_ATTACHMENT';
    IF constraint_count = 0 THEN
        EXECUTE IMMEDIATE
            'ALTER TABLE email_metadata ADD CONSTRAINT uk_email_metadata_mailbox_attachment ' ||
            'UNIQUE (mailbox_folder, uid_validity, message_uid, attachment_index)';
    END IF;
END;
/

PROMPT Phase 4 Oracle email lifecycle migration completed.
EXIT SUCCESS
