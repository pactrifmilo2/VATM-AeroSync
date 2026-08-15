package vatm.aerosync.worker.pipeline;

/**
 * Signals that a Word attachment is an application/supporting document rather
 * than an issued permit. It is archived as skipped instead of reported as a
 * broken permit format.
 */
class NonPermitDocumentException extends RuntimeException {

    NonPermitDocumentException(String message) {
        super(message);
    }
}
