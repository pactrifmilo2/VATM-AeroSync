package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.worker.model.PermitFieldDiagnostic;
import vatm.aerosync.worker.model.WordPermitParseResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class PermitTrainingUsageService {

    private final PermitTrainingCandidateRepository repository;

    public PermitTrainingUsageService(
            PermitTrainingCandidateRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(WordPermitParseResult result) {
        List<PermitTrainingCandidate> candidates =
                repository.findByStatusAndProfileIdAndProfileVersion(
                        PermitTrainingStatus.APPROVED,
                        result.profileId(),
                        result.profileVersion());
        if (candidates.isEmpty()) {
            return;
        }
        LocalDateTime usedAt = LocalDateTime.now();
        candidates.stream()
                .filter(candidate -> candidate.getId() != null)
                .filter(candidate -> used(result, candidate))
                .map(PermitTrainingCandidate::getId)
                .distinct()
                .forEach(id -> repository.incrementUsage(
                        id,
                        PermitTrainingStatus.APPROVED,
                        usedAt));
    }

    private boolean used(
            WordPermitParseResult result,
            PermitTrainingCandidate candidate) {
        return result.fields().stream()
                .filter(field -> Objects.equals(
                        field.field(),
                        candidate.getSemanticField()))
                .map(PermitFieldDiagnostic::observedValue)
                .filter(Objects::nonNull)
                .map(PermitTextNormalizer::canonicalHeader)
                .anyMatch(candidate.getCanonicalAlias()::equals);
    }
}
