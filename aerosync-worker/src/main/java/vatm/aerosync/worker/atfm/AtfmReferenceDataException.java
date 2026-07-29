package vatm.aerosync.worker.atfm;

/**
 * Indicates that a permit cannot be written until its source data or ATFM
 * reference data is corrected. Retrying the same message cannot resolve it.
 */
public class AtfmReferenceDataException extends RuntimeException {

    public AtfmReferenceDataException(String message) {
        super(message);
    }
}
