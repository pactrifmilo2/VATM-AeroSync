package vatm.aerosync.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmPermitSnapshot;
import vatm.aerosync.worker.atfm.AtfmWriteResult;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitImportCoordinatorTest {

    @Mock
    private PermitImportRepository permitImportRepository;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private PermitSemanticHasher semanticHasher;
    @Mock
    private AtfmScheduleGateway atfmScheduleGateway;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AtfmDatabaseProperties properties;
    private PermitImportCoordinator coordinator;
    private SyncJob job;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        properties = new AtfmDatabaseProperties();
        properties.setWriteEnabled(true);
        properties.setPermitLockSeconds(600);
        coordinator = new PermitImportCoordinator(
                permitImportRepository,
                syncJobRepository,
                semanticHasher,
                atfmScheduleGateway,
                properties,
                redisTemplate);
        job = org.mockito.Mockito.mock(SyncJob.class);
        when(job.getFileHash()).thenReturn("f".repeat(64));
        when(syncJobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(semanticHasher.hash(any())).thenReturn("s".repeat(64));
        when(permitImportRepository.findBySyncJobId(7L)).thenReturn(Optional.empty());
        when(permitImportRepository.save(any(PermitImport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), eq("7"), any(Duration.class)))
                .thenReturn(true);
        context = context();
    }

    @Test
    void importPermit_insertsAndRecordsGeneratedKeys() {
        when(atfmScheduleGateway.findNewPermitInEitherStore(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.insert(any())).thenReturn(new AtfmWriteResult(203001L, 202510L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(outcome.targetMasterId()).isEqualTo(203001L);
        assertThat(outcome.targetPermId()).isEqualTo(202510L);
        assertThat(outcome.detailCount()).isOne();
        verify(redisTemplate).delete("aerosync:permit-lock:O/F 05199/S/CHK/2026");
    }

    @Test
    void importPermit_skipsSameSemanticPermit() {
        when(atfmScheduleGateway.findNewPermitInEitherStore(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(203001L, 202510L, true)));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.DUPLICATE);
        assertThat(outcome.targetPermId()).isEqualTo(202510L);
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_skipsChangedNewPermitInsteadOfUpdating() {
        when(atfmScheduleGateway.findNewPermitInEitherStore(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(203001L, 202510L, false)));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.DUPLICATE);
        assertThat(outcome.detailCount()).isZero();
        assertThat(outcome.targetMasterId()).isEqualTo(203001L);
        assertThat(outcome.targetPermId()).isEqualTo(202510L);
        verify(atfmScheduleGateway, never()).update(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_updatesConfiguredRevisionAfterAtfmComparison() {
        properties.setManualReviewEnabled(false);
        context.setSchedulePermit(reviewOnlyPermit());
        when(atfmScheduleGateway.findExisting(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(203001L, 202510L, false)));
        when(atfmScheduleGateway.update(any()))
                .thenReturn(new AtfmWriteResult(203001L, 202510L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(outcome.targetPermId()).isEqualTo(202510L);
        verify(atfmScheduleGateway).findExisting(any());
        verify(atfmScheduleGateway).update(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_quarantinesNonSeasonalRevisionWhenNormalizedIdContainsSeason() {
        properties.setManualReviewEnabled(false);
        context.setSchedulePermit(reviewOnlyPermit());
        when(atfmScheduleGateway.findExisting(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Original ATFM permit not found");

        verify(atfmScheduleGateway, never()).insert(any());
        verify(atfmScheduleGateway, never()).update(any());
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.REVISION_REVIEW));
    }

    @Test
    void importPermit_doesNotInsertNonScheduledRevisionWhenBaseIsMissing() {
        context.setSchedulePermit(nonScheduledRevisionPermit());
        when(atfmScheduleGateway.findExisting(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Original ATFM permit not found");

        verify(atfmScheduleGateway, never()).insert(any());
        verify(atfmScheduleGateway, never()).update(any());
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.REVISION_REVIEW));
    }

    @Test
    void importPermit_reportsWrongStoreWhenMultiDayRevisionBaseIsInNoTables() {
        context.setSchedulePermit(multiDayNonScheduledRevisionPermit());
        when(atfmScheduleGateway.findExisting(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.findCrossStoreRevisionBaseline(any()))
                .thenReturn(Optional.of(new vatm.aerosync.worker.atfm.AtfmRevisionBaseline(
                        5001L, 7001L, "CKS", List.of())));

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-REVISION-BASE-WRONG-STORE")
                .hasMessageContaining("different storage table");

        verify(atfmScheduleGateway, never()).insert(any());
        verify(atfmScheduleGateway, never()).update(any());
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.REVISION_REVIEW
                        && attempt.getErrorMessage() != null));
    }

    @Test
    void importPermit_keepsNotFoundMessageWhenBaseIsMissingFromBothStores() {
        context.setSchedulePermit(multiDayNonScheduledRevisionPermit());
        when(atfmScheduleGateway.findExisting(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.findCrossStoreRevisionBaseline(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Original ATFM permit not found");

        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.REVISION_REVIEW));
    }

    @Test
    void importPermit_locksAndTracksReferencedRevisionTarget() {
        context.setSchedulePermit(referencedRevisionPermit());
        when(atfmScheduleGateway.findExisting(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(4167L, 9001L, false)));
        when(atfmScheduleGateway.update(any()))
                .thenReturn(new AtfmWriteResult(4167L, 9001L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        verify(valueOperations).setIfAbsent(
                eq("aerosync:permit-lock:LD 0068A/S/CHK/2026"),
                eq("7"), any(Duration.class));
        verify(atfmScheduleGateway).update(argThat(permit ->
                permit.atfmTargetPermitId().equals("LD 0068A/S/CHK/2026")
                        && permit.normalizedPermitId().equals("O/F 05199/S/CHK/2026")));
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                "LD 0068A/S/CHK/2026".equals(attempt.getNormalizedPermitId())));
    }

    @Test
    void importPermit_blocksReviewOnlyPermitWhenManualReviewIsEnabled() {
        context.setSchedulePermit(nonRevisionReviewOnlyPermit());

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PERMIT-MANUAL-REVIEW");
        verify(atfmScheduleGateway, never()).findExisting(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_blocksReviewOnlyRevisionBeforeAtfmWrite() {
        context.setSchedulePermit(reviewOnlyPermit());
        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PERMIT-MANUAL-REVIEW");
        verify(atfmScheduleGateway, never()).findExisting(any());
        verify(atfmScheduleGateway, never()).update(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_doesNotInsertSubjectCorrectionWhenTargetMasterIsMissing() {
        SchedulePermit correction = new SchedulePermit(
                "LD-5199/7/2026VN", "LD 05199/S/CHK/2026", "5199",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 17),
                "RMY", "CORRECT", 72, "Cyberjaya", "SC",
                false, false, false, "corrected content", permit().flights(), List.of(),
                null, false, SchedulePermit.SUBJECT_CORRECTION);
        context.setSchedulePermit(correction);
        when(atfmScheduleGateway.findExisting(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Subject correction master not found");
        verify(atfmScheduleGateway, never()).insert(any());
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.REVISION_REVIEW));
    }

    @Test
    void importPermit_writesReviewOnlyPermitWhenManualReviewIsDisabled() {
        properties.setManualReviewEnabled(false);
        context.setSchedulePermit(nonRevisionReviewOnlyPermit());
        when(atfmScheduleGateway.findNewPermitInEitherStore(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.insert(any())).thenReturn(new AtfmWriteResult(203001L, 202510L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        verify(atfmScheduleGateway).insert(any());
    }

    @Test
    void importPermit_insertsWhenAtfmTargetIsMissing() {
        when(atfmScheduleGateway.findNewPermitInEitherStore(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.insert(any())).thenReturn(new AtfmWriteResult(303001L, 302510L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(outcome.targetMasterId()).isEqualTo(303001L);
        verify(atfmScheduleGateway).insert(any());
    }

    @Test
    void importPermit_dryRunDoesNotConnectToTarget() {
        properties.setWriteEnabled(false);

        assertThatThrownBy(() -> coordinator.importPermit(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ATFM-WRITE-DISABLED");
        verify(atfmScheduleGateway, never()).findExisting(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    @Test
    void importPermit_refreshesTrackingWhenReusingAResetAttempt() {
        PermitImport resetAttempt = imported("x".repeat(64), 203001L, 202510L);
        resetAttempt.setStatus(PermitImportStatus.RESERVED);
        resetAttempt.setNormalizedPermitId("OLD-ID");
        resetAttempt.setErrorMessage("old failure");
        when(permitImportRepository.findBySyncJobId(7L)).thenReturn(Optional.of(resetAttempt));
        when(atfmScheduleGateway.findNewPermitInEitherStore(any())).thenReturn(Optional.empty());
        when(atfmScheduleGateway.insert(any())).thenReturn(new AtfmWriteResult(303001L, 302510L, 1));

        PermitImportOutcome outcome = coordinator.importPermit(context);

        assertThat(outcome.status()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(resetAttempt.getNormalizedPermitId()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(resetAttempt.getSemanticHash()).isEqualTo("s".repeat(64));
        assertThat(resetAttempt.getErrorMessage()).isNull();
    }

    private PermitImport imported(String hash, long masterId, long permId) {
        PermitImport permitImport = new PermitImport();
        permitImport.setNormalizedPermitId("O/F 05199/S/CHK/2026");
        permitImport.setSemanticHash(hash);
        permitImport.setSourceFileHash("f".repeat(64));
        permitImport.setStatus(PermitImportStatus.SAVED);
        permitImport.setTargetMasterId(masterId);
        permitImport.setTargetPermId(permId);
        permitImport.setDetailCount(1);
        return permitImport;
    }

    @Test
    void failedAttemptRetainsRecoveryUpdatePath() {
        PermitImport failed = imported("old", 1L, 2L);
        failed.setStatus(PermitImportStatus.FAILED);
        when(permitImportRepository.findBySyncJobId(7L)).thenReturn(Optional.of(failed));
        when(atfmScheduleGateway.findExisting(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(1L, 2L, false)));
        when(atfmScheduleGateway.update(any())).thenReturn(new AtfmWriteResult(1L, 2L, 1));
        assertThat(coordinator.importPermit(context).status()).isEqualTo(PermitImportStatus.SAVED);
        verify(atfmScheduleGateway, never()).findNewPermitInEitherStore(any());
        verify(atfmScheduleGateway).update(any());
    }

    @Test
    void duplicateNewPermitPersistsErrorWithZeroWrittenRows() {
        when(atfmScheduleGateway.findNewPermitInEitherStore(any()))
                .thenReturn(Optional.of(new AtfmPermitSnapshot(1L, 2L, false)));
        assertThat(coordinator.importPermit(context).detailCount()).isZero();
        verify(permitImportRepository, atLeastOnce()).save(argThat(attempt ->
                attempt.getStatus() == PermitImportStatus.DUPLICATE
                        && "Ph\u00e9p n\u00e0y \u0111\u00e3 t\u1ed3n t\u1ea1i r\u1ed3i.".equals(attempt.getErrorMessage())));
        verify(atfmScheduleGateway, never()).update(any());
        verify(atfmScheduleGateway, never()).insert(any());
    }

    private ProcessingContext context() {
        ProcessingContext processingContext = new ProcessingContext(
                new FileIngestedEvent(7L, "C:/staging/permit.docx", "f".repeat(64),
                        FileSourceType.EMAIL, false));
        processingContext.setSchedulePermit(permit());
        return processingContext;
    }

    private SchedulePermit permit() {
        ScheduleFlight flight = new ScheduleFlight(
                "CAR", 1935L, BigDecimal.ZERO, "RMY685", null, "1000000",
                "WMKK", "VHHH", "1140", null, "M765/M771",
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27), "CAR 76X/32X");
        return new SchedulePermit(
                "OF-5199/7/2026VN", "O/F 05199/S/CHK/2026", "5199",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 7, 17),
                "RMY", "G17.44", 72, "Cyberjaya", "SC", "raw", List.of(flight));
    }

    private SchedulePermit reviewOnlyPermit() {
        SchedulePermit permit = permit();
        return new SchedulePermit(
                permit.sourcePermitNumber(), permit.normalizedPermitId(), permit.permitNumber(),
                permit.authorId(), permit.permitType(), permit.version(), permit.season(),
                permit.permitDate(), permit.operatorId(), permit.reference(), permit.validHours(),
                permit.billingAddress(), permit.flightType(), permit.iataAirportsAllowed(),
                permit.emptyAirwaysAllowed(), true, "REV1\n" + permit.rawContent(), permit.flights());
    }

    private SchedulePermit nonRevisionReviewOnlyPermit() {
        SchedulePermit permit = permit();
        return new SchedulePermit(
                permit.sourcePermitNumber(), permit.normalizedPermitId(), permit.permitNumber(),
                permit.authorId(), permit.permitType(), permit.version(), permit.season(),
                permit.permitDate(), permit.operatorId(), permit.reference(), permit.validHours(),
                permit.billingAddress(), permit.flightType(), permit.iataAirportsAllowed(),
                permit.emptyAirwaysAllowed(), true, permit.rawContent(), permit.flights());
    }

    private SchedulePermit nonScheduledRevisionPermit() {
        ScheduleFlight revised = new ScheduleFlight(
                "CAR", 1935L, BigDecimal.ZERO, "CKS9716", null, "0000007",
                "WMKP", "VVNB", "2130", "0100", "R474",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 16), "CAR B744");
        return new SchedulePermit(
                "LD-2797/07/2026VN/REV1", "LD 02797/CHK/2026", "2797",
                "CHK", "LD", "A", null, LocalDate.of(2026, 8, 15),
                "CKS", null, 24, "KALITTA AIR, LLC", "NO",
                false, false, false,
                "REV1\nNEW SCHEDULE", List.of(revised), List.of());
    }

    private SchedulePermit multiDayNonScheduledRevisionPermit() {
        ScheduleFlight revised = new ScheduleFlight(
                "CAR", 1935L, BigDecimal.ZERO, "CKS9716", null, "1234567",
                "WMKP", "VVNB", "2130", "0100", "R474",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 30), "CAR B744");
        return new SchedulePermit(
                "LD-2797/07/2026VN/REV1", "LD 02797/CHK/2026", "2797",
                "CHK", "LD", "A", null, LocalDate.of(2026, 8, 15),
                "CKS", null, 24, "KALITTA AIR, LLC", "NO",
                false, false, false,
                "REV1\nNEW SCHEDULE", List.of(revised), List.of());
    }

    private SchedulePermit referencedRevisionPermit() {
        SchedulePermit permit = permit();
        return new SchedulePermit(
                permit.sourcePermitNumber(), permit.normalizedPermitId(), permit.permitNumber(),
                permit.authorId(), permit.permitType(), permit.version(), permit.season(),
                permit.permitDate(), permit.operatorId(), "LD-68/A/S/2026VN",
                permit.validHours(), permit.billingAddress(), permit.flightType(),
                permit.iataAirportsAllowed(), permit.emptyAirwaysAllowed(), false,
                "ORIGINAL SCHEDULE\n" + permit.rawContent(), permit.flights(), List.of(),
                "LD 0068A/S/CHK/2026");
    }
}
