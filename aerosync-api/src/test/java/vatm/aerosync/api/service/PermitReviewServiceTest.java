package vatm.aerosync.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.common.dto.PermitReviewFlightSnapshot;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermitReviewServiceTest {

    private PermitReviewRepository repository;
    private PermitReviewCommandPublisher publisher;
    private ObjectMapper objectMapper;
    private PermitReviewService service;
    private PermitReview review;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(PermitReviewRepository.class);
        publisher = mock(PermitReviewCommandPublisher.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        PlatformTransactionManager transactionManager = transactionManager();
        service = new PermitReviewService(repository, publisher, transactionManager);

        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(7L);
        PermitImport permitImport = mock(PermitImport.class);
        when(permitImport.getId()).thenReturn(8L);
        when(permitImport.getSyncJob()).thenReturn(job);
        when(permitImport.getNormalizedPermitId()).thenReturn("O/F 05199/S/CHK/2026");

        review = new PermitReview();
        ReflectionTestUtils.setField(review, "id", 4L);
        review.setPermitImport(permitImport);
        review.setStatus(PermitReviewStatus.PENDING);
        review.setOriginalPermitJson(objectMapper.writeValueAsString(snapshot("RMY685")));
        when(repository.findByIdForUpdate(4L)).thenReturn(Optional.of(review));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void correctionApprovalAndPublicationAreSeparateTransitions() {
        service.correct(4L, snapshot("RMY686"), "Fixed callsign", "operator.one");

        assertThat(review.getStatus()).isEqualTo(PermitReviewStatus.CORRECTED);
        assertThat(review.getCorrectedBy()).isEqualTo("operator.one");

        service.approve(4L, "Checked against source", "operator.two");

        assertThat(review.getStatus()).isEqualTo(PermitReviewStatus.APPROVED);
        assertThat(review.getApprovedBy()).isEqualTo("operator.two");

        service.requestPublish(4L, "admin.one");

        assertThat(review.getStatus()).isEqualTo(PermitReviewStatus.PUBLISHING);
        ArgumentCaptor<PermitReviewPublishCommand> command =
                ArgumentCaptor.forClass(PermitReviewPublishCommand.class);
        verify(publisher).publish(command.capture());
        assertThat(command.getValue().reviewId()).isEqualTo(4L);
        assertThat(command.getValue().requestedBy()).isEqualTo("admin.one");
    }

    private PermitReviewSnapshot snapshot(String flightNumber) {
        return new PermitReviewSnapshot(
                "OF-5199/7/2026VN",
                "O/F 05199/S/CHK/2026",
                "5199",
                "CHK",
                "O/F",
                "A",
                "S",
                LocalDate.of(2026, 7, 17),
                "RMY",
                "G17.44",
                72,
                "Cyberjaya",
                "SC",
                false,
                false,
                "raw",
                List.of(new PermitReviewFlightSnapshot(
                        "CAR",
                        1935L,
                        BigDecimal.ZERO,
                        flightNumber,
                        null,
                        "1000000",
                        "WMKK",
                        "VHHH",
                        "1140",
                        null,
                        "M765/M771",
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 7, 27),
                        "CAR 76X/32X",
                        "B76X")));
    }

    private PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
