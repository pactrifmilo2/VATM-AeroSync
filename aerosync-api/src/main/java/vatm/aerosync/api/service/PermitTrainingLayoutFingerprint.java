package vatm.aerosync.api.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.training.PermitTrainingLayoutFingerprinter;

@Component
public class PermitTrainingLayoutFingerprint {

    public String fingerprint(PermitTrainingDocument document) {
        return PermitTrainingLayoutFingerprinter.fingerprint(document);
    }
}
