package vatm.aerosync.worker.atfm;

import org.springframework.stereotype.Component;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.io.StringReader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JdbcAtfmScheduleGateway implements AtfmScheduleGateway {

    private static final int NO_DAYSFLIGHT_MAX_BYTES = 200;
    static final String VIA_PREFIX = "Other permitted airways published in Viet Nams AIP/";

    private static final String FIND_SC_PERMIT_IDS_BY_YEAR_SQL = """
            SELECT PERMNBR_ID FROM T_PERMMASTER_SC
             WHERE SUBSTR(TRIM(PERMNBR_ID), -4) = ?
            """;

    private static final String FIND_EXISTING_SQL = """
            SELECT m.ID, m.PERM_ID, m.AUTHOR_ID, m.PERMTYPE, m.PERMNBR,
                   m.VERSION, m.SEASON, m.PERMDATE, m.OPER_ID, m.VALIDHOURS,
                   m.FLIGHTTYPE,
                   d.PURPOSE_ID, d.CRAFT_ID, d.MTOW, d.FLIGHTNBR,
                   d.REGISTRATION, d.DAY1, d.DAY2, d.DAY3, d.DAY4, d.DAY5, d.DAY6, d.DAY7,
                   d.FROM_AIRP, d.TO_AIRP, d.ETD, d.ETA, d.VIA,
                   d.BEGINDATE, d.ENDDATE, d.REMARK
              FROM T_PERMMASTER_SC m
              LEFT JOIN T_PERMDETAIL_SC d ON d.PERM_ID = m.PERM_ID
             WHERE m.ID = (
                   SELECT MAX(candidate.ID)
                     FROM T_PERMMASTER_SC candidate
                    WHERE candidate.PERMNBR_ID = ?
                      AND SUBSTR(TRIM(candidate.PERMNBR_ID), -4) = ?)
             ORDER BY d.ID
            """;

    private static final String INSERT_MASTER_SQL = """
            BEGIN
              INSERT INTO T_PERMMASTER_SC (
                  PERMNBR_ID, AUTHOR_ID, PERMTYPE, PERMNBR, VERSION, SEASON,
                  PERMDATE, OPER_ID, REFERENCE, VALIDHOURS, LASTMODIFY, LASTUSER,
                  STATUS, PERMCONTENT, PERMCONTENT1, BILLINGADDRESS, FLIGHTTYPE)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              RETURNING ID, PERM_ID INTO ?, ?;
            END;
            """;

    private static final String INSERT_DETAIL_SQL = """
            INSERT INTO T_PERMDETAIL_SC (
                PERM_ID, PURPOSE_ID, CRAFT_ID, MTOW, FLIGHTNBR, REGISTRATION,
                DAY1, DAY2, DAY3, DAY4, DAY5, DAY6, DAY7,
                FROM_AIRP, TO_AIRP, ETD, ETA, VIA, STATUS,
                LASTMODIFY, LASTUSER, BEGINDATE, ENDDATE, REMARK)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String LOCK_EXISTING_SQL = """
            SELECT ID, PERM_ID
              FROM T_PERMMASTER_SC
             WHERE ID = (SELECT MAX(candidate.ID)
                           FROM T_PERMMASTER_SC candidate
                          WHERE candidate.PERMNBR_ID = ?
                            AND SUBSTR(TRIM(candidate.PERMNBR_ID), -4) = ?)
             FOR UPDATE
            """;

    private static final String UPDATE_MASTER_SQL = """
            UPDATE T_PERMMASTER_SC
               SET AUTHOR_ID = ?, PERMTYPE = ?, PERMNBR = ?, VERSION = ?, SEASON = ?,
                   PERMDATE = ?, OPER_ID = ?, REFERENCE = ?, VALIDHOURS = ?,
                   LASTMODIFY = ?, LASTUSER = ?, STATUS = ?, PERMCONTENT = ?,
                   PERMCONTENT1 = ?, BILLINGADDRESS = ?, FLIGHTTYPE = ?
             WHERE ID = ?
            """;

    private static final String FIND_NO_MASTER_SQL = """
            SELECT ID, PERM_ID, REFERENCE
              FROM T_PERMMASTER_NO
             WHERE ID = (SELECT MAX(candidate.ID)
                           FROM T_PERMMASTER_NO candidate
                          WHERE candidate.PERMNBR_ID = ?
                            AND SUBSTR(TRIM(candidate.PERMNBR_ID), -4) = ?)
            """;
    private static final String FIND_NO_BASELINE_SQL = """
            SELECT ID, PERM_ID, OPER_ID
              FROM T_PERMMASTER_NO
             WHERE ID = (SELECT MAX(candidate.ID)
                           FROM T_PERMMASTER_NO candidate
                          WHERE candidate.PERMNBR_ID = ?
                            AND SUBSTR(TRIM(candidate.PERMNBR_ID), -4) = ?)
            """;

    private static final String LOCK_NO_MASTER_SQL = FIND_NO_MASTER_SQL + " FOR UPDATE";

    private static final String FIND_NO_REVISION_MASTER_CONTENT_SQL = """
            SELECT REFERENCE, PERMCONTENT, PERMCONTENT1
              FROM T_PERMMASTER_NO
             WHERE ID = ?
            """;

    private static final String UPDATE_NO_REVISION_MASTER_SQL = """
            UPDATE T_PERMMASTER_NO
               SET REFERENCE = ?, PERMCONTENT = ?, PERMCONTENT1 = ?,
                   LASTMODIFY = ?, LASTUSER = ?
             WHERE ID = ?
            """;

    private static final String FIND_SC_REVISION_MASTER_CONTENT_SQL = """
            SELECT REFERENCE, PERMCONTENT, PERMCONTENT1
              FROM T_PERMMASTER_SC
             WHERE ID = ?
            """;

    private static final String UPDATE_SC_REVISION_MASTER_SQL = """
            UPDATE T_PERMMASTER_SC
               SET REFERENCE = ?, PERMCONTENT = ?, PERMCONTENT1 = ?,
                   LASTMODIFY = ?, LASTUSER = ?
             WHERE ID = ?
            """;

    private static final String INSERT_NO_MASTER_SQL = """
            BEGIN
              INSERT INTO T_PERMMASTER_NO (
                  PERMNBR_ID, AUTHOR_ID, PERMTYPE, PERMNBR, PERMDATE, OPER_ID,
                  REFERENCE, VALIDHOURS, STATUS, LASTUSER, LASTMODIFY,
                  BILLINGADDRESS, PERMCONTENT, PERMCONTENT1, ADDRESS1, VERSION, FLIGHTTYPE)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              RETURNING ID, PERM_ID INTO ?, ?;
            END;
            """;

    private static final String INSERT_NO_DETAIL_SQL = """
            INSERT INTO T_PERMDETAIL_NO (
                PERM_ID, CRAFT_ID, MTOW, DAYSFLIGHT, FLIGHTNBR, REGISTRATION,
                FROM_AIRP, TO_AIRP, ETD, ETA, VIA, STATUS, LASTMODIFY,
                LASTUSER, PURPOSE_ID, MAX_DATE, REMARK)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_REFERENCED_SEASON_SQL = """
            SELECT SEASON
              FROM T_PERMMASTER_SC
             WHERE ID = (SELECT MAX(candidate.ID)
                           FROM T_PERMMASTER_SC candidate
                          WHERE candidate.PERMNBR_ID = ?)
            """;

    private static final String UPDATE_NO_REFERENCE_SQL = """
            UPDATE T_PERMMASTER_NO
               SET REFERENCE = ?, LASTMODIFY = ?, LASTUSER = ?
             WHERE ID = ?
            """;

    private static final DateTimeFormatter NO_FLIGHT_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu")
            .toFormatter(java.util.Locale.ENGLISH);

    private final AtfmDatabaseProperties properties;
    private final AtfmAirportCodeResolver airportCodeResolver;

    public JdbcAtfmScheduleGateway(AtfmDatabaseProperties properties,
                                   AtfmAirportCodeResolver airportCodeResolver) {
        this.properties = properties;
        this.airportCodeResolver = airportCodeResolver;
    }

    @Override
    public Optional<AtfmPermitSnapshot> findExisting(SchedulePermit permit) {
        try (Connection connection = openConnection()) {
            List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
            List<ScheduleFlight> noFlights = noFlights(permit, resolvedFlights);
            List<ScheduleFlight> scheduledFlights = scheduledFlights(permit, resolvedFlights);
            if (scheduledFlights.isEmpty()) {
                return findExistingNo(connection, permit, noFlights);
            }
            try (PreparedStatement statement = connection.prepareStatement(FIND_EXISTING_SQL)) {
                statement.setString(1, permit.atfmTargetPermitId());
                statement.setString(2, requiredPermitYear(permit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    long masterId = resultSet.getLong("ID");
                    long permId = resultSet.getLong("PERM_ID");
                    boolean masterMatches = masterMatches(resultSet, permit);
                    List<ExistingFlight> existingFlights = new ArrayList<>();
                    do {
                        if (resultSet.getString("FLIGHTNBR") != null) {
                            existingFlights.add(toExistingFlight(resultSet));
                        }
                    } while (resultSet.next());
                    return Optional.of(new AtfmPermitSnapshot(
                            masterId,
                            permId,
                            permit.multiDayNonScheduledRevision()
                                    ? false
                                    : permit.importRevision()
                                    ? flightsContain(existingFlights, scheduledFlights)
                                    : masterMatches && flightsMatch(existingFlights, scheduledFlights)
                                            && (noFlights.isEmpty()
                                                || findExistingNo(connection, permit, noFlights)
                                                        .map(AtfmPermitSnapshot::matchesExpectedPermit)
                                                        .orElse(false))));
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure("read existing permit", exception);
        }
    }

    @Override
    public Optional<AtfmPermitSnapshot> findNewPermitInEitherStore(SchedulePermit permit) {
        try (Connection connection = openConnection()) {
            for (String table : List.of("T_PERMMASTER_SC", "T_PERMMASTER_NO")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT ID, PERM_ID FROM " + table
                                + " WHERE UPPER(TRIM(PERMNBR_ID)) = ? ORDER BY ID DESC")) {
                    statement.setString(1, permit.atfmTargetPermitId().trim()
                            .toUpperCase(java.util.Locale.ROOT));
                    statement.setMaxRows(1);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            return Optional.of(new AtfmPermitSnapshot(
                                    rows.getLong("ID"), rows.getLong("PERM_ID"), false));
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw databaseFailure("check new permit in SC and NO", exception);
        }
    }

    @Override
    public Optional<AtfmRevisionBaseline> findRevisionBaseline(SchedulePermit permit) {
        try (Connection connection = openConnection()) {
            if (!permit.seasonalPermit()) {
                if (permit.multiDayNonScheduled()
                        && permit.referencedPermitId() != null
                        && !permit.referencedPermitId().isBlank()) {
                    Optional<AtfmRevisionBaseline> referenced = findScRevisionBaseline(
                            connection, permit.referencedPermitId(), requiredPermitYear(permit));
                    if (referenced.isPresent()) {
                        return referenced;
                    }
                }
                Optional<AtfmRevisionBaseline> nonScheduled = findNoRevisionBaseline(connection, permit);
                return nonScheduled.isPresent()
                        ? nonScheduled
                        : findLegacyScheduledRevisionBaseline(connection, permit);
            }
            return findSeasonalRevisionBaseline(connection, permit, requiredPermitYear(permit));
        } catch (SQLException exception) {
            throw databaseFailure("read revision baseline", exception);
        }
    }

    private Optional<AtfmRevisionBaseline> findScRevisionBaseline(Connection connection,
                                                                   String permitId,
                                                                   String year)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_EXISTING_SQL)) {
            statement.setString(1, permitId);
            statement.setString(2, year);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long masterId = resultSet.getLong("ID");
                long permId = resultSet.getLong("PERM_ID");
                String operatorId = resultSet.getString("OPER_ID");
                List<ScheduleFlight> flights = new ArrayList<>();
                do {
                    if (resultSet.getString("FLIGHTNBR") != null) {
                        flights.add(toBaselineFlight(resultSet));
                    }
                } while (resultSet.next());
                return Optional.of(new AtfmRevisionBaseline(
                        masterId, permId, operatorId, permitId, flights));
            }
        }
    }

    private Optional<AtfmRevisionBaseline> findSeasonalRevisionBaseline(
            Connection connection,
            SchedulePermit permit,
            String year) throws SQLException {
        String basePermitId = permit.revisionBasePermitId();
        Optional<AtfmRevisionBaseline> base = findScRevisionBaseline(
                connection, basePermitId, year);
        Integer currentRevision = permit.revisionNumber();
        Pattern family = revisionFamilyPattern(basePermitId);
        if (currentRevision == null || currentRevision < 2 || family == null) {
            return base;
        }

        Map<String, RevisionPermitId> precedingByCanonicalId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_SC_PERMIT_IDS_BY_YEAR_SQL)) {
            statement.setString(1, year);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String storedPermitId = rows.getString(1);
                    String canonical = canonicalPermitId(storedPermitId);
                    java.util.regex.Matcher matcher = family.matcher(canonical);
                    if (!matcher.matches()) {
                        continue;
                    }
                    String number = matcher.group("number") == null
                            ? matcher.group("legacyNumber")
                            : matcher.group("number");
                    int revision = Integer.parseInt(number);
                    if (revision > 0 && revision < currentRevision) {
                        precedingByCanonicalId.putIfAbsent(
                                canonical, new RevisionPermitId(storedPermitId, revision));
                    }
                }
            }
        }

        List<AtfmRevisionBaseline> preceding = new ArrayList<>();
        List<RevisionPermitId> orderedPreceding = precedingByCanonicalId.values().stream()
                .sorted(Comparator.comparingInt(RevisionPermitId::revision).reversed())
                .toList();
        for (RevisionPermitId candidate : orderedPreceding) {
            findScRevisionBaseline(connection, candidate.permitId(), year)
                    .ifPresent(preceding::add);
        }
        if (preceding.isEmpty()) {
            return base;
        }

        List<ScheduleFlight> completeHistory = new ArrayList<>();
        preceding.forEach(item -> completeHistory.addAll(item.flights()));
        base.ifPresent(item -> completeHistory.addAll(item.flights()));
        AtfmRevisionBaseline identity = base.orElse(preceding.getLast());
        String operatorId = preceding.stream()
                .map(AtfmRevisionBaseline::operatorId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(identity.operatorId());
        return Optional.of(new AtfmRevisionBaseline(
                identity.masterId(), identity.permId(), operatorId,
                base.map(AtfmRevisionBaseline::scheduledPermitId)
                        .orElse(identity.scheduledPermitId()),
                completeHistory));
    }

    private record RevisionPermitId(String permitId, int revision) {
    }

    private Optional<AtfmRevisionBaseline> findLegacyScheduledRevisionBaseline(
            Connection connection,
            SchedulePermit permit) throws SQLException {
        String noPermitId = noBasePermitId(permit);
        String year = requiredPermitYear(permit);
        // A multi-day non-scheduled permit is stored in the scheduled tables
        // under its unmarked id; that exact id wins over legacy S/W variants.
        Optional<AtfmRevisionBaseline> unmarked =
                findScRevisionBaseline(connection, noPermitId, year);
        if (unmarked.isPresent()) {
            return unmarked;
        }
        List<AtfmRevisionBaseline> matches = new ArrayList<>(2);
        for (String season : List.of("S", "W")) {
            String scheduledId = noPermitId.replaceFirst(
                    "(?i)/(?=[A-Z0-9]+/(?:19|20)\\d{2}$)", "/" + season + "/");
            findScRevisionBaseline(connection, scheduledId, year).ifPresent(matches::add);
        }
        if (matches.size() > 1) {
            throw new AtfmReferenceDataException(
                    "Revision base is ambiguous between S and W permits: " + noPermitId);
        }
        return matches.stream().findFirst();
    }

    @Override
    public Optional<AtfmRevisionBaseline> findCrossStoreRevisionBaseline(SchedulePermit permit) {
        // Only relevant for the multi-day non-scheduled routing rule: the
        // permit currently routes to SC storage, but its base permit may
        // still be sitting in the NO table (e.g. it was originally a
        // single-day permit before this revision extended it to a range).
        if (permit.seasonalPermit() || !permit.usesScheduledStorage()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection()) {
            return findNoRevisionBaseline(connection, permit);
        } catch (SQLException exception) {
            throw databaseFailure("read cross-store revision baseline", exception);
        }
    }

    @Override
    public Set<Integer> findUsedRevisionNumbers(SchedulePermit permit) {
        String baseId = permit.normalizedPermitId();
        Integer year = permit.permitYear();
        if (!permit.seasonalPermit() || baseId == null || year == null) {
            return Set.of();
        }
        Pattern revisionFamily = revisionFamilyPattern(baseId);
        if (revisionFamily == null) {
            return Set.of();
        }
        Set<Integer> used = new HashSet<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SC_PERMIT_IDS_BY_YEAR_SQL)) {
            statement.setString(1, Integer.toString(year));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String candidate = canonicalPermitId(resultSet.getString(1));
                    java.util.regex.Matcher matcher = revisionFamily.matcher(candidate);
                    if (matcher.matches()) {
                        String number = matcher.group("number");
                        if (number == null) {
                            number = matcher.group("legacyNumber");
                        }
                        used.add(Integer.valueOf(number));
                    }
                }
            }
            return Set.copyOf(used);
        } catch (SQLException exception) {
            throw databaseFailure("read used revision numbers", exception);
        }
    }

    private Pattern revisionFamilyPattern(String basePermitId) {
        String canonical = canonicalPermitId(basePermitId);
        int slash = canonical.startsWith("O/F") ? canonical.indexOf('/', 3) : canonical.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String head = canonical.substring(0, slash);
        java.util.regex.Matcher suffix = Pattern.compile("(?<digits>\\d+)(?<version>[A-Z]?)$")
                .matcher(head);
        if (!suffix.find()) {
            return null;
        }
        String permitNumber = head.substring(0, suffix.start()) + suffix.group("digits");
        String version = suffix.group("version");
        String afterRevision = canonical.substring(slash);
        return Pattern.compile("^" + Pattern.quote(permitNumber)
                + "(?:" + Pattern.quote(version) + "R(?<number>\\d+)"
                + "|R(?<legacyNumber>\\d+)" + Pattern.quote(version) + ")"
                + Pattern.quote(afterRevision) + "$");
    }

    private String canonicalPermitId(String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[\\s-]+", "");
    }

    private Optional<AtfmRevisionBaseline> findNoRevisionBaseline(Connection connection,
                                                                   SchedulePermit permit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_NO_BASELINE_SQL)) {
            statement.setString(1, noBasePermitId(permit));
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long masterId = rows.getLong("ID");
                long permId = rows.getLong("PERM_ID");
                List<ScheduleFlight> flights = readExistingNoFlights(connection, permId).stream()
                        .map(ExistingFlight::toScheduleFlight)
                        .toList();
                return Optional.of(new AtfmRevisionBaseline(
                        masterId, permId, rows.getString("OPER_ID"), flights));
            }
        }
    }

    @Override
    public AtfmWriteResult insert(SchedulePermit permit) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AtfmWriteResult result = insertWithinTransaction(connection, permit);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseFailure("insert scheduled permit", exception);
        }
    }

    @Override
    public AtfmWriteResult update(SchedulePermit permit) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AtfmWriteResult result = updateWithinTransaction(connection, permit);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseFailure("update scheduled permit", exception);
        }
    }

    private AtfmWriteResult updateWithinTransaction(Connection connection,
                                                    SchedulePermit permit) throws SQLException {
        List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
        if (permit.subjectCorrection()) {
            return replaceAllDetailsForCorrection(connection, permit, resolvedFlights);
        }
        List<ScheduleFlight> resolvedOriginalFlights = resolveAirportCodes(
                connection, permit.originalFlights());
        SchedulePermit resolvedPermit = permit.withFlightsAndOriginals(
                resolvedFlights, resolvedOriginalFlights);
        List<ScheduleFlight> noFlights = noFlights(resolvedPermit, resolvedFlights);
        List<ScheduleFlight> scheduledFlights = scheduledFlights(resolvedPermit, resolvedFlights);
        AtfmWriteResult scheduledResult = scheduledFlights.isEmpty()
                ? null
                : updateScWithinTransaction(connection, resolvedPermit.withFlights(scheduledFlights));
        AtfmWriteResult noResult = noFlights.isEmpty()
                ? null
                : upsertNoWithinTransaction(connection, resolvedPermit.withFlights(noFlights), noFlights);
        return combine(scheduledResult, noResult);
    }

    private AtfmWriteResult replaceAllDetailsForCorrection(Connection connection,
                                                           SchedulePermit permit,
                                                           List<ScheduleFlight> resolvedFlights)
            throws SQLException {
        if (resolvedFlights.isEmpty()) {
            throw new AtfmReferenceDataException(
                    "Subject correction has no validated replacement detail rows");
        }
        validateReferenceData(connection, permit.withFlights(resolvedFlights));
        if (permit.usesScheduledStorage()) {
            return replaceScheduledDetailsForCorrection(connection, permit, resolvedFlights);
        }
        return replaceNonScheduledDetailsForCorrection(connection, permit, resolvedFlights);
    }

    private AtfmWriteResult replaceScheduledDetailsForCorrection(Connection connection,
                                                                  SchedulePermit permit,
                                                                  List<ScheduleFlight> flights)
            throws SQLException {
        long masterId;
        long permId;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_EXISTING_SQL)) {
            statement.setString(1, permit.atfmTargetPermitId());
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AtfmReferenceDataException(
                            "Subject correction master not found: " + permit.atfmTargetPermitId());
                }
                masterId = rows.getLong("ID");
                permId = rows.getLong("PERM_ID");
            }
        }
        updateScheduledMaster(connection, masterId, permit);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM T_PERMDETAIL_SC WHERE PERM_ID = ?")) {
            delete.setLong(1, permId);
            delete.executeUpdate();
        }
        insertScheduledDetails(connection, permId, flights);
        return new AtfmWriteResult(masterId, permId, flights.size());
    }

    private AtfmWriteResult replaceNonScheduledDetailsForCorrection(Connection connection,
                                                                     SchedulePermit permit,
                                                                     List<ScheduleFlight> flights)
            throws SQLException {
        long masterId;
        long permId;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_NO_MASTER_SQL)) {
            statement.setString(1, noPermitId(permit));
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AtfmReferenceDataException(
                            "Subject correction master not found: " + noPermitId(permit));
                }
                masterId = rows.getLong("ID");
                permId = rows.getLong("PERM_ID");
            }
        }
        updateNoCorrectionMaster(connection, masterId, permit);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM T_PERMDETAIL_NO WHERE PERM_ID = ?")) {
            delete.setLong(1, permId);
            delete.executeUpdate();
        }
        insertNonScheduledDetails(connection, permId, flights);
        return new AtfmWriteResult(masterId, permId, flights.size());
    }

    private void updateScheduledMaster(Connection connection,
                                       long masterId,
                                       SchedulePermit permit) throws SQLException {
        String billingAddress = operatorName(connection, permit);
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_MASTER_SQL)) {
            int index = 1;
            statement.setString(index++, permit.authorId());
            statement.setString(index++, permit.permitType());
            statement.setString(index++, permit.permitNumber());
            statement.setString(index++, permit.version());
            statement.setString(index++, permit.season());
            statement.setDate(index++, Date.valueOf(permit.permitDate()));
            statement.setString(index++, permit.operatorId());
            statement.setString(index++, truncateUtf8(masterReference(permit), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, "AEROSYNC");
            statement.setString(index++, "0");
            statement.setString(index++, permit.seasonalPermit()
                    ? null : truncateUtf8(permit.rawContent(), 4000));
            setFullContent(statement, index++, permit.seasonalPermit() ? null : permit.rawContent());
            statement.setString(index++, truncateUtf8(billingAddress, 4000));
            statement.setString(index++, permit.flightType());
            statement.setLong(index, masterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Subject correction master update affected an unexpected number of rows");
            }
        }
    }

    private void updateNoCorrectionMaster(Connection connection,
                                          long masterId,
                                          SchedulePermit permit) throws SQLException {
        String reference = masterReference(permit);
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_NO_REVISION_MASTER_SQL)) {
            statement.setString(1, truncateUtf8(reference, 4000));
            statement.setString(2, truncateUtf8(permit.rawContent(), 4000));
            if (permit.rawContent() == null) {
                statement.setNull(3, Types.CLOB);
            } else {
                statement.setCharacterStream(3, new StringReader(permit.rawContent()),
                        permit.rawContent().length());
            }
            statement.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(5, "AEROSYNC");
            statement.setLong(6, masterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Subject correction NO master update affected an unexpected number of rows");
            }
        }
    }

    private void insertScheduledDetails(Connection connection,
                                        long permId,
                                        List<ScheduleFlight> flights) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_DETAIL_SQL)) {
            for (ScheduleFlight flight : flights) {
                bindDetail(statement, permId, flight);
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("Subject correction SC detail insert failed");
                }
            }
        }
    }

    private void insertNonScheduledDetails(Connection connection,
                                           long permId,
                                           List<ScheduleFlight> flights) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_NO_DETAIL_SQL)) {
            for (ScheduleFlight flight : flights) {
                bindNoDetail(statement, permId, flight);
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("Subject correction NO detail insert failed");
                }
            }
        }
    }

    private AtfmWriteResult updateScWithinTransaction(Connection connection,
                                                      SchedulePermit permit) throws SQLException {
        List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
        validateReferenceData(connection, permit);
        long masterId;
        long permId;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_EXISTING_SQL)) {
            statement.setString(1, permit.atfmTargetPermitId());
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AtfmReferenceDataException(
                            "Revision base permit not found in ATFM: " + permit.atfmTargetPermitId());
                }
                masterId = resultSet.getLong("ID");
                permId = resultSet.getLong("PERM_ID");
            }
        }

        if (permit.multiDayNonScheduledRevision()) {
            return appendMultiDayNoRevisionToSc(connection, masterId, permId, permit, resolvedFlights);
        }

        if (!permit.importRevision()) {
            String billingAddress = operatorName(connection, permit);
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_MASTER_SQL)) {
                int index = 1;
                statement.setString(index++, permit.authorId());
                statement.setString(index++, permit.permitType());
                statement.setString(index++, permit.permitNumber());
                statement.setString(index++, permit.version());
                statement.setString(index++, permit.season());
                statement.setDate(index++, Date.valueOf(permit.permitDate()));
                statement.setString(index++, permit.operatorId());
                statement.setString(index++, truncateUtf8(masterReference(permit), 4000));
                statement.setInt(index++, permit.validHours());
                statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
                statement.setString(index++, "AEROSYNC");
                statement.setString(index++, "0");
                statement.setString(index++, permit.seasonalPermit()
                        ? null : truncateUtf8(permit.rawContent(), 4000));
                setFullContent(statement, index++, permit.seasonalPermit() ? null : permit.rawContent());
                statement.setString(index++, truncateUtf8(billingAddress, 4000));
                statement.setString(index++, permit.flightType());
                statement.setLong(index, masterId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Permit master update affected an unexpected number of rows");
                }
            }
        }
        List<ExistingFlight> existingFlights = readExistingFlights(connection, permId);
        List<ScheduleFlight> missingFlights = missingFlights(existingFlights, resolvedFlights);
        if (missingFlights.isEmpty()) {
            return new AtfmWriteResult(masterId, permId, 0);
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_DETAIL_SQL)) {
            for (ScheduleFlight flight : missingFlights) {
                bindDetail(statement, permId, flight);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            for (int count : counts) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("ATFM revision detail batch reported a failed row");
                }
            }
        }
        return new AtfmWriteResult(masterId, permId, missingFlights.size());
    }

    private AtfmWriteResult appendMultiDayNoRevisionToSc(Connection connection,
                                                          long masterId,
                                                          long permId,
                                                          SchedulePermit permit,
                                                          List<ScheduleFlight> rows) throws SQLException {
        if (permit.originalFlights().isEmpty() || rows.size() <= permit.originalFlights().size()) {
            throw new AtfmReferenceDataException(
                    "Multi-day non-scheduled revision requires OLD and NEW rows");
        }
        List<ExistingFlight> stored = readExistingFlights(connection, permId);
        verifyMultiDayOldRowsStillMatch(stored, permit.originalFlights());
        if (multiDayRevisionAlreadyApplied(connection, masterId, stored, rows, permit)) {
            return new AtfmWriteResult(masterId, permId, 0);
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_DETAIL_SQL)) {
            for (ScheduleFlight flight : rows) {
                bindDetail(statement, permId, flight);
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("Multi-day non-scheduled revision SC append failed");
                }
            }
        }
        appendScRevisionMaster(connection, masterId, permit);
        return new AtfmWriteResult(masterId, permId, rows.size());
    }

    private void verifyMultiDayOldRowsStillMatch(List<ExistingFlight> stored,
                                                 List<ScheduleFlight> oldRows)
            throws SQLException {
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        for (ScheduleFlight old : oldRows) {
            List<Integer> matches = new ArrayList<>();
            for (int index = 0; index < stored.size(); index++) {
                if (sameScOldIdentity(stored.get(index), old)
                        && !isCancelledRevisionRow(stored.get(index).remark())) {
                    matches.add(index);
                }
            }
            if (matches.isEmpty() || !consumed.add(matches.getLast())) {
                throw new AtfmReferenceDataException(
                        "Referenced SC permit OLD row is missing or repeated: "
                                + old.flightNumber());
            }
            ExistingFlight original = stored.get(matches.getLast());
            if (old.craftId() > 0 && old.craftId() != original.craftId()) {
                throw new AtfmReferenceDataException(
                        "Referenced SC permit OLD aircraft changed before revision append");
            }
            if (old.purposeId() != null && !Objects.equals(
                    normalize(old.purposeId()), normalize(original.purposeId()))) {
                throw new AtfmReferenceDataException(
                        "Referenced SC permit OLD purpose changed before revision append");
            }
        }
    }

    private boolean sameScOldIdentity(ExistingFlight stored, ScheduleFlight old) {
        return Objects.equals(normalize(stored.flightNumber()), normalize(old.flightNumber()))
                && Objects.equals(normalize(stored.fromAirport()), normalize(old.fromAirport()))
                && Objects.equals(normalize(stored.toAirport()), normalize(old.toAirport()))
                && Objects.equals(stored.beginDate(), old.beginDate())
                && Objects.equals(stored.endDate(), old.endDate())
                && Objects.equals(normalize(stored.etd()), normalize(old.etd()))
                && ((old.eta() == null || old.eta().isBlank())
                        || Objects.equals(normalize(stored.eta()), normalize(old.eta())));
    }

    private boolean isCancelledRevisionRow(String remark) {
        return remark != null && remark.trim().toUpperCase(java.util.Locale.ROOT).startsWith("HUY ");
    }

    private boolean multiDayRevisionAlreadyApplied(Connection connection,
                                                     long masterId,
                                                     List<ExistingFlight> stored,
                                                     List<ScheduleFlight> rows,
                                                     SchedulePermit permit) throws SQLException {
        boolean oldRowsPresent = rows.subList(0, permit.originalFlights().size()).stream()
                .allMatch(old -> stored.stream().anyMatch(existing ->
                        sameScOldIdentity(existing, old)
                                && Objects.equals(normalize(existing.remark()), normalize(old.remark()))));
        boolean newRowsPresent = rows.subList(permit.originalFlights().size(), rows.size()).stream()
                .allMatch(newRow -> stored.stream().anyMatch(existing ->
                        existing.equals(ExistingFlight.fromExpected(newRow))));
        if (!oldRowsPresent || !newRowsPresent) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_SC_REVISION_MASTER_CONTENT_SQL)) {
            statement.setLong(1, masterId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                String fullContent = result.getString("PERMCONTENT1");
                return fullContent != null && fullContent.contains(permit.rawContent());
            }
        }
    }

    private void appendScRevisionMaster(Connection connection,
                                        long masterId,
                                        SchedulePermit permit) throws SQLException {
        String existingReference;
        String existingShortContent;
        String existingFullContent;
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_SC_REVISION_MASTER_CONTENT_SQL)) {
            statement.setLong(1, masterId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Multi-day revision SC master disappeared while locked");
                }
                existingReference = rows.getString("REFERENCE");
                existingShortContent = rows.getString("PERMCONTENT");
                existingFullContent = rows.getString("PERMCONTENT1");
            }
        }
        String reference = appendDistinct(existingReference, permit.revisionReference());
        String shortContent = truncateUtf8(
                appendDistinct(existingShortContent, permit.rawContent()), 4000);
        String fullContent = appendDistinct(existingFullContent, permit.rawContent());
        try (PreparedStatement statement = connection.prepareStatement(
                UPDATE_SC_REVISION_MASTER_SQL)) {
            statement.setString(1, reference);
            statement.setString(2, shortContent);
            if (fullContent == null) {
                statement.setNull(3, Types.CLOB);
            } else {
                statement.setCharacterStream(3, new StringReader(fullContent), fullContent.length());
            }
            statement.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(5, "AEROSYNC");
            statement.setLong(6, masterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Multi-day revision SC master update affected an unexpected row count");
            }
        }
    }

    private List<ExistingFlight> readExistingFlights(Connection connection,
                                                      long permId) throws SQLException {
        String sql = """
                SELECT PURPOSE_ID, CRAFT_ID, MTOW, FLIGHTNBR, REGISTRATION,
                       DAY1, DAY2, DAY3, DAY4, DAY5, DAY6, DAY7,
                       FROM_AIRP, TO_AIRP, ETD, ETA, VIA, BEGINDATE, ENDDATE, REMARK
                  FROM T_PERMDETAIL_SC
                 WHERE PERM_ID = ?
                """;
        List<ExistingFlight> flights = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    flights.add(toExistingFlight(resultSet));
                }
            }
        }
        return flights;
    }

    private AtfmWriteResult insertWithinTransaction(Connection connection,
                                                    SchedulePermit permit) throws SQLException {
        List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
        List<ScheduleFlight> noFlights = noFlights(permit, resolvedFlights);
        List<ScheduleFlight> scheduledFlights = scheduledFlights(permit, resolvedFlights);
        AtfmWriteResult scheduledResult = scheduledFlights.isEmpty()
                ? null
                : insertScWithinTransaction(connection, permit.withFlights(scheduledFlights));
        AtfmWriteResult noResult = noFlights.isEmpty()
                ? null
                : upsertNoWithinTransaction(connection, permit.withFlights(noFlights), noFlights);
        return combine(scheduledResult, noResult);
    }

    private AtfmWriteResult insertScWithinTransaction(Connection connection,
                                                      SchedulePermit permit) throws SQLException {
        List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
        validateReferenceData(connection, permit);
        ensurePermitNumberIsAvailable(
                connection, permit.normalizedPermitId(), requiredPermitYear(permit));
        String billingAddress = operatorName(connection, permit);
        long masterId;
        long permId;
        try (CallableStatement statement = connection.prepareCall(INSERT_MASTER_SQL)) {
            int index = 1;
            statement.setString(index++, permit.normalizedPermitId());
            statement.setString(index++, permit.authorId());
            statement.setString(index++, permit.permitType());
            statement.setString(index++, permit.permitNumber());
            statement.setString(index++, permit.version());
            statement.setString(index++, permit.season());
            statement.setDate(index++, Date.valueOf(permit.permitDate()));
            statement.setString(index++, permit.operatorId());
            statement.setString(index++, truncateUtf8(masterReference(permit), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, "AEROSYNC");
            statement.setString(index++, "0");
            statement.setString(index++, permit.seasonalPermit()
                    ? null : truncateUtf8(permit.rawContent(), 4000));
            setFullContent(statement, index++, permit.seasonalPermit() ? null : permit.rawContent());
            statement.setString(index++, truncateUtf8(billingAddress, 4000));
            statement.setString(index++, permit.flightType());
            statement.registerOutParameter(index++, Types.NUMERIC);
            statement.registerOutParameter(index, Types.NUMERIC);
            statement.execute();
            masterId = statement.getLong(index - 1);
            permId = statement.getLong(index);
        }

        try (PreparedStatement statement = connection.prepareStatement(INSERT_DETAIL_SQL)) {
            for (ScheduleFlight flight : resolvedFlights) {
                bindDetail(statement, permId, flight);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            for (int count : counts) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("ATFM detail batch reported a failed row");
                }
            }
        }
        return new AtfmWriteResult(masterId, permId, resolvedFlights.size());
    }

    private Optional<AtfmPermitSnapshot> findExistingNo(Connection connection,
                                                         SchedulePermit permit,
                                                         List<ScheduleFlight> expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_NO_MASTER_SQL)) {
            statement.setString(1, noPermitId(permit));
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long masterId = rows.getLong("ID");
                long permId = rows.getLong("PERM_ID");
                List<ExistingFlight> actual = readExistingNoFlights(connection, permId);
                boolean referenceMatches = Objects.equals(
                        normalize(rows.getString("REFERENCE")),
                        normalize(masterReference(permit)));
                boolean matches = permit.replacesNonScheduledRows()
                        ? false // Always validate OLD/receipt under the master lock before declaring a retry.
                        : permit.importRevision() ? flightsContainComplete(actual, expected)
                        : flightsMatch(actual, expected) && referenceMatches;
                return Optional.of(new AtfmPermitSnapshot(masterId, permId, matches));
            }
        }
    }

    private AtfmWriteResult upsertNoWithinTransaction(Connection connection,
                                                       SchedulePermit permit,
                                                       List<ScheduleFlight> flights)
            throws SQLException {
        validateReferenceData(connection, permit);
        long masterId;
        long permId;
        boolean existingMaster = false;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_NO_MASTER_SQL)) {
            statement.setString(1, noPermitId(permit));
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    existingMaster = true;
                    masterId = rows.getLong("ID");
                    permId = rows.getLong("PERM_ID");
                } else {
                    if (permit.replacesNonScheduledRows()) throw new AtfmReferenceDataException(
                            "PERMNO revision base was not found; refusing to create a replacement master");
                    long[] ids = insertNoMaster(connection, permit);
                    masterId = ids[0];
                    permId = ids[1];
                }
            }
        }

        if (permit.replacesNonScheduledRows()) {
            return replaceNoDetails(connection, masterId, permId, permit, flights);
        }
        if (existingMaster && !permit.importRevision()) {
            updateNoReference(connection, masterId, permit);
        }

        List<ExistingFlight> existing = readExistingNoFlights(connection, permId);
        List<ScheduleFlight> missing = missingFlights(existing, flights);
        if (missing.isEmpty()) return new AtfmWriteResult(masterId, permId, 0);
        try (PreparedStatement statement = connection.prepareStatement(INSERT_NO_DETAIL_SQL)) {
            for (ScheduleFlight flight : missing) {
                bindNoDetail(statement, permId, flight);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            for (int count : counts) {
                if (count == PreparedStatement.EXECUTE_FAILED) {
                    throw new SQLException("ATFM non-scheduled detail batch reported a failed row");
                }
            }
        }
        return new AtfmWriteResult(masterId, permId, missing.size());
    }

    private AtfmWriteResult replaceNoDetails(Connection connection, long masterId, long permId,
                                              SchedulePermit permit, List<ScheduleFlight> replacements) throws SQLException {
        if (permit.originalFlights().isEmpty()) throw new AtfmReferenceDataException(
                "PERMNO replacement requires the documented OLD schedule");
        if (replacements.isEmpty()) throw new AtfmReferenceDataException("PERMNO replacement has no NEW rows");
        for (int i = 0; i < replacements.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (ExistingFlight.fromExpected(replacements.get(i)).sameBusinessRow(
                        ExistingFlight.fromExpected(replacements.get(j)))) throw new AtfmReferenceDataException(
                        "PERMNO NEW table repeats the same business row");
            }
        }
        List<NoStoredRow> stored = readNoStoredRows(connection, permId);
        List<ExistingFlight> actual = readExistingNoFlights(connection, permId);
        boolean completeNew = flightsContainComplete(actual, replacements);
        boolean applied = noRevisionAlreadyApplied(connection, masterId, permit);
        java.util.Set<Long> deleteIds = new java.util.LinkedHashSet<>();
        int missingOld = 0;
        for (ScheduleFlight old : permit.originalFlights()) {
            List<NoStoredRow> matches = stored.stream().filter(row ->
                    vatm.aerosync.worker.model.NoRevisionIdentity.matches(row.flight(), old)).toList();
            if (matches.isEmpty()) { missingOld++; continue; }
            if (matches.size() != 1 || !deleteIds.add(matches.getFirst().id())) throw new AtfmReferenceDataException(
                    "PERMNO OLD row is ambiguous or repeated: " + old.flightNumber());
            if (matches.getFirst().dateCount() != 1) throw new AtfmReferenceDataException(
                    "PERMNO OLD row contains multiple stored dates; refusing to delete unrelated dates");
        }
        // Receipt + full NEW values + no residual OLD is a committed retry.
        // Equal OLD/NEW schedules are also idempotent when every matched OLD is NEW.
        if (applied && completeNew && (missingOld == permit.originalFlights().size()
                || (missingOld == 0 && stored.stream().filter(row -> deleteIds.contains(row.id()))
                    .allMatch(row -> replacements.stream().anyMatch(f ->
                            ExistingFlight.fromExpected(row.flight()).equals(ExistingFlight.fromExpected(f))))))) {
            return new AtfmWriteResult(masterId, permId, 0);
        }
        if (missingOld != 0) throw new AtfmReferenceDataException(
                "PERMNO OLD schedule is missing or partially applied; no rows were changed");
        for (ScheduleFlight old : permit.originalFlights()) {
            ScheduleFlight snapshot = stored.stream().filter(row ->
                    vatm.aerosync.worker.model.NoRevisionIdentity.matches(row.flight(), old)).findFirst().orElseThrow().flight();
            if (old.craftId() > 0 && (snapshot.craftId() != old.craftId()
                    || !Objects.equals(normalize(snapshot.purposeId()), normalize(old.purposeId()))))
                throw new AtfmReferenceDataException("PERMNO baseline attributes changed after reconciliation; retry from source");
        }
        // Existing identical NEW rows outside the OLD set are a conflict, not a reason
        // to silently leave OLD behind or insert another copy.
        if (stored.stream().filter(row -> !deleteIds.contains(row.id())).anyMatch(row ->
                replacements.stream().anyMatch(f -> ExistingFlight.fromExpected(row.flight())
                        .sameBusinessRow(ExistingFlight.fromExpected(f))))) throw new AtfmReferenceDataException(
                "PERMNO NEW row already exists outside the documented OLD set");
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM T_PERMDETAIL_NO WHERE PERM_ID = ? AND ID = ?")) {
            for (long id : deleteIds) {
                delete.setLong(1, permId); delete.setLong(2, id);
                if (delete.executeUpdate() != 1) throw new SQLException("PERMNO OLD row changed during replacement");
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_NO_DETAIL_SQL)) {
            for (ScheduleFlight replacement : replacements) {
                bindNoDetail(insert, permId, replacement);
                if (insert.executeUpdate() != 1) throw new SQLException("PERMNO NEW insert failed");
            }
        }
        appendNoRevisionMaster(connection, masterId, permit);
        return new AtfmWriteResult(masterId, permId, replacements.size());
    }

    private boolean noRevisionAlreadyApplied(Connection connection, long masterId, SchedulePermit permit)
            throws SQLException {
        if (permit.rawContent() == null || permit.rawContent().isBlank()) return false;
        try (PreparedStatement statement = connection.prepareStatement(FIND_NO_REVISION_MASTER_CONTENT_SQL)) {
            statement.setLong(1, masterId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                String full = rows.getString("PERMCONTENT1");
                if (full == null) return false;
                String normalized = full.replace("\r\n", "\n").trim();
                String expected = permit.rawContent().replace("\r\n", "\n").trim();
                return normalized.equals(expected) || normalized.startsWith(expected + "\n")
                        || normalized.endsWith("\n" + expected) || normalized.contains("\n" + expected + "\n");
            }
        }
    }

    private List<NoStoredRow> readNoStoredRows(Connection connection, long permId) throws SQLException {
        List<NoStoredRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID, PURPOSE_ID, CRAFT_ID, MTOW, "
                + "DAYSFLIGHT, FLIGHTNBR, REGISTRATION, FROM_AIRP, TO_AIRP, ETD, ETA, VIA, REMARK "
                + "FROM T_PERMDETAIL_NO WHERE PERM_ID = ? FOR UPDATE")) {
            statement.setLong(1, permId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<LocalDate> dates = parseNoFlightDates(rows.getString("DAYSFLIGHT"));
                    for (LocalDate date : dates) result.add(new NoStoredRow(rows.getLong("ID"), dates.size(),
                            new ScheduleFlight(normalize(rows.getString("PURPOSE_ID")), rows.getLong("CRAFT_ID"),
                                    defaultZero(rows.getBigDecimal("MTOW")), normalize(rows.getString("FLIGHTNBR")),
                                    normalize(rows.getString("REGISTRATION")), serviceDayFor(date),
                                    normalize(rows.getString("FROM_AIRP")), normalize(rows.getString("TO_AIRP")),
                                    normalize(rows.getString("ETD")), normalize(rows.getString("ETA")),
                                    viaForComparison(rows.getString("VIA")), date, date, normalize(rows.getString("REMARK")))));
                }
            }
        }
        return result;
    }

    private record NoStoredRow(long id, int dateCount, ScheduleFlight flight) {}

    private void appendNoRevisionMaster(Connection connection,
                                        long masterId,
                                        SchedulePermit permit) throws SQLException {
        String existingReference;
        String existingShortContent;
        String existingFullContent;
        try (PreparedStatement statement = connection.prepareStatement(FIND_NO_REVISION_MASTER_CONTENT_SQL)) {
            statement.setLong(1, masterId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Non-scheduled revision master disappeared while locked");
                }
                existingReference = rows.getString("REFERENCE");
                existingShortContent = rows.getString("PERMCONTENT");
                existingFullContent = rows.getString("PERMCONTENT1");
            }
        }
        String reference = appendDistinct(existingReference, permit.revisionReference());
        String shortContent = truncateUtf8(
                appendDistinct(existingShortContent, permit.rawContent()), 4000);
        String fullContent = appendDistinct(existingFullContent, permit.rawContent());
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_NO_REVISION_MASTER_SQL)) {
            statement.setString(1, reference);
            statement.setString(2, shortContent);
            if (fullContent == null) {
                statement.setNull(3, Types.CLOB);
            } else {
                statement.setCharacterStream(3, new StringReader(fullContent), fullContent.length());
            }
            statement.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(5, "AEROSYNC");
            statement.setLong(6, masterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Non-scheduled revision master update affected an unexpected number of rows");
            }
        }
    }

    private String appendDistinct(String existing, String addition) {
        String base = existing == null ? "" : existing.trim();
        String next = addition == null ? "" : addition.trim();
        if (next.isEmpty() || java.util.Arrays.stream(base.split("\\R"))
                .anyMatch(line -> line.trim().equals(next))) {
            return base.isEmpty() ? null : base;
        }
        return base.isEmpty() ? next : base + System.lineSeparator() + next;
    }

    private long[] insertNoMaster(Connection connection, SchedulePermit permit) throws SQLException {
        String billingAddress = operatorName(connection, permit);
        try (CallableStatement statement = connection.prepareCall(INSERT_NO_MASTER_SQL)) {
            int index = 1;
            statement.setString(index++, noPermitId(permit));
            statement.setString(index++, permit.authorId());
            statement.setString(index++, permit.permitType());
            statement.setString(index++, permit.permitNumber());
            statement.setDate(index++, Date.valueOf(permit.permitDate()));
            statement.setString(index++, permit.operatorId());
            statement.setString(index++, truncateUtf8(masterReference(permit), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setString(index++, "0");
            statement.setString(index++, "AEROSYNC");
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, truncateUtf8(billingAddress, 4000));
            statement.setString(index++, truncateUtf8(permit.rawContent(), 4000));
            setFullContent(statement, index++, permit.rawContent());
            statement.setString(index++, billingAddress);
            statement.setString(index++, permit.version());
            statement.setString(index++, "NO");
            statement.registerOutParameter(index++, Types.NUMERIC);
            statement.registerOutParameter(index, Types.NUMERIC);
            statement.execute();
            return new long[] {statement.getLong(index - 1), statement.getLong(index)};
        }
    }

    private void bindNoDetail(PreparedStatement statement,
                              long permId,
                              ScheduleFlight flight) throws SQLException {
        int index = 1;
        statement.setLong(index++, permId);
        setNullableAircraftId(statement, index++, flight.craftId());
        statement.setBigDecimal(index++, flight.mtow());
        statement.setString(index++, noFlightDatesForStorage(flight));
        statement.setString(index++, flight.flightNumber());
        setNullableString(statement, index++, registrationForStorage(flight.registration()));
        statement.setString(index++, flight.fromAirport());
        statement.setString(index++, flight.toAirport());
        statement.setString(index++, flight.etd());
        setNullableString(statement, index++, flight.eta());
        statement.setString(index++, viaForStorage(flight.via()));
        statement.setString(index++, "0");
        statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
        statement.setString(index++, "AEROSYNC");
        statement.setString(index++, flight.purposeId());
        statement.setDate(index++, Date.valueOf(flight.endDate()));
        statement.setString(index, truncateUtf8(flight.remark(), 4000));
    }

    @Override
    public Optional<String> findReferencedSeason(SchedulePermit permit) {
        if (permit.referencedPermitId() == null || permit.referencedPermitId().isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_REFERENCED_SEASON_SQL)) {
            statement.setString(1, permit.referencedPermitId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String season = normalize(rows.getString("SEASON"));
                return season.matches("[SW]") ? Optional.of(season) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw databaseFailure("read referenced permit season", exception);
        }
    }

    private void updateNoReference(Connection connection,
                                   long masterId,
                                   SchedulePermit permit) throws SQLException {
        String reference = masterReference(permit);
        if (reference == null || reference.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_NO_REFERENCE_SQL)) {
            statement.setString(1, truncateUtf8(reference, 4000));
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(3, "AEROSYNC");
            statement.setLong(4, masterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Non-scheduled master reference update affected an unexpected number of rows");
            }
        }
    }

    private String noFlightDates(ScheduleFlight flight) {
        if (flight.beginDate() == null) {
            return "";
        }
        LocalDate end = flight.endDate() == null ? flight.beginDate() : flight.endDate();
        String serviceDays = flight.serviceDays() == null ? "" : flight.serviceDays();
        List<String> dates = new ArrayList<>();
        for (LocalDate date = flight.beginDate(); !date.isAfter(end); date = date.plusDays(1)) {
            int weekday = date.getDayOfWeek().getValue() - 1;
            if (serviceDays.length() != 7 || serviceDays.charAt(weekday) != '0') {
                dates.add(date.format(NO_FLIGHT_DATE).toUpperCase(java.util.Locale.ENGLISH));
            }
        }
        if (dates.isEmpty()) {
            dates.add(flight.beginDate().format(NO_FLIGHT_DATE)
                    .toUpperCase(java.util.Locale.ENGLISH));
        }
        return String.join(",", dates);
    }

    private String noFlightDatesForStorage(ScheduleFlight flight) {
        return truncateDelimited(noFlightDates(flight), NO_DAYSFLIGHT_MAX_BYTES, ",");
    }

    private String registrationForStorage(String registration) {
        return null;
    }

    static String truncateDelimited(String value, int maxBytes, String delimiterPattern) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        String[] entries = value.split(delimiterPattern);
        StringBuilder result = new StringBuilder();
        for (String entry : entries) {
            String normalized = entry.trim();
            if (normalized.isEmpty()) continue;
            String candidate = result.isEmpty() ? normalized : result + "," + normalized;
            if (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) break;
            result.setLength(0);
            result.append(candidate);
        }
        return result.isEmpty() ? truncateUtf8(value, maxBytes) : result.toString();
    }

    private List<ExistingFlight> readExistingNoFlights(Connection connection,
                                                        long permId) throws SQLException {
        String sql = """
                SELECT PURPOSE_ID, CRAFT_ID, MTOW, DAYSFLIGHT, FLIGHTNBR,
                       REGISTRATION, FROM_AIRP, TO_AIRP, ETD, ETA, VIA, REMARK
                  FROM T_PERMDETAIL_NO
                 WHERE PERM_ID = ?
                """;
        List<ExistingFlight> flights = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (LocalDate date : parseNoFlightDates(rows.getString("DAYSFLIGHT"))) {
                        flights.add(new ExistingFlight(
                                normalize(rows.getString("PURPOSE_ID")),
                                rows.getLong("CRAFT_ID"),
                                defaultZero(rows.getBigDecimal("MTOW")),
                                normalize(rows.getString("FLIGHTNBR")),
                                normalize(rows.getString("REGISTRATION")),
                                serviceDayFor(date),
                                normalize(rows.getString("FROM_AIRP")),
                                normalize(rows.getString("TO_AIRP")),
                                normalize(rows.getString("ETD")),
                                normalize(rows.getString("ETA")),
                                viaForComparison(rows.getString("VIA")),
                                date, date, normalize(rows.getString("REMARK"))));
                    }
                }
            }
        }
        return flights;
    }

    private List<LocalDate> parseNoFlightDates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        for (String token : value.split("[,;]")) {
            String candidate = token.trim();
            for (String pattern : List.of("d-MMM-uuuu", "d-M-uuuu", "d/M/uuuu")) {
                try {
                    dates.add(LocalDate.parse(candidate, new DateTimeFormatterBuilder()
                            .parseCaseInsensitive().appendPattern(pattern)
                            .toFormatter(java.util.Locale.ENGLISH)));
                    break;
                } catch (DateTimeParseException ignored) {
                    // Try the next legacy representation.
                }
            }
        }
        return dates;
    }

    private String serviceDayFor(LocalDate date) {
        char[] days = "0000000".toCharArray();
        int index = date.getDayOfWeek().getValue() - 1;
        days[index] = (char) ('1' + index);
        return new String(days);
    }

    private List<ScheduleFlight> noFlights(SchedulePermit permit, List<ScheduleFlight> flights) {
        return permit.usesScheduledStorage() ? List.of() : flights;
    }

    private List<ScheduleFlight> scheduledFlights(SchedulePermit permit, List<ScheduleFlight> flights) {
        return permit.usesScheduledStorage() ? flights : List.of();
    }

    private String noPermitId(SchedulePermit permit) {
        String target = permit.atfmTargetPermitId();
        String season = permit.season();
        if (season != null && !season.isBlank()) {
            target = target.replaceFirst("/(?i:" + java.util.regex.Pattern.quote(season) + ")/", "/");
        }
        return target.replaceFirst("/(?i:S|W)/(?=[A-Z0-9]+/(?:19|20)\\d{2}$)", "/");
    }

    private String noBasePermitId(SchedulePermit permit) {
        String target = permit.revisionBasePermitId();
        String season = permit.season();
        if (season != null && !season.isBlank()) {
            target = target.replaceFirst("/(?i:" + java.util.regex.Pattern.quote(season) + ")/", "/");
        }
        return target.replaceFirst("/(?i:S|W)/(?=[A-Z0-9]+/(?:19|20)\\d{2}$)", "/");
    }

    private AtfmWriteResult combine(AtfmWriteResult scheduled, AtfmWriteResult nonScheduled) {
        if (scheduled == null) {
            return nonScheduled;
        }
        if (nonScheduled == null) {
            return scheduled;
        }
        return new AtfmWriteResult(
                scheduled.masterId(), scheduled.permId(),
                scheduled.detailCount() + nonScheduled.detailCount());
    }

    private List<ScheduleFlight> resolveAirportCodes(Connection connection,
                                                     List<ScheduleFlight> flights) throws SQLException {
        Map<String, String> resolvedCodes = new HashMap<>();
        List<ScheduleFlight> resolvedFlights = new ArrayList<>(flights.size());
        for (ScheduleFlight flight : flights) {
            String from = resolveAirportCode(connection, resolvedCodes, flight.fromAirport());
            String to = resolveAirportCode(connection, resolvedCodes, flight.toAirport());
            resolvedFlights.add(new ScheduleFlight(
                    flight.purposeId(),
                    flight.craftId(),
                    flight.mtow(),
                    flight.flightNumber(),
                    flight.registration(),
                    flight.serviceDays(),
                    from,
                    to,
                    flight.etd(),
                    flight.eta(),
                    flight.via(),
                    flight.beginDate(),
                    flight.endDate(),
                    flight.remark(),
                    flight.sourceAircraftType()));
        }
        return resolvedFlights;
    }

    private String resolveAirportCode(Connection connection,
                                      Map<String, String> resolvedCodes,
                                      String sourceCode) throws SQLException {
        String cached = resolvedCodes.get(sourceCode);
        if (cached != null) {
            return cached;
        }
        String resolved = airportCodeResolver.resolve(connection, sourceCode);
        resolvedCodes.put(sourceCode, resolved);
        return resolved;
    }

    private void validateReferenceData(Connection connection,
                                       SchedulePermit permit) throws SQLException {
        ensureLookupExists(connection, "M_FPAUTHOR", "AUTHOR_CODE", permit.authorId());
        ensureLookupExists(connection, "M_OPER", "OPER_ICAO", permit.operatorId());
        for (String purposeId : permit.flights().stream().map(ScheduleFlight::purposeId).distinct().toList()) {
            ensureLookupExists(connection, "M_FLY_PURPOSE", "PURPOSE_CODE", purposeId);
        }
        for (Long craftId : permit.flights().stream().map(ScheduleFlight::craftId).distinct().toList()) {
            ensureLookupExists(connection, "M_CRAFT", "ID", craftId);
        }
    }

    private String masterReference(SchedulePermit permit) {
        if (permit.reference() != null && !permit.reference().isBlank()) {
            return permit.reference().trim();
        }
        // REFERENCE is the permit's business reference, never the full Word body.
        // Profiles without an explicit (REF...) field still have the source permit number.
        return permit.sourcePermitNumber();
    }

    private String operatorName(Connection connection, SchedulePermit permit) throws SQLException {
        if (permit.billingAddress() != null && !permit.billingAddress().isBlank()) {
            return permit.billingAddress().trim();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT MAX(TRIM(OPER_NAME)) AS OPER_NAME
                  FROM M_OPER
                 WHERE UPPER(TRIM(OPER_ICAO)) = ?
                """)) {
            statement.setString(1, normalize(permit.operatorId()));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    String operatorName = rows.getString("OPER_NAME");
                    if (operatorName != null && !operatorName.isBlank()) {
                        return operatorName.trim();
                    }
                }
            }
        }
        throw new AtfmReferenceDataException(
                "Operator name not found in M_OPER for " + permit.operatorId());
    }

    private void ensureLookupExists(Connection connection,
                                    String table,
                                    String column,
                                    Object value) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getInt(1) != 1) {
                    throw new AtfmReferenceDataException(
                            "ATFM lookup not found: " + table + "." + column + "=" + value);
                }
            }
        }
    }

    private void ensurePermitNumberIsAvailable(Connection connection,
                                               String normalizedPermitId,
                                               String permitYear) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                        SELECT COUNT(*)
                          FROM T_PERMMASTER_SC
                         WHERE PERMNBR_ID = ?
                           AND SUBSTR(TRIM(PERMNBR_ID), -4) = ?
                        """)) {
            statement.setString(1, normalizedPermitId);
            statement.setString(2, permitYear);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getInt(1) > 0) {
                    throw new SQLException("ATFM permit appeared concurrently: " + normalizedPermitId);
                }
            }
        }
    }

    private void bindDetail(PreparedStatement statement,
                            long permId,
                            ScheduleFlight flight) throws SQLException {
        int index = 1;
        statement.setLong(index++, permId);
        statement.setString(index++, flight.purposeId());
        setNullableAircraftId(statement, index++, flight.craftId());
        statement.setBigDecimal(index++, flight.mtow());
        statement.setString(index++, flight.flightNumber());
        setNullableString(statement, index++, registrationForStorage(flight.registration()));
        for (int day = 0; day < 7; day++) {
            statement.setString(index++, String.valueOf(flight.serviceDays().charAt(day)));
        }
        statement.setString(index++, flight.fromAirport());
        statement.setString(index++, flight.toAirport());
        statement.setString(index++, flight.etd());
        setNullableString(statement, index++, flight.eta());
        statement.setString(index++, viaForStorage(flight.via()));
        statement.setString(index++, "0");
        statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
        statement.setString(index++, "AEROSYNC");
        statement.setDate(index++, Date.valueOf(flight.beginDate()));
        statement.setDate(index++, Date.valueOf(flight.endDate()));
        statement.setString(index, truncateUtf8(flight.remark(), 4000));
    }

    private boolean masterMatches(ResultSet row, SchedulePermit permit) throws SQLException {
        Date permitDate = row.getDate("PERMDATE");
        return Objects.equals(normalize(row.getString("AUTHOR_ID")), normalize(permit.authorId()))
                && Objects.equals(normalize(row.getString("PERMTYPE")), normalize(permit.permitType()))
                && Objects.equals(normalize(row.getString("PERMNBR")), normalize(permit.permitNumber()))
                && Objects.equals(normalize(row.getString("VERSION")), normalize(permit.version()))
                && Objects.equals(normalize(row.getString("SEASON")), normalize(permit.season()))
                && permitDate != null && Objects.equals(permitDate.toLocalDate(), permit.permitDate())
                && Objects.equals(normalize(row.getString("OPER_ID")), normalize(permit.operatorId()))
                && row.getInt("VALIDHOURS") == permit.validHours()
                && Objects.equals(normalize(row.getString("FLIGHTTYPE")), normalize(permit.flightType()));
    }

    private boolean flightsMatch(List<ExistingFlight> existing, List<ScheduleFlight> expected) {
        if (existing.size() != expected.size()) {
            return false;
        }
        Comparator<ExistingFlight> comparator = Comparator
                .comparing(ExistingFlight::flightNumber)
                .thenComparing(ExistingFlight::beginDate)
                .thenComparing(ExistingFlight::etd);
        List<ExistingFlight> actualSorted = existing.stream().sorted(comparator).toList();
        List<ExistingFlight> expectedSorted = expected.stream()
                .map(ExistingFlight::fromExpected)
                .sorted(comparator)
                .toList();
        return actualSorted.equals(expectedSorted);
    }

    private boolean flightsContain(List<ExistingFlight> existing, List<ScheduleFlight> expected) {
        return missingFlights(existing, expected).isEmpty();
    }

    private boolean flightsContainComplete(List<ExistingFlight> existing, List<ScheduleFlight> expected) {
        return missingCompleteFlights(existing, expected).isEmpty();
    }

    private List<ScheduleFlight> missingCompleteFlights(List<ExistingFlight> existing,
                                                        List<ScheduleFlight> expected) {
        List<ExistingFlight> remaining = new ArrayList<>(existing);
        List<ScheduleFlight> missing = new ArrayList<>();
        for (ScheduleFlight flight : expected) {
            ExistingFlight expectedFlight = ExistingFlight.fromExpected(flight);
            int match = remaining.indexOf(expectedFlight);
            if (match < 0) {
                missing.add(flight);
            } else {
                remaining.remove(match);
            }
        }
        return missing;
    }

    private List<ScheduleFlight> missingFlights(List<ExistingFlight> existing,
                                                List<ScheduleFlight> expected) {
        List<ExistingFlight> remaining = new ArrayList<>(existing);
        List<ScheduleFlight> missing = new ArrayList<>();
        for (ScheduleFlight flight : expected) {
            ExistingFlight expectedFlight = ExistingFlight.fromExpected(flight);
            int match = -1;
            for (int index = 0; index < remaining.size(); index++) {
                if (remaining.get(index).sameBusinessRow(expectedFlight)) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                missing.add(flight);
            } else {
                remaining.remove(match);
            }
        }
        return missing;
    }

    private ExistingFlight toExistingFlight(ResultSet row) throws SQLException {
        StringBuilder days = new StringBuilder(7);
        for (int day = 1; day <= 7; day++) {
            days.append(nullToZero(row.getString("DAY" + day)));
        }
        Date begin = row.getDate("BEGINDATE");
        Date end = row.getDate("ENDDATE");
        return new ExistingFlight(
                normalize(row.getString("PURPOSE_ID")),
                row.getLong("CRAFT_ID"),
                defaultZero(row.getBigDecimal("MTOW")),
                normalize(row.getString("FLIGHTNBR")),
                normalize(row.getString("REGISTRATION")),
                days.toString(),
                normalize(row.getString("FROM_AIRP")),
                normalize(row.getString("TO_AIRP")),
                normalize(row.getString("ETD")),
                normalize(row.getString("ETA")),
                viaForComparison(row.getString("VIA")),
                begin == null ? null : begin.toLocalDate(),
                end == null ? null : end.toLocalDate(),
                normalize(row.getString("REMARK")));
    }

    private ScheduleFlight toBaselineFlight(ResultSet row) throws SQLException {
        ExistingFlight flight = toExistingFlight(row);
        return new ScheduleFlight(
                flight.purposeId(), flight.craftId(), flight.mtow(), flight.flightNumber(),
                flight.registration(), flight.serviceDays(), flight.fromAirport(), flight.toAirport(),
                flight.etd(), flight.eta(), flight.via(), flight.beginDate(), flight.endDate(),
                flight.remark(), null);
    }

    private Connection openConnection() throws SQLException {
        if (properties.getUrl().isBlank() || properties.getUsername().isBlank()) {
            throw new IllegalStateException("ATFM database URL and username must be configured");
        }
        return DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableAircraftId(PreparedStatement statement,
                                       int index,
                                       long craftId) throws SQLException {
        if (craftId > 0) {
            statement.setLong(index, craftId);
        } else {
            statement.setNull(index, Types.NUMERIC);
        }
    }

    private void setFullContent(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.CLOB);
        } else {
            statement.setCharacterStream(index, new StringReader(value), value.length());
        }
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    static String viaForStorage(String value) {
        String route = viaForComparison(value);
        return route == null ? null : VIA_PREFIX + route.replaceAll(
                "(?<=\\b[A-Z0-9]{2,15})\\s+(?=[A-Z0-9]{2,15}\\b)", "/");
    }

    static String viaForComparison(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (String prefix : List.of(VIA_PREFIX,
                "Other permitted airways published in Viet Nam's AIP/",
                "Other permitted airways published in Viet Nam" + (char) 0x2019 + "s AIP/")) {
            if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
                normalized = normalized.substring(prefix.length()).trim();
                break;
            }
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requiredPermitYear(SchedulePermit permit) {
        Integer year = permit.atfmTargetPermitYear();
        if (year == null) {
            throw new IllegalArgumentException(
                    "Permit number does not contain a four-digit year: "
                            + permit.atfmTargetPermitId());
        }
        return Integer.toString(year);
    }

    private char nullToZero(String value) {
        return value == null || value.isBlank() ? '0' : value.trim().charAt(0);
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private IllegalStateException databaseFailure(String operation, SQLException exception) {
        return new IllegalStateException("Failed to " + operation + " in ATFM: " + exception.getMessage(), exception);
    }

    private record ExistingFlight(
            String purposeId,
            long craftId,
            BigDecimal mtow,
            String flightNumber,
            String registration,
            String serviceDays,
            String fromAirport,
            String toAirport,
            String etd,
            String eta,
            String via,
            java.time.LocalDate beginDate,
            java.time.LocalDate endDate,
            String remark
    ) {
        ExistingFlight {
            // Registration is no longer persisted or compared, including revisions.
            registration = null;
        }
        /** Optional enrichment must not create a second copy of one schedule row. */
        boolean sameBusinessRow(ExistingFlight other) {
            return Objects.equals(flightNumber, other.flightNumber)
                    && Objects.equals(serviceDays, other.serviceDays)
                    && Objects.equals(fromAirport, other.fromAirport)
                    && Objects.equals(toAirport, other.toAirport)
                    && Objects.equals(etd, other.etd)
                    && Objects.equals(beginDate, other.beginDate)
                    && Objects.equals(endDate, other.endDate);
        }

        ScheduleFlight toScheduleFlight() {
            return new ScheduleFlight(
                    purposeId, craftId, mtow, flightNumber, registration, serviceDays,
                    fromAirport, toAirport, etd, eta, via, beginDate, endDate, remark, null);
        }

        static ExistingFlight fromExpected(ScheduleFlight flight) {
            return new ExistingFlight(
                    normalize(flight.purposeId()), flight.craftId(),
                    flight.mtow() == null ? BigDecimal.ZERO : flight.mtow().stripTrailingZeros(),
                    normalize(flight.flightNumber()), normalize(flight.registration()), flight.serviceDays(),
                    normalize(flight.fromAirport()), normalize(flight.toAirport()), normalize(flight.etd()),
                    normalize(flight.eta()), viaForComparison(flight.via()), flight.beginDate(), flight.endDate(),
                    normalize(flight.remark()));
        }
    }
}
