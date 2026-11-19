package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.worker.atfm.AtfmAirportCodeResolver;
import vatm.aerosync.worker.atfm.AtfmDocumentRouteException;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ViaResolutionStep {

    static final String FALLBACK_AIRPORT = "VVVV";

    private final AtfmDatabaseProperties properties;
    private final AtfmAirportCodeResolver airportCodeResolver;
    private final AtfmViaResolver viaResolver;

    public ViaResolutionStep(AtfmDatabaseProperties properties,
                             AtfmAirportCodeResolver airportCodeResolver,
                             AtfmViaResolver viaResolver) {
        this.properties = properties;
        this.airportCodeResolver = airportCodeResolver;
        this.viaResolver = viaResolver;
    }

    public void resolve(ProcessingContext context) {
        resolve(context, false);
    }

    public void resolveAllowingMissingForNonSeasonal(ProcessingContext context) {
        resolve(context, true);
    }

    private void resolve(ProcessingContext context, boolean allowMissingForNonSeasonal) {
        SchedulePermit permit = context.getSchedulePermit();
        if (permit == null) {
            return;
        }
        boolean allowMissing = allowMissingForNonSeasonal && !permit.usesScheduledStorage();
        if (properties.getUrl().isBlank() || properties.getUsername().isBlank()) {
            throw new IllegalStateException("ATFM database URL and username must be configured");
        }

        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            Map<String, String> airports = new HashMap<>();
            Map<RouteKey, String> routes = new HashMap<>();
            List<ScheduleFlight> resolvedFlights = new ArrayList<>(permit.flights().size());
            for (int index = 0; index < permit.flights().size(); index++) {
                ScheduleFlight flight = permit.flights().get(index);
                String from = resolveAirportOrFallback(
                        connection, airports, flight.fromAirport(), index + 1, "fromAirport", context);
                String to = resolveAirportOrFallback(
                        connection, airports, flight.toAirport(), index + 1, "toAirport", context);
                RouteKey routeKey = new RouteKey(from, to, permit.operatorId(), flight.via());
                String via;
                boolean routeMissing = false;
                if (routes.containsKey(routeKey)) {
                    via = routes.get(routeKey);
                } else {
                    try {
                        via = resolveVia(connection, permit, flight, from, to);
                    } catch (AtfmDocumentRouteException exception) {
                        if (permit.replacesNonScheduledRows()) throw exception;
                        via = permit.seasonalPermit() ? null : fallbackVia(null);
                        routeMissing = true;
                        context.getRowValidationErrors().add(new RowValidationError(
                                index + 1, "via", "BR-ROUTE-DOCUMENT-UNREADABLE",
                                "BR-ROUTE-DOCUMENT-UNREADABLE: Cannot read route from permit file",
                                flight.via()));
                    } catch (AtfmReferenceDataException exception) {
                        // A missing ATFM route is a reference-data warning, not a
                        // reason to discard fields that were read successfully
                        // from the permit. Preserve the row and let the writer
                        // expose the warning to the user.
                        String routeError = routeErrorMessage(from, to, exception.getMessage());
                        via = permit.seasonalPermit() ? null : fallbackVia(flight.via());
                        routeMissing = true;
                        context.getRowValidationErrors().add(new RowValidationError(
                                index + 1, "via", "BR-ROUTE-NOT-FOUND",
                                routeError, from + " -> " + to));
                    }
                    // Cache only valid dictionary routes. An error value contains
                    // the source row number and must be rebuilt for every row.
                    if (!routeMissing && via != null && !via.isBlank()) {
                        routes.put(routeKey, via);
                    }
                }
                if (via == null && allowMissing && !routeMissing) {
                    String routeError = routeErrorMessage(from, to, null);
                    context.getRowValidationErrors().add(new RowValidationError(
                            index + 1, "via", "BR-ROUTE-NOT-FOUND",
                            routeError,
                            from + " -> " + to));
                }
                addLongTokenWarnings(context, index + 1, via);
                if (isSeasonalLandingTransit(permit, from, to)) {
                    context.getWarnings().add(new RowValidationError(
                            index + 1,
                            "route",
                            "BR-SEASONAL-LD-TRANSIT",
                            "Chuyến bay này là chuyến bay quá cảnh",
                            from + " -> " + to));
                }
                resolvedFlights.add(flight.withResolvedRoute(from, to, via));
            }
            context.setSchedulePermit(permit.withFlights(resolvedFlights));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to resolve ATFM routes from M_AIRPORT_ROUTE/M_VIA: " + exception.getMessage(),
                    exception);
        }
    }

    private String resolveAirport(Connection connection,
                                  Map<String, String> airports,
                                  String sourceCode) throws SQLException {
        String cached = airports.get(sourceCode);
        if (cached != null) {
            return cached;
        }
        String resolved = airportCodeResolver.resolve(connection, sourceCode);
        airports.put(sourceCode, resolved);
        return resolved;
    }

    private String resolveAirportOrFallback(Connection connection,
                                            Map<String, String> airports,
                                            String sourceCode,
                                            int rowNumber,
                                            String field,
                                            ProcessingContext context) throws SQLException {
        try {
            return resolveAirport(connection, airports, sourceCode);
        } catch (AtfmReferenceDataException exception) {
            context.getRowValidationErrors().add(new RowValidationError(
                    rowNumber,
                    field,
                    "BR-AIRPORT-FALLBACK",
                    "Airport code not found in ATFM dictionary M_AERO: " + sourceCode
                            + " (" + exception.getMessage() + "); fallback "
                            + FALLBACK_AIRPORT + " applied",
                    sourceCode));
            return FALLBACK_AIRPORT;
        }
    }

    private String resolveVia(Connection connection,
                              SchedulePermit permit,
                              ScheduleFlight flight,
                              String from,
                              String to) throws SQLException {
        return viaResolver.resolve(
                connection, from, to, permit.operatorId(), flight.via());
    }

    private boolean isSeasonalLandingTransit(SchedulePermit permit, String from, String to) {
        return permit.seasonalPermit()
                && "LD".equalsIgnoreCase(permit.permitType())
                && from != null && to != null
                && !from.startsWith("VV") && !to.startsWith("VV");
    }

    private record RouteKey(String from, String to, String operator, String documentVia) {
    }

    private String routeErrorMessage(String from, String to, String detail) {
        String route = "ATFM route not found: " + from + " -> " + to;
        return detail == null || detail.isBlank() ? route : detail;
    }

    private String fallbackVia(String documentVia) {
        String normalized = documentVia == null ? "" : documentVia.trim();
        return normalized.isBlank()
                ? AtfmViaResolver.DEFAULT_VIA_PREFIX
                : AtfmViaResolver.DEFAULT_VIA_PREFIX + normalized.replaceAll("\\s+", "/");
    }

    private void addLongTokenWarnings(ProcessingContext context, int rowNumber, String via) {
        if (via == null || via.startsWith(AtfmViaResolver.DEFAULT_VIA_PREFIX)) {
            return;
        }
        for (String token : via.split("/")) {
            if (token.length() >= 6 && token.matches("[A-Z0-9]+")) {
                context.getRowValidationErrors().add(new RowValidationError(
                        rowNumber, "via", "BR-ROUTE-LONG-TOKEN",
                        "Route token is unusually long but was saved: " + token, token));
            }
        }
    }

}
