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
        boolean revisionCreatesNewPermit,
        String workflow
) {
    public static final String REFERENCED_SEASON_NEW_SC = "REFERENCED_SEASON_NEW_SC";
    public static final String EXISTING_SC_REVISION = "EXISTING_SC_REVISION";
    public static final String SUBJECT_CORRECTION = "SUBJECT_CORRECTION";
    public static final String MULTI_DAY_NO_REVISION = "MULTI_DAY_NO_REVISION";
    public static final String CANCEL_SEASONAL_PERMIT = "CANCEL_SEASONAL_PERMIT";
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
    private static final Pattern REVISION_TOKEN = Pattern.compile(
            "(?iu)\\b(?:REV(?:ISION)?|RVS)(?:\\s*[:._/-]?\\s*\\d+)?\\b");
    private static final Pattern SCHEDULE_CANCELLATION = Pattern.compile(
            "(?iu)\\bCANCELLATION\\s+FORM\\s+FOR\\s+SCHEDULED\\s+FLIGHT\\b");
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
                resolvedFlights, originalFlights, referencedPermitId, revisionCreatesNewPermit,
                workflow);
    }

    public SchedulePermit withFlightsAndOriginals(List<ScheduleFlight> resolvedFlights,
                                                   List<ScheduleFlight> resolvedOriginalFlights) {
        return new SchedulePermit(
                sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                resolvedFlights, resolvedOriginalFlights, referencedPermitId, revisionCreatesNewPermit,
                workflow);
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
        if (referencedSeasonNewPermit()) {
            return normalizedPermitId;
        }
        if (seasonalRevision() || revisionCreatesNewPermit) {
            return revisionPermitId();
        }
        return revision() && referencedPermitId != null && !referencedPermitId.isBlank()
                ? referencedPermitId
                : normalizedPermitId;
    }

    public boolean explicitRevision() {
        return revisionNumber() != null;
    }

    /** REV/RVS with a resolved number identifies a revision for both permit types. */
    public boolean importRevision() {
        return explicitRevision();
    }

    /** A bare REV/RVS marker or replacement table requires an inferred revision number. */
    public boolean implicitTableRevision() {
        return !referencedSeasonNewPermit()
                && revisionNumber() == null
                && (hasRevisionToken() || hasReplacementScheduleSection()
                        || cancellationRevision());
    }

    public boolean cancellationRevision() {
        return seasonalPermit() && (cancellationOnlyNotice()
                || SCHEDULE_CANCELLATION.matcher(
                        rawContent == null ? "" : rawContent).find());
    }

    public boolean cancellationOnlyNotice() {
        return CANCEL_SEASONAL_PERMIT.equalsIgnoreCase(workflow);
    }

    private boolean hasRevisionToken() {
        return REVISION_TOKEN.matcher(String.join("\n",
                sourcePermitNumber == null ? "" : sourcePermitNumber,
                rawContent == null ? "" : rawContent)).find();
    }

    private boolean hasReplacementScheduleSection() {
        String folded = foldVietnamese(rawContent);
        boolean replacementHeading = Pattern.compile(
                "(?iu)(?:NEW|REVISED|AMENDED|REPLACEMENT|SUA\\s+DOI|"
                        + "THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI)"
                        + ".{0,80}(?:SCHEDULES?|FLIGHTS?|PHEP\\s+BAY|LICH\\s+BAY|CHUYEN\\s+BAY)|"
                        + "(?:SCHEDULES?|FLIGHTS?|PHEP\\s+BAY|LICH\\s+BAY|CHUYEN\\s+BAY)"
                        + ".{0,80}(?:NEW|REVISED|AMENDED|REPLACEMENT|SUA\\s+DOI|"
                        + "THAY\\s+DOI|DIEU\\s+CHINH|BO\\s+SUNG|MOI)")
                .matcher(folded).find();
        boolean pairedOldNewSections = Pattern.compile("(?iu)\\b(?:OLD|ORIGINAL)\\b")
                .matcher(folded).find()
                && Pattern.compile("(?iu)\\bNEW\\b")
                .matcher(folded).find()
                && Pattern.compile("(?iu)\\bSCHEDULES?\\b")
                .matcher(folded).find();
        return replacementHeading || pairedOldNewSections;
    }

    public boolean otherDetailsUnchanged() {
        String folded = foldVietnamese(rawContent);
        return Pattern.compile(
                "(?iu)(?:OTHER\\s+(?:MAIN\\s+)?(?:DETAILS|INFORMATION)\\s+"
                        + "(?:(?:REMAIN(?:S|ED)?|IS)\\s+)?UNCHANGED|"
                        + "(?:CAC\\s+)?(?:THONG\\s+TIN|CHI\\s+TIET)\\s+KHAC\\s+"
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
                flights, originalFlights, referencedPermitId, revisionCreatesNewPermit,
                workflow);
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
                originalFlights, revisionBasePermitId(), true, workflow);
    }

    public String revisionReference() {
        Integer number = revisionNumber();
        if (number == null) {
            return reference;
        }
        return (reference != null && reference.startsWith("EM ")
                ? reference : "EM " + permitDate.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy")))
                + " R(" + number + ")";
    }

    /** Remark placed on the exact flight row changed by a non-scheduled revision. */
    public String revisionRemark() {
        Integer number = revisionNumber();
        if (number == null || permitDate == null) {
            return reference;
        }
        return permitDate.format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"))
                + " R(" + number + ")";
    }

    private String seasonalRevisionReference() {
        return reference;
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
                revisionCreatesNewPermit, workflow);
    }

    /** Seasonal/non-scheduled classification comes only from the permit number. */
    public boolean seasonalPermit() {
        return SEASONAL_PERMIT.matcher(
                sourcePermitNumber == null ? "" : sourcePermitNumber).find();
    }

    public boolean referencedSeasonNewPermit() {
        return REFERENCED_SEASON_NEW_SC.equalsIgnoreCase(workflow);
    }

    public boolean existingScheduledRevision() {
        return EXISTING_SC_REVISION.equalsIgnoreCase(workflow);
    }

    public boolean subjectCorrection() {
        return SUBJECT_CORRECTION.equalsIgnoreCase(workflow);
    }

    public boolean multiDayNonScheduledRevision() {
        return MULTI_DAY_NO_REVISION.equalsIgnoreCase(workflow);
    }

    public SchedulePermit withWorkflow(String newWorkflow) {
        return new SchedulePermit(sourcePermitNumber, normalizedPermitId, permitNumber,
                authorId, permitType, version, season, permitDate, operatorId, reference,
                validHours, billingAddress, flightType, iataAirportsAllowed,
                emptyAirwaysAllowed, reviewOnly, rawContent, flights, originalFlights,
                referencedPermitId, revisionCreatesNewPermit, newWorkflow);
    }

    /** Only the actual NO destination uses OLD deletion / NEW insertion. */
    public boolean replacesNonScheduledRows() {
        return importRevision() && !usesScheduledStorage() && !revisionCreatesNewPermit;
    }

    public boolean usesScheduledStorage() {
        return seasonalPermit() || referencedSeasonNewPermit() || existingScheduledRevision()
                || multiDayNonScheduledRevision() || multiDayNonScheduled();
    }

    public SchedulePermit withReference(String newReference) {
        return new SchedulePermit(sourcePermitNumber, normalizedPermitId, permitNumber,
                authorId, permitType, version, season, permitDate, operatorId,
                newReference, validHours, billingAddress, flightType, iataAirportsAllowed,
                emptyAirwaysAllowed, reviewOnly, rawContent, flights, originalFlights,
                referencedPermitId, revisionCreatesNewPermit, workflow);
    }

    /** Identifies a non-scheduled permit whose schedule contains a date range. */
    public boolean multiDayNonScheduled() {
        return !seasonalPermit() && !referencedSeasonNewPermit() && !existingScheduledRevision()
                && flights.stream().anyMatch(flight -> flight.beginDate() != null
                        && flight.endDate() != null
                        && !flight.beginDate().equals(flight.endDate()));
    }

    /**
     * Season stored in ATFM. A multi-day non-scheduled permit derives its season
     * from the IATA season of its earliest flight date; all other permits keep
     * the season supplied by the parsing profile or inherited workflow.
     */
    @Override
    public String season() {
        if (!multiDayNonScheduled()) {
            return season;
        }
        LocalDate earliest = flights.stream()
                .map(ScheduleFlight::beginDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        return earliest == null ? season : iataSeason(earliest);
    }

    /** IATA seasons: summer from the last Sunday of March, winter from the last Sunday of October. */
    private static String iataSeason(LocalDate date) {
        return switch (date.getMonthValue()) {
            case 4, 5, 6, 7, 8, 9 -> "S";
            case 3 -> date.isBefore(lastSunday(date.getYear(), 3)) ? "W" : "S";
            case 10 -> date.isBefore(lastSunday(date.getYear(), 10)) ? "S" : "W";
            default -> "W";
        };
    }

    private static LocalDate lastSunday(int year, int month) {
        return LocalDate.of(year, month, 1)
                .with(java.time.temporal.TemporalAdjusters.lastInMonth(java.time.DayOfWeek.SUNDAY));
    }

    public SchedulePermit withExistingScheduledTarget(String scheduledPermitId) {
        String target = scheduledPermitId == null
                ? ""
                : scheduledPermitId.trim().toUpperCase(java.util.Locale.ROOT);
        if (target.isBlank()) {
            throw new IllegalArgumentException("Scheduled revision target must not be blank");
        }
        java.util.regex.Matcher matcher = Pattern.compile("(?i)/(?<season>[SW])/").matcher(target);
        // A multi-day non-scheduled permit lives in scheduled storage without a
        // season marker in its id; its season is derived from the flight dates.
        String targetSeason = matcher.find()
                ? matcher.group("season").toUpperCase(java.util.Locale.ROOT)
                : season();
        return new SchedulePermit(
                sourcePermitNumber, target, permitNumber, authorId, permitType,
                version, targetSeason, permitDate,
                operatorId, reference, validHours, billingAddress, flightType,
                iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, originalFlights, target, false, EXISTING_SC_REVISION);
    }

    public SchedulePermit withMultiDayNonScheduledRevisionTarget(String scheduledPermitId) {
        SchedulePermit targeted = withExistingScheduledTarget(scheduledPermitId);
        return targeted.withWorkflow(MULTI_DAY_NO_REVISION);
    }

    public SchedulePermit withReferencedSeason(String inheritedSeason) {
        if (!referencedSeasonNewPermit()) {
            return this;
        }
        String normalizedSeason = inheritedSeason == null
                ? ""
                : inheritedSeason.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalizedSeason.matches("[SW]")) {
            throw new IllegalArgumentException("Referenced permit season must be S or W");
        }
        String inheritedPermitId = normalizedPermitId;
        if (!SEASONAL_PERMIT.matcher(inheritedPermitId == null ? "" : inheritedPermitId).find()) {
            inheritedPermitId = inheritedPermitId.replaceFirst(
                    "(?i)/(?=[A-Z0-9]+/(?:19|20)\\d{2}$)",
                    "/" + normalizedSeason + "/");
        }
        List<ScheduleFlight> materialized = new java.util.ArrayList<>(
                originalFlights.size() + flights.size());
        for (int index = 0; index < originalFlights.size(); index++) {
            ScheduleFlight original = originalFlights.get(index);
            materialized.add(original.asCancelledReferenceFrom(
                    originalPermitNumber(original, index)));
        }
        materialized.addAll(flights);
        return new SchedulePermit(
                sourcePermitNumber, inheritedPermitId, permitNumber, authorId, permitType,
                version, normalizedSeason, permitDate, operatorId, reference, validHours,
                billingAddress, flightType, iataAirportsAllowed, emptyAirwaysAllowed,
                reviewOnly, rawContent, materialized, originalFlights, referencedPermitId,
                false, workflow);
    }

    private String originalPermitNumber(ScheduleFlight original, int index) {
        if (original.remark() != null) {
            java.util.regex.Matcher matcher = Pattern.compile(
                            "(?iu)(?:LD|O\\s*/?\\s*F)\\s*-?\\s*\\d{1,5}"
                                    + "(?:\\s*/\\s*[A-Z])?(?:\\s*/\\s*[SW])?"
                                    + "(?:\\s*/\\s*(?:19|20)\\d{2})(?:VN)?")
                    .matcher(original.remark());
            if (matcher.find()) {
                return matcher.group().trim();
            }
        }
        if (reference != null && !reference.isBlank()) {
            String[] references = reference.split("\\s*;\\s*");
            if (index < references.length && !references[index].isBlank()) {
                return references[index].trim();
            }
        }
        return referencedPermitId;
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
                          List<ScheduleFlight> originalFlights,
                          String referencedPermitId,
                          boolean revisionCreatesNewPermit) {
        this(sourcePermitNumber, normalizedPermitId, permitNumber, authorId, permitType,
                version, season, permitDate, operatorId, reference, validHours, billingAddress,
                flightType, iataAirportsAllowed, emptyAirwaysAllowed, reviewOnly, rawContent,
                flights, originalFlights, referencedPermitId, revisionCreatesNewPermit, null);
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
