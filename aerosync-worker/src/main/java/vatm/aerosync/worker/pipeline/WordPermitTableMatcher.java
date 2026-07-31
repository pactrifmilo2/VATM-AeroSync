package vatm.aerosync.worker.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WordPermitTableMatcher {

    private static final int MAX_HEADER_ROWS = 3;
    private static final double FUZZY_MATCH_THRESHOLD = 0.90;

    private WordPermitTableMatcher() {
    }

    static TableMatch find(List<List<List<String>>> tables,
                           List<String> tableContexts,
                           Map<String, List<String>> aliases,
                           Map<String, List<String>> declaredAliases,
                           List<String> requiredColumns,
                           List<String> excludedColumns,
                           List<String> contextPatterns,
                           boolean lastMatchingTable) {
        List<TableMatch> matches = findAll(
                tables, tableContexts, aliases, declaredAliases,
                requiredColumns, excludedColumns, contextPatterns);
        if (matches.isEmpty()) {
            return null;
        }
        return lastMatchingTable ? matches.getLast() : matches.getFirst();
    }

    static List<TableMatch> findAll(List<List<List<String>>> tables,
                                    List<String> tableContexts,
                                    Map<String, List<String>> aliases,
                                    Map<String, List<String>> declaredAliases,
                                    List<String> requiredColumns,
                                    List<String> excludedColumns,
                                    List<String> contextPatterns) {
        List<TableMatch> matches = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<List<String>> table = tables.get(tableIndex);
            if (table.size() < 2 || !contextMatches(tableIndex, tableContexts, contextPatterns)) {
                continue;
            }
            TableMatch match = bestMatch(
                    tableIndex, table, aliases, declaredAliases, requiredColumns, excludedColumns);
            if (match != null) {
                matches.add(match);
            }
        }
        return List.copyOf(matches);
    }

    static ColumnResolution resolveSingleHeader(List<String> header,
                                                Map<String, List<String>> aliases) {
        List<List<String>> table = List.of(header, List.of());
        return resolve(table, 1, aliases, aliases);
    }

    private static TableMatch bestMatch(int tableIndex,
                                        List<List<String>> table,
                                        Map<String, List<String>> aliases,
                                        Map<String, List<String>> declaredAliases,
                                        List<String> requiredColumns,
                                        List<String> excludedColumns) {
        List<TableMatch> candidates = new ArrayList<>();
        int maximumDepth = Math.min(MAX_HEADER_ROWS, table.size() - 1);
        for (int headerRows = 1; headerRows <= maximumDepth; headerRows++) {
            ColumnResolution resolution = resolve(table, headerRows, aliases, declaredAliases);
            if (!resolution.columns().keySet().containsAll(safeList(requiredColumns))
                    || safeList(excludedColumns).stream()
                    .anyMatch(resolution.columns()::containsKey)) {
                continue;
            }
            candidates.add(new TableMatch(
                    tableIndex,
                    table,
                    headerRows,
                    resolution.columns(),
                    resolution.matches()));
        }
        return candidates.stream()
                .max(Comparator
                        .comparingInt((TableMatch match) -> match.columns().size())
                        .thenComparingDouble(TableMatch::minimumConfidence)
                        .thenComparingInt(match -> -match.headerRows()))
                .orElse(null);
    }

    private static ColumnResolution resolve(List<List<String>> table,
                                            int headerRows,
                                            Map<String, List<String>> aliases,
                                            Map<String, List<String>> declaredAliases) {
        int columnCount = 0;
        for (int row = 0; row < headerRows; row++) {
            columnCount = Math.max(columnCount, table.get(row).size());
        }

        List<MatchCandidate> candidates = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            int columnIndex = column;
            List<String> variants = headerVariants(table, headerRows, column);
            aliases.forEach((semantic, configuredAliases) -> {
                MatchCandidate candidate = bestCandidate(
                        semantic,
                        columnIndex,
                        variants,
                        configuredAliases,
                        declaredAliases.getOrDefault(semantic, List.of()));
                if (candidate != null) {
                    candidates.add(candidate);
                }
            });
        }

        candidates.sort(Comparator
                .comparingDouble(MatchCandidate::confidence)
                .reversed()
                .thenComparing(candidate -> candidate.kind().ordinal())
                .thenComparing(MatchCandidate::semantic)
                .thenComparingInt(MatchCandidate::column));

        Map<String, Integer> columns = new LinkedHashMap<>();
        Map<String, ColumnMatch> matches = new LinkedHashMap<>();
        Set<Integer> assignedColumns = new LinkedHashSet<>();
        for (MatchCandidate candidate : candidates) {
            if (columns.containsKey(candidate.semantic())
                    || assignedColumns.contains(candidate.column())) {
                continue;
            }
            columns.put(candidate.semantic(), candidate.column());
            matches.put(candidate.semantic(), new ColumnMatch(
                    candidate.column(),
                    candidate.kind(),
                    candidate.confidence(),
                    candidate.header()));
            assignedColumns.add(candidate.column());
        }
        return new ColumnResolution(Map.copyOf(columns), Map.copyOf(matches));
    }

    private static MatchCandidate bestCandidate(String semantic,
                                                int column,
                                                List<String> variants,
                                                List<String> aliases,
                                                List<String> declaredAliases) {
        MatchCandidate best = null;
        for (String variant : variants) {
            String actual = PermitTextNormalizer.canonicalHeader(variant);
            if (actual.isBlank()) {
                continue;
            }
            for (String alias : safeList(aliases)) {
                String expected = PermitTextNormalizer.canonicalHeader(alias);
                if (expected.isBlank()) {
                    continue;
                }
                MatchKind kind;
                double confidence;
                if (actual.equals(expected)) {
                    boolean declared = safeList(declaredAliases).stream()
                            .map(PermitTextNormalizer::canonicalHeader)
                            .anyMatch(actual::equals);
                    kind = declared ? MatchKind.DECLARED_ALIAS : MatchKind.SHARED_ALIAS;
                    confidence = declared ? 1.0 : 0.95;
                } else {
                    confidence = similarity(actual, expected);
                    if (Math.max(actual.length(), expected.length()) < 8
                            || confidence < FUZZY_MATCH_THRESHOLD) {
                        continue;
                    }
                    kind = MatchKind.FUZZY_ALIAS;
                }
                MatchCandidate candidate = new MatchCandidate(
                        semantic, column, kind, confidence, variant);
                if (best == null || better(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean better(MatchCandidate left, MatchCandidate right) {
        int confidence = Double.compare(left.confidence(), right.confidence());
        if (confidence != 0) {
            return confidence > 0;
        }
        return left.kind().ordinal() < right.kind().ordinal();
    }

    private static List<String> headerVariants(List<List<String>> table,
                                               int headerRows,
                                               int column) {
        List<String> variants = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        Set<String> canonicalParts = new LinkedHashSet<>();
        for (int row = 0; row < headerRows; row++) {
            List<String> cells = table.get(row);
            String value = column < cells.size() ? PermitTextNormalizer.clean(cells.get(column)) : "";
            if (value.isBlank()) {
                continue;
            }
            variants.add(value);
            String canonical = PermitTextNormalizer.canonicalHeader(value);
            if (canonicalParts.add(canonical)) {
                parts.add(value);
            }
        }
        if (parts.size() > 1) {
            variants.add(String.join(" ", parts));
        }
        return variants;
    }

    private static boolean contextMatches(int tableIndex,
                                          List<String> tableContexts,
                                          List<String> contextPatterns) {
        if (safeList(contextPatterns).isEmpty()) {
            return true;
        }
        String context = tableIndex < tableContexts.size() ? tableContexts.get(tableIndex) : "";
        return contextPatterns.stream()
                .allMatch(pattern -> Pattern.compile(pattern).matcher(context).find());
    }

    private static double similarity(String left, String right) {
        int maximum = Math.max(left.length(), right.length());
        if (maximum == 0) {
            return 1.0;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - ((double) previous[right.length()] / maximum);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    enum MatchKind {
        DECLARED_ALIAS,
        SHARED_ALIAS,
        FUZZY_ALIAS
    }

    record ColumnMatch(
            int column,
            MatchKind kind,
            double confidence,
            String header
    ) {
    }

    record ColumnResolution(
            Map<String, Integer> columns,
            Map<String, ColumnMatch> matches
    ) {
    }

    record TableMatch(
            int tableIndex,
            List<List<String>> rows,
            int headerRows,
            Map<String, Integer> columns,
            Map<String, ColumnMatch> columnMatches
    ) {
        TableMatch {
            rows = List.copyOf(rows);
            columns = Map.copyOf(columns);
            columnMatches = Map.copyOf(columnMatches);
        }

        List<List<String>> dataRows() {
            return rows.subList(headerRows, rows.size());
        }

        double minimumConfidence() {
            return columnMatches.values().stream()
                    .mapToDouble(ColumnMatch::confidence)
                    .min()
                    .orElse(0.0);
        }

        boolean requiresReview() {
            return headerRows > 1 || columnMatches.values().stream()
                    .anyMatch(match -> match.kind() != MatchKind.DECLARED_ALIAS);
        }

        String source() {
            return "TABLE[" + (tableIndex + 1) + "].HEADER[1.." + headerRows + "]";
        }
    }

    private record MatchCandidate(
            String semantic,
            int column,
            MatchKind kind,
            double confidence,
            String header
    ) {
    }
}
