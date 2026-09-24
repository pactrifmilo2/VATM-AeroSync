package vatm.aerosync.worker.atfm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.pipeline.PermitOperatorResolver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JdbcPermitOperatorResolver implements PermitOperatorResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcPermitOperatorResolver.class);
    private static final String FIND_BY_IATA_SQL = """
            SELECT OPER_ICAO, OPER_NAME
              FROM M_OPER
             WHERE UPPER(TRIM(OPER_IATA)) = ?
            """;
    private static final String FIND_BY_ICAO_SQL = """
            SELECT OPER_ICAO
              FROM M_OPER
             WHERE UPPER(TRIM(OPER_ICAO)) = ?
             FETCH FIRST 1 ROW ONLY
            """;
    private static final String HAS_IATA_SQL = """
            SELECT COUNT(*)
              FROM M_OPER
             WHERE UPPER(TRIM(OPER_ICAO)) = ?
               AND LENGTH(TRIM(OPER_IATA)) = 2
            """;
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "CO", "COMPANY", "CORP", "CORPORATION", "INC", "INCORPORATED",
            "JSC", "JOINT", "STOCK", "LTD", "LIMITED", "LLC", "LLP",
            "PLC", "PTE", "PTY", "SA", "SRL", "THE");

    private final AtfmDatabaseProperties properties;

    public JdbcPermitOperatorResolver(AtfmDatabaseProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> resolve(String iataCode, String carrierName) {
        String normalizedIata = code(iataCode);
        if (!normalizedIata.matches("[A-Z0-9]{2}")) {
            return Optional.empty();
        }

        List<OperatorCandidate> candidates;
        try {
            candidates = findCandidates(normalizedIata);
        } catch (SQLException exception) {
            LOGGER.warn("Could not resolve ATFM operator for IATA {}: {}",
                    normalizedIata, exception.getMessage());
            return Optional.empty();
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        String normalizedCarrierName = normalizeName(carrierName);
        // IATA codes are not globally unique enough for this import rule; the
        // document's airline name must also match M_OPER.OPER_NAME.
        if (normalizedCarrierName.isBlank()) {
            return Optional.empty();
        }

        Set<String> distinctIcaoCodes = candidates.stream()
                .map(OperatorCandidate::icaoCode)
                .collect(Collectors.toSet());
        if (distinctIcaoCodes.size() == 1) {
            OperatorCandidate only = candidates.getFirst();
            return similarityScore(normalizedCarrierName, normalizeName(only.operatorName())) > 0
                    ? Optional.of(distinctIcaoCodes.iterator().next())
                    : Optional.empty();
        }

        Map<String, Integer> bestScoreByIcao = new HashMap<>();
        for (OperatorCandidate candidate : candidates) {
            int score = similarityScore(normalizedCarrierName, normalizeName(candidate.operatorName()));
            if (score > 0) {
                bestScoreByIcao.merge(candidate.icaoCode(), score, Math::max);
            }
        }
        if (bestScoreByIcao.isEmpty()) {
            return Optional.empty();
        }
        int bestScore = bestScoreByIcao.values().stream().max(Comparator.naturalOrder()).orElse(0);
        List<String> bestMatches = bestScoreByIcao.entrySet().stream()
                .filter(entry -> entry.getValue() == bestScore)
                .map(Map.Entry::getKey)
                .toList();
        return bestMatches.size() == 1 ? Optional.of(bestMatches.getFirst()) : Optional.empty();
    }

    @Override
    public Optional<String> resolveFromCallsign(String callsign, String carrierName) {
        String source = callsign == null ? "" : callsign.trim().toUpperCase(Locale.ROOT);
        if (source.matches("^[A-Z]{1,2}-[A-Z0-9]{3,5}$")) {
            return Optional.empty();
        }
        String compact = code(callsign);
        if (compact.length() >= 3) {
            String icaoPrefix = compact.substring(0, 3);
            try (Connection connection = DriverManager.getConnection(
                    properties.getUrl(), properties.getUsername(), properties.getPassword());
                 PreparedStatement statement = connection.prepareStatement(FIND_BY_ICAO_SQL)) {
                statement.setString(1, icaoPrefix);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return Optional.of(icaoPrefix);
                    }
                }
            } catch (SQLException exception) {
                LOGGER.warn("Could not resolve ATFM operator for callsign {}: {}",
                        compact, exception.getMessage());
            }
        }
        return compact.length() >= 2
                ? resolve(compact.substring(0, 2), carrierName)
                : Optional.empty();
    }

    @Override
    public boolean hasIata(String icaoCode) {
        String normalizedIcao = code(icaoCode);
        if (!normalizedIcao.matches("[A-Z0-9]{3}") || "PRV".equals(normalizedIcao)) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
             PreparedStatement statement = connection.prepareStatement(HAS_IATA_SQL)) {
            statement.setString(1, normalizedIcao);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        } catch (SQLException exception) {
            LOGGER.warn("Could not verify IATA code for ICAO {}: {}",
                    normalizedIcao, exception.getMessage());
            return false;
        }
    }

    private List<OperatorCandidate> findCandidates(String iataCode) throws SQLException {
        List<OperatorCandidate> candidates = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
             PreparedStatement statement = connection.prepareStatement(FIND_BY_IATA_SQL)) {
            statement.setString(1, iataCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new OperatorCandidate(
                            validIcaoOrPrivate(resultSet.getString("OPER_ICAO")),
                            resultSet.getString("OPER_NAME")));
                }
            }
        }
        return candidates;
    }

    private String validIcaoOrPrivate(String value) {
        String normalized = code(value);
        return normalized.matches("[A-Z0-9]{3}") ? normalized : "PRV";
    }

    private int similarityScore(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0;
        }
        if (left.equals(right)) {
            return 100;
        }

        String simpleLeft = withoutLegalSuffixes(left);
        String simpleRight = withoutLegalSuffixes(right);
        if (!simpleLeft.isBlank() && simpleLeft.equals(simpleRight)) {
            return 95;
        }
        if (Math.min(simpleLeft.length(), simpleRight.length()) >= 8
                && (simpleLeft.contains(simpleRight) || simpleRight.contains(simpleLeft))) {
            return 85;
        }

        Set<String> leftTokens = tokens(simpleLeft);
        Set<String> rightTokens = tokens(simpleRight);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        double coverage = (double) intersection.size()
                / Math.max(leftTokens.size(), rightTokens.size());
        return coverage >= 0.75 ? 70 + (int) Math.round(coverage * 10) : 0;
    }

    private String normalizeName(String value) {
        String normalized = Normalizer.normalize(
                        value == null ? "" : value.replace('Đ', 'D').replace('đ', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("[^A-Z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private String withoutLegalSuffixes(String value) {
        return Arrays.stream(value.split("\\s+"))
                .filter(token -> !LEGAL_SUFFIXES.contains(token))
                .collect(Collectors.joining(" "));
    }

    private Set<String> tokens(String value) {
        return Arrays.stream(value.split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    private String code(String value) {
        return value == null
                ? ""
                : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private record OperatorCandidate(String icaoCode, String operatorName) {
    }
}
