package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import vatm.aerosync.worker.model.*;
import vatm.aerosync.worker.atfm.*;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import java.nio.file.Path;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoRevisionReplacementMappingTest {
    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void mapsOldAndNewSeparatelyKeepingEtaAndLatestRouteForIdenticalSector() {
        SchedulePermit permit = parser.parseNoReplacement(document("LFPB", true), "OF-6916-REV1.docx");
        assertThat(permit.originalFlights()).hasSize(1);
        assertThat(permit.flights()).hasSize(1);
        assertThat(permit.originalFlights().getFirst().via()).isEqualTo("R474/W21");
        assertThat(permit.flights().getFirst().via()).isEqualTo("A1/W2");
        assertThat(permit.originalFlights().getFirst().eta()).isEqualTo("0655+");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("0830+");
        assertThat(permit.flights().getFirst().beginDate()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    void inheritsPurposeAndCraftButNeverOldScheduleOrOldSectorVia() {
        SchedulePermit mapped = parser.parseNoReplacement(document("LFLL", true), "OF-6916-REV1.docx");
        ScheduleFlight original = mapped.originalFlights().getFirst().withPurposeId("VIP")
                .withResolvedAircraft(321, new BigDecimal("42.5"));
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(mapped)).thenReturn(Optional.of(new AtfmRevisionBaseline(1, 2, "TBJ", List.of(original))));
        var context = context(mapped);
        new RevisionReconciliationStep(gateway).reconcile(context);
        var flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.flightNumber()).isEqualTo("TBJ90");
        assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(flight.endDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(flight.etd()).isEqualTo("2000");
        assertThat(flight.eta()).isEqualTo("0830+");
        assertThat(flight.toAirport()).isEqualTo("LFLL");
        assertThat(flight.via()).isEqualTo("A1/W2");
        assertThat(flight.purposeId()).isEqualTo("VIP");
        assertThat(flight.craftId()).isEqualTo(321);
        assertThat(flight.mtow()).isEqualByComparingTo("42.5");
    }

    @Test
    void explicitNewPurposeWinsAndOldPurposeIsNotCombined() {
        var entries = List.of(new PurposeResolutionStep.PurposeEntry("CAR", "Cargo"),
                new PurposeResolutionStep.PurposeEntry("VIP", "VIP"));
        String raw = "4. Purpose of Flight\n4.1 ORIGINAL\nPurpose of Flight: Cargo\n4.2 NEW\nPurpose of Flight: VIP\n5. Other details unchanged";
        var flight = parser.parseNoReplacement(document("LFLL", true), "OF-6916-REV1.docx").flights().getFirst();
        assertThat(PurposeResolutionStep.resolvePurpose(entries, RevisionDocumentText.newValues(raw), flight)).isEqualTo("VIP");
        assertThat(PurposeResolutionStep.hasExplicitNewPurpose(raw, flight)).isTrue();
        assertThat(PurposeResolutionStep.hasExplicitNewPurpose("Other details remain unchanged", flight)).isFalse();
    }

    @Test
    void shortNewPurposeLabelWinsAndRouteTextIsNotPurpose() {
        var flight = parser.parseNoReplacement(document("LFLL", true), "OF-6916-REV1.docx").flights().getFirst();
        var entries = List.of(new PurposeResolutionStep.PurposeEntry("CAR", "Cargo"),
                new PurposeResolutionStep.PurposeEntry("VIP", "VIP"));
        String text = "4. Purpose of Flight\n4.1 ORIGINAL: Cargo\n4.2 NEW: VIP\n5. Aircraft";
        assertThat(PurposeResolutionStep.resolvePurpose(entries, RevisionDocumentText.newValues(text), flight)).isEqualTo("VIP");
        assertThat(PurposeResolutionStep.hasExplicitNewPurpose("VHHH-LFLL: R474/W21", flight)).isFalse();
        assertThat(PurposeResolutionStep.hasExplicitNewPurpose("Purpose of Flight: UNCHANGED", flight)).isFalse();
    }

    @Test
    void realOf6916ReconcilesNewFieldsAndPreservesBaselineAttributes() throws Exception {
        String sample = System.getProperty("permit.of6916.sample");
        Assumptions.assumeTrue(sample != null, "Set permit.of6916.sample for real Word regression");
        Path path = Path.of(sample);
        SchedulePermit originalParse = parser.parse(path, path.getFileName().toString());
        SchedulePermit mapped = parser.parseNoReplacement(path);
        assertThat(mapped.originalFlights()).hasSize(1);
        assertThat(mapped.flights()).hasSize(1);
        var old = mapped.originalFlights().getFirst().withPurposeId("BUS").withResolvedAircraft(321, BigDecimal.TEN);
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(originalParse)).thenReturn(Optional.of(new AtfmRevisionBaseline(1, 2, "TBJ", List.of(old))));
        var context = context(originalParse); context.setFilePath(path);
        new RevisionReconciliationStep(gateway).reconcile(context);
        var flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.toAirport()).isEqualTo("LFLL");
        assertThat(flight.etd()).isEqualTo("2000");
        assertThat(flight.eta()).isEqualTo("0830+");
        assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(flight.via()).isEqualTo("R474/W21/B214");
        assertThat(flight.craftId()).isEqualTo(321);
        assertThat(flight.purposeId()).isEqualTo("BUS");
        assertThat(context.getSchedulePermit().originalFlights().getFirst().toAirport()).isEqualTo("LFPB");
    }

    @Test
    void legacyScheduledDestinationBypassesTheNoMapper() {
        var permit = parser.parseNoReplacement(document("LFLL", true), "OF-6916-REV1.docx");
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(permit)).thenReturn(Optional.of(new AtfmRevisionBaseline(1, 2, "TBJ",
                "O/F 06916/S/CHK/2026", permit.originalFlights())));
        var context = context(permit);
        new RevisionReconciliationStep(gateway).reconcile(context);
        assertThat(context.getSchedulePermit().usesScheduledStorage()).isTrue();
        assertThat(context.isNoReplacementMapped()).isFalse();
    }

    @Test
    void newAircraftTableWinsOverOldAircraftTable() {
        var base = document("LFLL", true);
        var tables = new java.util.ArrayList<>(base.tables());
        var contexts = new java.util.ArrayList<>(base.tableContexts());
        tables.add(List.of(List.of("Aircraft Type"), List.of("A320"))); contexts.add("5. AIRCRAFT\n5.1 ORIGINAL");
        tables.add(List.of(List.of("Aircraft Type"), List.of("GLEX"))); contexts.add("5.2 NEW");
        var doc = new WordPermitDocument(base.paragraphText(), "", base.rawContent(), tables, contexts);
        var mapped = parser.parseNoReplacement(doc, "OF-6916-REV1.docx");
        assertThat(mapped.flights().getFirst().sourceAircraftType()).isEqualTo("GLEX");
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(mapped)).thenReturn(Optional.of(new AtfmRevisionBaseline(1, 2, "TBJ",
                List.of(mapped.originalFlights().getFirst().withPurposeId("VIP").withResolvedAircraft(321, BigDecimal.TEN)))));
        var context = context(mapped);
        new RevisionReconciliationStep(gateway).reconcile(context);
        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isZero(); // resolve GLEX next
        assertThat(context.getSchedulePermit().flights().getFirst().sourceAircraftType()).isEqualTo("GLEX");
    }

    @Test
    void preservesPublishedDayFlagsRatherThanSilentlyCorrectingThem() {
        var base = document("LFLL", true);
        var tables = new java.util.ArrayList<>(base.tables());
        tables.set(1, List.of(tables.get(1).getFirst(),
                List.of("TBJ90", "27SEP26", "27SEP26", "-----6-", "VHHH", "2000", "LFLL", "0830+1")));
        var mapped = parser.parseNoReplacement(new WordPermitDocument(base.paragraphText(), "", base.rawContent(),
                tables, base.tableContexts()), "OF-6916-REV1.docx");
        assertThat(mapped.flights().getFirst().serviceDays()).isEqualTo("0000060");
        var context = context(mapped);
        assertThatThrownBy(() -> new BusinessRuleValidatorStep().validate(context)).hasMessageContaining("validation");
    }

    @Test
    void pipelineCanRecoverLegacyRouteFilterFailureButNeverForScDestination(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        var base = document("LFLL", true);
        // Legacy last route table only contains OLD, so its filter used to drop NEW.
        var tables = base.tables().subList(0, 3);
        var contexts = base.tableContexts().subList(0, 3);
        Path file = dir.resolve("OF-6916-REV1.docx");
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            word.createParagraph().createRun().setText("HA NOI, 24 SEP 2026");
            word.createParagraph().createRun().setText("PERMIT NUMBER OF-6916/9/2026VN /REV1");
            for (int i=0;i<tables.size();i++) {
                word.createParagraph().createRun().setText(contexts.get(i));
                var data=tables.get(i);var table=word.createTable(data.size(),data.getFirst().size());
                for(int r=0;r<data.size();r++)for(int c=0;c<data.get(r).size();c++)table.getRow(r).getCell(c).setText(data.get(r).get(c));
            }
            try(var out=java.nio.file.Files.newOutputStream(file)){word.write(out);}
        }
        var context = new ProcessingContext(new FileIngestedEvent(1L,file.toString(),"hash",FileSourceType.EMAIL,false));
        context.setFileType(vatm.aerosync.common.enums.FileType.DOCX);context.setFilePath(file);
        new ParserStep(new com.fasterxml.jackson.databind.ObjectMapper(), parser, new LegacyDocRevisionPermitParser(parser)).parse(context);
        assertThat(context.getLegacyWordParseFailure()).isNotNull();
        assertThat(context.getSchedulePermit().flights().getFirst().toAirport()).isEqualTo("LFLL");
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(context.getSchedulePermit())).thenReturn(Optional.of(
                new AtfmRevisionBaseline(1,2,"TBJ","O/F 06916/S/CHK/2026",List.of())));
        assertThatThrownBy(() -> new RevisionReconciliationStep(gateway).reconcile(context))
                .isSameAs(context.getLegacyWordParseFailure());
    }

    @Test
    void rejectsAmbiguousInheritanceRatherThanPairingMultipleChangedCallsignsByPosition() {
        var mapped = parser.parseNoReplacement(document("LFLL", true), "OF-6916-REV1.docx");
        var old = mapped.originalFlights().getFirst().withResolvedAircraft(321, BigDecimal.TEN);
        var new1 = mapped.flights().getFirst().withFlightNumber("TBJ91");
        var new2 = new1.withFlightNumber("TBJ92");
        var permit = mapped.withFlightsAndOriginals(List.of(new1,new2),List.of(old,old.withFlightNumber("TBJ93")));
        var gateway = mock(AtfmScheduleGateway.class);
        when(gateway.findRevisionBaseline(permit)).thenReturn(Optional.of(new AtfmRevisionBaseline(1,2,"TBJ",permit.originalFlights())));
        assertThatThrownBy(() -> new RevisionReconciliationStep(gateway).reconcile(context(permit)))
                .hasMessageContaining("BR-REVISION-ROW-MAPPING-AMBIGUOUS");
    }

    @Test
    void keepsProfileSelectionAndEtaPolicyForLegacyScParser() {
        var document = document("LFLL", true);
        var legacy = parser.parse(document, "OF-6916-REV1.docx");
        var replacement = parser.parseNoReplacement(document, "OF-6916-REV1.docx");
        assertThat(legacy.flights().getFirst().eta()).isEqualTo("0830+");
        assertThat(replacement.flights().getFirst().eta()).isEqualTo("0830+");
    }

    @Test
    void newRowAircraftIsNotOverriddenByCommonAuxiliaryAircraft() {
        var base = document("LFLL", true);
        var tables = new java.util.ArrayList<>(base.tables());
        var contexts = new java.util.ArrayList<>(base.tableContexts());
        var header = new java.util.ArrayList<>(tables.get(1).getFirst()); header.add("Aircraft Type");
        var row = new java.util.ArrayList<>(tables.get(1).get(1)); row.add("GLEX");
        tables.set(1, List.of(header,row));
        tables.add(List.of(List.of("Aircraft Type"), List.of("A320"))); contexts.add("5. Aircraft");
        var mapped = parser.parseNoReplacement(new WordPermitDocument(base.paragraphText(), "", base.rawContent(),
                tables, contexts), "OF-6916-REV1.docx");
        assertThat(mapped.flights().getFirst().sourceAircraftType()).isEqualTo("GLEX");
    }

    private ProcessingContext context(SchedulePermit permit) {
        var context = new ProcessingContext(new FileIngestedEvent(1L, "OF-6916-REV1.docx", "hash", FileSourceType.EMAIL, false));
        context.setSchedulePermit(permit); return context;
    }

    private WordPermitDocument document(String destination, boolean routes) {
        String text = "HA NOI, 24 SEP 2026\nPERMIT NUMBER OF-6916/9/2026VN /REV1\n"
                + "NAME: TAG AVIATION ASIA LTD\n3. SCHEDULES: UTC TIME\n3.1 ORIGINAL\n3.2 NEW\n"
                + "4. AIRWAYS\n4.1 ORIGINAL\n4.2 NEW\nOTHER DETAILS REMAIN UNCHANGED.";
        var headers = List.of("FLIGHT NUMBER", "EFFECTIVE FROM", "EFFECTIVE TO", "DAYS OF SERVICES",
                "DEPARTURE AIRPORT", "EOBT", "ARRIVAL AIRPORT", "EIBT");
        return new WordPermitDocument(text, "", text, List.of(
                List.of(headers, List.of("TBJ90", "26SEP26", "26SEP26", "-----6-", "VHHH", "1800", "LFPB", "0655+1")),
                List.of(headers, List.of("TBJ90", "27SEP26", "27SEP26", "------7", "VHHH", "2000", destination, "0830+1")),
                List.of(List.of("SECTOR", "AIRWAYS"), List.of("VHHH-LFPB", "R474-W21")),
                List.of(List.of("SECTOR", "AIRWAYS"), List.of("VHHH-" + destination, "A1-W2"))),
                List.of("3. SCHEDULES: UTC TIME\n3.1 ORIGINAL", "3.2 NEW", "4. AIRWAYS\n4.1 ORIGINAL", "4.2 NEW"));
    }
}
