package vatm.aerosync.worker.model;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

public record SchedulePermit(
        String sourcePermitNumber,
        String normalizedPermitId,
        String permitNumber,
        String authorId,
        String permitType,
        String version,
        String season,
        LocalDate permitDate,
        String operatorId,
        String reference,
        int validHours,
        String billingAddress,
        String flightType,
        boolean iataAirportsAllowed,
        boolean emptyAirwaysAllowed,
        boolean reviewOnly,
        String rawContent,
        List<ScheduleFlight> flights,
        List<ScheduleFlight> originalFlights,
        String referencedPermitId,
        boolean revisionCreatesNewPermit
) {
    private static final Pattern REVISION_MARKER = Pattern.compile(
            "(?iu)(?:\\bREV(?:ISION)?\\s*\\d*\\b|\\bRVS\\s*\\d*\\b|"
                    + "SỬA\\s*ĐỔI|SUA\\s*DOI|LỊCH\\s*BAY\\s*GỐC|LICH\\s*BAY\\s*GOC|"
                    + "(?:BẢNG\\s+)?CHUYẾN\\s+BAY\\s*(?:CŨ|GỐC|BAN\\s+ĐẦU)|"
                    + "(?:BANG\\s+)?CHUYEN\\s+BAY\\s*(?:CU|GOC|BAN\\s+DAU)|"
                    + "(?:BẢNG\\s+)?(?:PHÉP|LỊCH|CHUYẾN)\\s+BAY\\s*"
                    + "(?:MỚI|SỬA\\s+ĐỔI|THAY\\s+ĐỔI|ĐIỀU\\s+CHỈNH|BỔ\\s+SUNG)|"
                    + "(?:BANG\\s+)?(?:PHEP|LICH|CHUYEN)\\s+BAY\\s*"
                    + "(?:MOI|SUA\\s+DOI|THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG)|"
                    + "(?:OLD|ORIGINAL)\\s+(?:FLIGHT\\s+)?SCHEDULES?|"
                    + "(?:OLD|ORIGINAL)\\s+FLIGHTS?|"
                    + "(?:NEW|REVISED|AMENDED|REPLACEMENT)\\s+(?:FLIGHT\\s+)?SCHEDULES?|"
                    + "(?:NEW|REVISED|AMENDED|REPLACEMENT)\\s+FLIGHTS?)");
    private static final Pattern PERMIT_YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern SEASONAL_PERMIT = Pattern.compile("(?i)/(?:S|W)/");
    private static final Pattern EXPLICIT_REVISION = Pattern.compile(
            "(?iu)\\b(?:REV|RVS)\\s*[:._/-]?\\s*(?<number>\\d+)\\b");
    private static final String INFERRED_REVISION_MARKER = "AEROSYNC_INFERRED_REVISION";

    public SchedulePermit {
        flights = List.copyOf(flights);
        originalFlights = originalFlights == null ? List.of() : List.copyOf(originalFlights);
        // Business classification must come from the permit identity read from Word.
        // normalizedPermitId is produced by YAML and must never be allowed to invent /S/ or /W/.
        boolean seasonalIdentity = SEASONAL_PERMIT.matcher(
                sourcePermitNumber == null ? "" : sourcePermitNumber).find();
        if (revisionCreatesNewPermit && !seasonalIdentity) {
            throw new IllegalArgumentException(
                    "Only a seasonal permit may create a separate revision permit");
        }
        flightType = seasonalIdentity ? "SC" : "NO";
    }

    public SchedulePermit withFlights(List<ScheduleFlight> resolvedFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights, originalFlights, referencedPermitId, revisionCreatesNewPermit);
    }

    public SchedulePermit withFlightsAndOriginals(List<ScheduleFlight> resolvedFlights,
                                                   List<ScheduleFlight> resolvedOriginalFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights, resolvedOriginalFlights, referencedPermitId, revisionCreatesNewPermit);
    }

    public boolean revision() {
        return REVISION_MARKER.matcher(
                foldVietnamese((sourcePermitNumber == null ? "" : sourcePermitNumber) + "\n"
                        + (rawContent == null ? "" : rawContent))).find();
    }

    public Integer permitYear() {
        if (normalizedPermitId == null) {
            return null;
        }
        java.util.regex.Matcher matcher = PERMIT_YEAR.matcher(normalizedPermitId);
        Integer year = null;
        while (matcher.find()) {
            year = Integer.valueOf(matcher.group());
        }
        return year;
    }

    public String atfmTargetPermitId() {
        if (seasonalRevision() || inferredRevision() || revisionCreatesNewPermit) {
            return revisionPermitId();
        }
        return revision() && referencedPermitId != null && !referencedPermitId.isBlank()
                ? referencedPermitId
                : normalizedPermitId;
    }

    public boolean explicitRevision() {
        return revisionNumber() != null;
    }

    /**
     * SC is revised by REV/RVS alone. NO requires both REV/RVS and a
     * NEW/REVISED schedule section.
     */
    public boolean importRevision() {
        if (!explicitRevision()) {
            return false;
        }
        if (seasonalPermit()) {
            return true;
        }
        return hasReplacementScheduleSection();
    }

    /**
     * Only a seasonal permit may infer a revision from a NEW/REVISED schedule
     * table when the Word permit number itself has no REV/RVS marker.
     */
    public boolean implicitTableRevision() {
        return seasonalPermit() && revisionNumber() == null && hasReplacementScheduleSection();
    }

    private boolean hasReplacementScheduleSection() {
        String folded = foldVietnamese(rawContent);
        return Pattern.compile(
                "(?iu)(?:NEW|REVISED|AMENDED|REPLACEMENT|SUA\\s+DOI|"
                        + "THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI)"
                        + ".{0,80}(?:SCHEDULES?|FLIGHTS?|PHEP\\s+BAY|LICH\\s+BAY|CHUYEN\\s+BAY)|"
                        + "(?:SCHEDULES?|FLIGHTS?|PHEP\\s+BAY|LICH\\s+BAY|CHUYEN\\s+BAY)"
                        + ".{0,80}(?:NEW|REVISED|AMENDED|REPLACEMENT|SUA\\s+DOI|"
                        + "THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI)")
                .matcher(folded).find();
    }

    public boolean otherDetailsUnchanged() {
        String folded = foldVietnamese(rawContent);
        return Pattern.compile(
                "(?iu)(?:OTHER\\s+(?:DETAILS|INFORMATION)\\s+"
                        + "(?:REMAIN(?:S|ED)?\\s+)?UNCHANGED|"
                        + "(?:CAC\\s+)?THONG\\s+TIN\\s+KHAC\\s+"
                        + "(?:KHONG\\s+THAY\\s+DOI|GIU\\s+NGUYEN))")
                .matcher(folded).find();
    }

    private static String foldVietnamese(String value) {
        String asciiD = (value == null ? "" : value)
                .replace('\u0110', 'D')
                .replace('\u0111', 'd');
        return java.text.Normalizer.normalize(asciiD, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    public Integer revisionNumber() {
        java.util.regex.Matcher matcher = EXPLICIT_REVISION.matcher(String.join("\n",
                sourcePermitNumber == null ? "" : sourcePermitNumber,
                rawContent == null ? "" : rawContent));
        return matcher.find() ? Integer.valueOf(matcher.group("number")) : null;
    }

    /** Adds an inferred REV number without changing the Word content fields. */
    public SchedulePermit withRevisionNumber(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("Revision number must be positive");
        }
        if (revisionNumber() != null) {
            return this;
        }
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly,
                INFERRED_REVISION_MARKER + "\nREV" + number + "\n"
                        + (rawContent == null ? "" : rawContent),
                flights, originalFlights, referencedPermitId, revisionCreatesNewPermit);
    }

    public boolean inferredRevision() {
        return rawContent != null && rawContent.contains(INFERRED_REVISION_MARKER);
    }

    public boolean seasonalRevision() {
        return seasonalPermit() && revisionNumber() != null;
    }

    /** Base permit used only to inherit old rows/details for a revision. */
    public String revisionBasePermitId() {
        return referencedPermitId != null && !referencedPermitId.isBlank()
                ? referencedPermitId
                : normalizedPermitId;
    }

    /** Creates LD 0001AR2/S/CHK/2026 from LD 0001A/S/CHK/2026 + REV2. */
    public String revisionPermitId() {
        Integer number = revisionNumber();
        if (number == null || normalizedPermitId == null) {
            return normalizedPermitId;
        }
        int slash = identitySlash(normalizedPermitId);
        if (slash < 0) {
            return normalizedPermitId;
        }
        String head = normalizedPermitId.substring(0, slash);
        java.util.regex.Matcher alreadyRevised = Pattern.compile(
                        "(?i)(?:[A-Z]R" + number + "|R" + number + "[A-Z]?)$")
                .matcher(head);
        if (alreadyRevised.find()) {
            return normalizedPermitId;
        }
        java.util.regex.Matcher suffix = Pattern.compile("(?i)(?<digits>\\d+)(?<version>[A-Z]?)$")
                .matcher(head);
        if (!suffix.find()) {
            return normalizedPermitId;
        }
        String revisedHead = head.substring(0, suffix.start())
                + suffix.group("digits") + suffix.group("version") + "R" + number;
        return revisedHead + normalizedPermitId.substring(slash);
    }

    /** Business lookup key: permit text from its first character to the first slash. */
    public String permitLookupKey() {
        String source = seasonalRevision() || revisionCreatesNewPermit
                ? sourcePermitWithRevisionNumber()
                : sourcePermitNumber;
        if (source == null || source.isBlank()) {
            source = atfmTargetPermitId();
        }
        String compact = source == null ? "" : source.trim().toUpperCase(java.util.Locale.ROOT);
        int slash = compact.startsWith("O/F") ? compact.indexOf('/', 3) : compact.indexOf('/');
        return (slash < 0 ? compact : compact.substring(0, slash)).replaceAll("\\s+", "");
    }

    private String sourcePermitWithRevisionNumber() {
        Integer number = revisionNumber();
        String source = sourcePermitNumber == null ? "" : sourcePermitNumber;
        int slash = identitySlash(source);
        if (number == null || slash < 0) {
            return source;
        }
        String head = source.substring(0, slash).replaceAll("(?i)[A-Z]?R\\d+$", "");
        String sourceVersion = version == null ? "" : version.trim().toUpperCase(java.util.Locale.ROOT);
        if (!sourceVersion.isBlank() && head.toUpperCase(java.util.Locale.ROOT).endsWith(sourceVersion)) {
            sourceVersion = "";
        }
        return head + sourceVersion + "R" + number + source.substring(slash);
    }

    /** Ignores the slash inside the O/F prefix and returns the permit-body slash. */
    private static int identitySlash(String permitId) {
        if (permitId == null) {
            return -1;
        }
        String trimmed = permitId.stripLeading();
        int leadingOffset = permitId.length() - trimmed.length();
        return trimmed.regionMatches(true, 0, "O/F", 0, 3)
                ? permitId.indexOf('/', leadingOffset + 3)
                : permitId.indexOf('/', leadingOffset);
    }

    public SchedulePermit asSeasonalRevision(List<ScheduleFlight> materializedFlights) {
        String revisedId = revisionPermitId();
        String revisedPermitNumber = revisedPermitNumber();
        return new SchedulePermit(
                sourcePermitWithRevisionNumber(), revisedId, revisedPermitNumber,
                authorId, permitType, version, season, permitDate, operatorId,
                seasonalRevisionReference(),
                validHours, billingAddress, flightType, iataAirportsAllowed,
                emptyAirwaysAllowed, reviewOnly, rawContent, materializedFlights,
                originalFlights, revisionBasePermitId(), true);
    }

    public String revisionReference() {
        Integer number = revisionNumber();
        if (number == null || permitDate == null) {
            return reference;
        }
        return permitDate.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"))
                + " RVS(" + number + ")";
    }

    private String seasonalRevisionReference() {
        Integer number = revisionNumber();
        if (number == null) {
            return reference;
        }
        String base = sourcePermitNumber == null || sourcePermitNumber.isBlank()
                ? revisionBasePermitId()
                : sourcePermitNumber.replaceFirst(
                        "(?iu)\\s*/?\\s*(?:REV|RVS)\\s*[:._/-]?\\s*\\d+\\s*$", "").trim();
        return base + " RVS(" + number + ")";
    }

    private String revisedPermitNumber() {
        if (permitNumber == null) {
            return null;
        }
        String base = permitNumber.replaceFirst("(?i)(?:[A-Z]R\\d+|R\\d+[A-Z]?)$", "");
        java.util.regex.Matcher matcher = Pattern.compile("(?i)(?<digits>\\d+)(?<version>[A-Z]?)$")
                .matcher(base);
        if (!matcher.find()) {
            return base + (version == null ? "" : version) + "R" + revisionNumber();
        }
        String numberVersion = matcher.group("version");
        if (numberVersion.isBlank() && version != null) {
            numberVersion = version.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return base.substring(0, matcher.start()) + matcher.group("digits")
                + numberVersion + "R" + revisionNumber();
    }

    public SchedulePermit withOperatorId(String resolvedOperatorId) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, resolvedOperatorId, reference, validHours,
                billingAddress, flightType, iataAirportsAllowed, emptyAirwaysAllowed,
                reviewOnly, rawContent, flights, originalFlights, referencedPermitId,
                revisionCreatesNewPermit);
    }

    /** Seasonal/non-scheduled classification comes only from the permit number. */
    public boolean seasonalPermit() {
        return SEASONAL_PERMIT.matcher(
                sourcePermitNumber == null ? "" : sourcePermitNumber).find();
    }

    public Integer atfmTargetPermitYear() {
        String target = atfmTargetPermitId();
        if (target == null) {
            return null;
        }
        java.util.regex.Matcher matcher = PERMIT_YEAR.matcher(target);
        Integer year = null;
        while (matcher.find()) {
            year = Integer.valueOf(matcher.group());
        }
        return year;
    }

    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          boolean iataAirportsAllowed,
                          boolean emptyAirwaysAllowed,
                          boolean reviewOnly,
                          String rawContent,
                          List<ScheduleFlight> flights,
                          List<ScheduleFlight> originalFlights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, originalFlights, null, false);
    }

    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          boolean iataAirportsAllowed,
                          boolean emptyAirwaysAllowed,
                          boolean reviewOnly,
                          String rawContent,
                          List<ScheduleFlight> flights,
                          List<ScheduleFlight> originalFlights,
                          String referencedPermitId) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, originalFlights, referencedPermitId, false);
    }

    /**
     * Compatibility constructor for existing programmatic callers. Word profile
     * parsing always supplies the validation policy explicitly.
     */
    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, "LD".equals(permitType), "LD".equals(permitType), false,
                rawContent, flights, List.of(), null, false);
    }

    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          boolean iataAirportsAllowed,
                          boolean emptyAirwaysAllowed,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, false, rawContent, flights,
                List.of(), null, false);
    }

    public SchedulePermit(String sourcePermitNumber,
                          String normalizedPermitId,
                          String permitNumber,
                          String authorId,
                          String permitType,
                          String version,
                          String season,
                          LocalDate permitDate,
                          String operatorId,
                          String reference,
                          int validHours,
                          String billingAddress,
                          String flightType,
                          boolean iataAirportsAllowed,
                          boolean emptyAirwaysAllowed,
                          boolean reviewOnly,
                          String rawContent,
                          List<ScheduleFlight> flights) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, List.of(), null, false);
    }
}
