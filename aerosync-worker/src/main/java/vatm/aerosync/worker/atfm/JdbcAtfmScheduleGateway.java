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
import java.time.LocalDateTime;
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
                    WHERE candidate.PERMNBR_ID = ?)
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
            try (PreparedStatement statement = connection.prepareStatement(FIND_EXISTING_SQL)) {
                statement.setString(1, permit.normalizedPermitId());
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
                            masterMatches && flightsMatch(existingFlights, resolvedFlights)));
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure("read existing permit", exception);
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

    private AtfmWriteResult insertWithinTransaction(Connection connection,
                                                    SchedulePermit permit) throws SQLException {
        List<ScheduleFlight> resolvedFlights = resolveAirportCodes(connection, permit.flights());
        validateReferenceData(connection, permit);
        ensurePermitNumberIsAvailable(connection, permit.normalizedPermitId());
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
            statement.setString(index++, truncate(permit.reference(), 4000));
            statement.setInt(index++, permit.validHours());
            statement.setTimestamp(index++, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(index++, "AEROSYNC");
            statement.setString(index++, "0");
            statement.setString(index++, truncate(permit.rawContent(), 4000));
            statement.setString(index++, truncate(permit.billingAddress(), 4000));
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
                    throw new SQLException("ATFM lookup not found: " + table + "." + column + "=" + value);
                }
            }
        }
    }

    private void ensurePermitNumberIsAvailable(Connection connection,
                                               String normalizedPermitId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM T_PERMMASTER_SC WHERE PERMNBR_ID = ?")) {
            statement.setString(1, normalizedPermitId);
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
        statement.setString(index, truncate(flight.remark(), 4000));
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

    private String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
