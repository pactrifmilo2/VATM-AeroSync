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
                String from = resolveAirport(connection, airports, flight.fromAirport());
                String to = resolveAirport(connection, airports, flight.toAirport());
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
                        via = null;
                        routeMissing = true;
                        context.getRowValidationErrors().add(new RowValidationError(
                                index + 1, "via", "BR-ROUTE-DOCUMENT-UNREADABLE",
                                "BR-ROUTE-DOCUMENT-UNREADABLE: Cannot read route from permit file",
                                flight.via()));
                    } catch (AtfmReferenceDataException exception) {
                        if (!allowMissing || permit.replacesNonScheduledRows()) {
                            throw exception;
                        }
                        String routeError = routeErrorMessage(from, to, exception.getMessage());
                        via = null;
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

    private String resolveVia(Connection connection,
                              SchedulePermit permit,
                              ScheduleFlight flight,
                              String from,
                              String to) throws SQLException {
        return viaResolver.resolve(
                connection, from, to, permit.operatorId(), flight.via());
    }

    private record RouteKey(String from, String to, String operator, String documentVia) {
    }

    private String routeErrorMessage(String from, String to, String detail) {
        String route = "ATFM route not found: " + from + " -> " + to;
        return detail == null || detail.isBlank() ? route : detail;
    }

}
