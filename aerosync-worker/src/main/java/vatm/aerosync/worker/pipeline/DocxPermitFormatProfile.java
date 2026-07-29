package vatm.aerosync.worker.pipeline;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

record DocxPermitFormatProfile(
        String id,
        int priority,
        List<String> detectionPatterns,
        PermitIdentity permit,
        DateField permitDate,
        TextField operator,
        TextField billingAddress,
        TextField reference,
        String referenceColumn,
        PurposeDefinition purpose,
        MasterDefaults master,
        ScheduleDefinition schedule,
        RouteDefinition route,
        AircraftDefinition aircraft,
        ValidationRules validation
) {
    record PermitIdentity(
            String pattern,
            String numberGroup,
            String numberTemplate,
            String sourceTemplate,
            String normalizedTemplate,
            Map<String, Integer> zeroPadGroups
    ) {
    }

    record DateField(
            String source,
            String pattern,
            String group,
            List<String> formats,
            String locale,
            boolean fallbackToDocumentCreatedDate
    ) {
    }

    record TextField(
            String source,
            String pattern,
            String group,
            boolean required,
            Map<String, String> valueMappings
    ) {
    }

    record MasterDefaults(
            String authorId,
            String permitType,
            String version,
            String season,
            int validHours,
            String flightType
    ) {
    }

    record PurposeDefinition(
            String defaultId,
            List<PatternValue> mappings
    ) {
    }

    record PatternValue(String pattern, String value) {
    }

    record ScheduleDefinition(
            Map<String, List<String>> columns,
            List<String> requiredColumns,
            List<String> excludeColumns,
            List<String> tableContextPatterns,
            List<String> dateFormats,
            List<String> timeFormats,
            String locale,
            String purposeId,
            boolean includeEta,
            boolean lastMatchingTable,
            boolean inferIataPrefix
    ) {
    }

    record RouteDefinition(
            Map<String, List<String>> columns,
            List<String> requiredColumns,
            Map<String, String> staticAirways,
            boolean tableRequired,
            boolean allowEmpty,
            boolean fallbackToFirst,
            boolean lastMatchingTable,
            boolean filterSchedule
    ) {
    }

    record AircraftDefinition(
            Long defaultCraftId,
            BigDecimal defaultMtow,
            String scheduleColumn,
            List<AircraftMapping> mappings,
            Map<String, List<String>> auxiliaryColumns,
            List<String> auxiliaryRequiredColumns,
            String auxiliaryTypeColumn,
            String remarkPrefix,
            String defaultType,
            boolean lastMatchingTable
    ) {
    }

    record AircraftMapping(List<String> aliases, long craftId, BigDecimal mtow) {
    }

    record ValidationRules(boolean allowIataAirports, boolean reviewOnly) {
    }
}
