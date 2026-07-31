package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermitSemanticExtractorTest {

    private final PermitSemanticExtractor extractor = new PermitSemanticExtractor();

    @Test
    void extract_ranksHeaderIdentityAndClassifiesCommonVietnameseSections() {
        String paragraphs = String.join("\n",
                "Reference LD-2372/6/2026VN",
                "H\u00c0 N\u1ed8I, NG\u00c0Y 03/7/2026",
                "LD- 11112/7/2026VN",
                "2.1. L\u1ecaCH BAY G\u1ed0C",
                "2.2. L\u1ecaCH BAY M\u1edaI",
                "2.5. CHUY\u1ebeN B\u1ed4 SUNG");
        List<List<List<String>>> tables = List.of(
                List.of(List.of(
                        "M\u00e3 IATA (n\u1ebfu c\u00f3): VN",
                        "M\u00e3 ICAO (n\u1ebfu c\u00f3): HVN",
                        "\u0110\u1ecba ch\u1ec9 b\u01b0u \u0111i\u1ec7n: "
                                + "200 Ph\u1ed1 Nguy\u1ec5n S\u01a1n")),
                List.of(List.of("Flight number"), List.of("VN100")),
                List.of(List.of("Flight number"), List.of("VN1466")),
                List.of(List.of("Flight number"), List.of("VN7180")));
        List<String> contexts = List.of(
                "Header",
                "2.1. L\u1ecaCH BAY G\u1ed0C",
                "2.2. L\u1ecaCH BAY M\u1edaI",
                "2.5. CHUY\u1ebeN B\u1ed4 SUNG");
        String tableText = tables.stream()
                .flatMap(List::stream)
                .flatMap(List::stream)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        WordPermitDocument document = new WordPermitDocument(
                paragraphs,
                tableText,
                paragraphs + "\n" + tableText,
                tables,
                contexts);

        PermitSemanticEvidence evidence = extractor.extract(document);

        assertThat(evidence.permitIdentities()).isNotEmpty();
        assertThat(evidence.permitIdentities().getFirst().rawValue())
                .isEqualTo("LD- 11112/7/2026VN");
        assertThat(evidence.permitIdentities().getFirst().confidence()).isEqualTo(0.99);
        assertThat(evidence.permitDate().value()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(evidence.permitDate().method()).isEqualTo("DATE_NEAR_LABEL");
        assertThat(evidence.operatorIcao().value()).isEqualTo("HVN");
        assertThat(evidence.operatorIata().value()).isEqualTo("VN");
        assertThat(evidence.billingAddress().value())
                .isEqualTo("200 Ph\u1ed1 Nguy\u1ec5n S\u01a1n");
        assertThat(evidence.tableRole(1).role())
                .isEqualTo(PermitSemanticEvidence.TableRole.ORIGINAL);
        assertThat(evidence.tableRole(2).role())
                .isEqualTo(PermitSemanticEvidence.TableRole.REPLACEMENT);
        assertThat(evidence.tableRole(3).role())
                .isEqualTo(PermitSemanticEvidence.TableRole.SUPPLEMENTAL);
    }
}
