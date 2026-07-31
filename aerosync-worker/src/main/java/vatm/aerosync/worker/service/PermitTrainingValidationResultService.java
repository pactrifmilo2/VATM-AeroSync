package vatm.aerosync.worker.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.entity.PermitTrainingDecision;
import vatm.aerosync.common.enums.PermitTrainingAction;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.common.repository.PermitTrainingDecisionRepository;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingValidationResultService {

    private final PermitTrainingCandidateRepository candidateRepository;
    private final PermitTrainingDecisionRepository decisionRepository;

    public PermitTrainingValidationResultService(
            PermitTrainingCandidateRepository candidateRepository,
            PermitTrainingDecisionRepository decisionRepository) {
        this.candidateRepository = candidateRepository;
        this.decisionRepository = decisionRepository;
    }

    @Transactional
    public void complete(
            Long candidateId,
            String actor,
            int corpusSize,
            int passedCount,
            int failedCount,
            String report) {
        PermitTrainingCandidate candidate = findForUpdate(candidateId);
        if (candidate.getValidationStatus()
                != PermitTrainingValidationStatus.RUNNING) {
            return;
        }
        boolean passed = corpusSize > 0
                && passedCount == corpusSize
                && failedCount == 0;
        candidate.setValidationStatus(
                passed
                        ? PermitTrainingValidationStatus.PASSED
                        : PermitTrainingValidationStatus.FAILED);
        candidate.setValidationCompletedAt(LocalDateTime.now());
        candidate.setValidationCorpusSize(corpusSize);
        candidate.setValidationPassedCount(passedCount);
        candidate.setValidationFailedCount(failedCount);
        candidate.setValidationReport(truncate(report, 4000));
        candidateRepository.save(candidate);
        record(
                candidate,
                passed
                        ? PermitTrainingAction.VALIDATION_PASSED
                        : PermitTrainingAction.VALIDATION_FAILED,
                actor,
                report);
    }

    @Transactional
    public void fail(Long candidateId, String actor, String message) {
        PermitTrainingCandidate candidate = findForUpdate(candidateId);
        if (candidate.getValidationStatus()
                != PermitTrainingValidationStatus.RUNNING) {
            return;
        }
        candidate.setValidationStatus(
                PermitTrainingValidationStatus.FAILED);
        candidate.setValidationCompletedAt(LocalDateTime.now());
        candidate.setValidationCorpusSize(0);
        candidate.setValidationPassedCount(0);
        candidate.setValidationFailedCount(0);
        candidate.setValidationReport(truncate(message, 4000));
        candidateRepository.save(candidate);
        record(
                candidate,
                PermitTrainingAction.VALIDATION_FAILED,
                actor,
                message);
    }

    private PermitTrainingCandidate findForUpdate(Long id) {
        return candidateRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training candidate not found: " + id));
    }

    private void record(
            PermitTrainingCandidate candidate,
            PermitTrainingAction action,
            String actor,
            String comment) {
        PermitTrainingDecision decision = new PermitTrainingDecision();
        decision.setCandidate(candidate);
        decision.setAction(action);
        decision.setActor(actor == null || actor.isBlank() ? "system" : actor);
        decision.setComment(truncate(comment, 2000));
        decisionRepository.save(decision);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
