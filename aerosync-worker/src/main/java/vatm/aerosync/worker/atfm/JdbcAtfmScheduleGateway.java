package vatm.aerosync.worker.atfm;

import org.springframework.stereotype.Component;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class JdbcAtfmScheduleGateway implements AtfmScheduleGateway {

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
                  STATUS, PERMCONTENT, BILLINGADDRESS, FLIGHTTYPE)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                   BILLINGADDRESS = ?, FLIGHTTYPE = ?
             WHERE ID = ?
            """;

    private static final String FIND_NO_MASTER_SQL = """
            SELECT ID, PERM_ID
              FROM T_PERMMASTER_NO
             WHERE ID = (SELECT MAX(candidate.ID)
                           FROM T_PERMMASTER_NO candidate
                          WHERE candidate.PERMNBR_ID = ?
                            AND SUBSTR(TRIM(candidate.PERMNBR_ID), -4) = ?)
            """;

    private static final String LOCK_NO_MASTER_SQL = FIND_NO_MASTER_SQL + " FOR UPDATE";

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
            List<ScheduleFlight> noFlights = noFlights(resolvedFlights);
            List<ScheduleFlight> scheduledFlights = scheduledFlights(resolvedFlights);
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
                            permit.revision()
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
    public Optional<AtfmRevisionBaseline> findRevisionBaseline(SchedulePermit permit) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_EXISTING_SQL)) {
            statement.setString(1, permit.atfmTargetPermitId());
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                long masterId = resultSet.getLong("ID");
                long permId = resultSet.getLong("PERM_ID");
                List<ScheduleFlight> flights = new ArrayList<>();
                do {
                    if (resultSet.getString("FLIGHTNBR") != null) {
                        flights.add(toBaselineFlight(resultSet));
                    }
                } while (resultSet.next());
                return Optional.of(new AtfmRevisionBaseline(masterId, permId, flights));
            }
        } catch (SQLException exception) {
            throw databaseFailure("read revision baseline", exception);
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
        List<ScheduleFlight> noFlights = noFlights(resolvedFlights);
        List<ScheduleFlight> scheduledFlights = scheduledFlights(resolvedFlights);
        AtfmWriteResult scheduledResult = scheduledFlights.isEmpty()
                ? null
                : updateScWithinTransaction(connection, permit.withFlights(scheduledFlights));
        AtfmWriteResult noResult = noFlights.isEmpty()
                ? null
                : upsertNoWithinTransaction(connection, permit.withFlights(noFlights), noFlights);
        return combine(scheduledResult, noResult);
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

        if (!permit.revision()) {
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_MASTER_SQL)) {
                int index = 1;
                statement.setString(index++, permit.authorId());
                statement.setString(index++, permit.permitType());
                statement.setString(index++, permit.permitNumber());
                statement.setString(index++, permit.version());
                statement.setString(index++, permit.season());
                statement.setDate(index++, Date.valueOf(permit.permitDate()));
                statement.setString(index++, permit.operatorId());
                statement.setString(index++, truncateUtf8(permit.reference(), 4000));
                statement.setInt(index++, permit.validHours());
                statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
                statement.setString(index++, "AEROSYNC");
                statement.setString(index++, "0");
                statement.setString(index++, truncateUtf8(permit.rawContent(), 4000));
                statement.setString(index++, truncateUtf8(permit.billingAddress(), 4000));
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
        List<ScheduleFlight> noFlights = noFlights(resolvedFlights);
        List<ScheduleFlight> scheduledFlights = scheduledFlights(resolvedFlights);
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
            statement.setString(index++, truncateUtf8(permit.reference(), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, "AEROSYNC");
            statement.setString(index++, "0");
            statement.setString(index++, truncateUtf8(permit.rawContent(), 4000));
            statement.setString(index++, truncateUtf8(permit.billingAddress(), 4000));
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
                boolean matches = permit.revision()
                        ? flightsContain(actual, expected)
                        : flightsMatch(actual, expected);
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
        try (PreparedStatement statement = connection.prepareStatement(LOCK_NO_MASTER_SQL)) {
            statement.setString(1, noPermitId(permit));
            statement.setString(2, requiredPermitYear(permit));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    masterId = rows.getLong("ID");
                    permId = rows.getLong("PERM_ID");
                } else {
                    long[] ids = insertNoMaster(connection, permit);
                    masterId = ids[0];
                    permId = ids[1];
                }
            }
        }

        List<ExistingFlight> existing = readExistingNoFlights(connection, permId);
        List<ScheduleFlight> missing = missingFlights(existing, flights);
        if (missing.isEmpty()) {
            return new AtfmWriteResult(masterId, permId, 0);
        }
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

    private long[] insertNoMaster(Connection connection, SchedulePermit permit) throws SQLException {
        try (CallableStatement statement = connection.prepareCall(INSERT_NO_MASTER_SQL)) {
            int index = 1;
            statement.setString(index++, noPermitId(permit));
            statement.setString(index++, permit.authorId());
            statement.setString(index++, permit.permitType());
            statement.setString(index++, permit.permitNumber());
            statement.setDate(index++, Date.valueOf(permit.permitDate()));
            statement.setString(index++, permit.operatorId());
            statement.setString(index++, truncateUtf8(permit.reference(), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setString(index++, "0");
            statement.setString(index++, "AEROSYNC");
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, truncateUtf8(permit.billingAddress(), 4000));
            statement.setString(index++, truncateUtf8(permit.rawContent(), 4000));
            statement.setString(index++, permit.rawContent());
            statement.setString(index++, permit.billingAddress());
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
        statement.setLong(index++, flight.craftId());
        statement.setBigDecimal(index++, flight.mtow());
        statement.setString(index++, flight.beginDate().format(NO_FLIGHT_DATE).toUpperCase(java.util.Locale.ENGLISH));
        statement.setString(index++, flight.flightNumber());
        setNullableString(statement, index++, flight.registration());
        statement.setString(index++, flight.fromAirport());
        statement.setString(index++, flight.toAirport());
        statement.setString(index++, flight.etd());
        setNullableString(statement, index++, flight.eta());
        statement.setString(index++, flight.via());
        statement.setString(index++, "0");
        statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
        statement.setString(index++, "AEROSYNC");
        statement.setString(index++, flight.purposeId());
        statement.setDate(index++, Date.valueOf(flight.endDate()));
        statement.setString(index, truncateUtf8(flight.remark(), 4000));
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
                                normalize(rows.getString("VIA")),
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

    private List<ScheduleFlight> noFlights(List<ScheduleFlight> flights) {
        return flights.stream()
                .filter(flight -> flight.beginDate() != null
                        && flight.beginDate().equals(flight.endDate()))
                .toList();
    }

    private List<ScheduleFlight> scheduledFlights(List<ScheduleFlight> flights) {
        return flights.stream()
                .filter(flight -> flight.beginDate() == null
                        || !flight.beginDate().equals(flight.endDate()))
                .toList();
    }

    private String noPermitId(SchedulePermit permit) {
        String target = permit.atfmTargetPermitId();
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
            ensureLookupExists(connection, "M_CRAFT_TYPE", "CRAFT_ID", craftId);
        }
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
        statement.setLong(index++, flight.craftId());
        statement.setBigDecimal(index++, flight.mtow());
        statement.setString(index++, flight.flightNumber());
        setNullableString(statement, index++, flight.registration());
        for (int day = 0; day < 7; day++) {
            statement.setString(index++, String.valueOf(flight.serviceDays().charAt(day)));
        }
        statement.setString(index++, flight.fromAirport());
        statement.setString(index++, flight.toAirport());
        statement.setString(index++, flight.etd());
        setNullableString(statement, index++, flight.eta());
        statement.setString(index++, flight.via());
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

    private List<ScheduleFlight> missingFlights(List<ExistingFlight> existing,
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
                normalize(row.getString("VIA")),
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
        static ExistingFlight fromExpected(ScheduleFlight flight) {
            return new ExistingFlight(
                    normalize(flight.purposeId()), flight.craftId(),
                    flight.mtow() == null ? BigDecimal.ZERO : flight.mtow().stripTrailingZeros(),
                    normalize(flight.flightNumber()), normalize(flight.registration()), flight.serviceDays(),
                    normalize(flight.fromAirport()), normalize(flight.toAirport()), normalize(flight.etd()),
                    normalize(flight.eta()), normalize(flight.via()), flight.beginDate(), flight.endDate(),
                    normalize(flight.remark()));
        }
    }
}
