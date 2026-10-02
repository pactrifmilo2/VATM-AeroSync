package vatm.aerosync.worker.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class AircraftTypeResolutionStep {

    private static final Logger LOGGER = LoggerFactory.getLogger(AircraftTypeResolutionStep.class);

    private final AircraftTypeCatalog aliases;
    private final AtfmAircraftTypeResolver resolver;

    public AircraftTypeResolutionStep(AircraftTypeCatalog aliases,
                                      AtfmAircraftTypeResolver resolver) {
        this.aliases = aliases;
        this.resolver = resolver;
    }

    public void resolve(ProcessingContext context) {
        resolve(context, false);
    }

    /**
     * Resolves aircraft types while allowing non-seasonal permits to be written
     * with a NULL CRAFT_ID. The warning is retained in the processing context;
     * seasonal permits continue to use the strict quarantine behavior.
     */
    public void resolveAllowingMissingForNonSeasonal(ProcessingContext context) {
        resolve(context, true);
    }

    private void resolve(ProcessingContext context, boolean allowMissingForNonSeasonal) {
        SchedulePermit permit = context.getSchedulePermit();
        if (permit == null) {
            return;
        }

        boolean allowMissing = true;

        List<ScheduleFlight> resolvedFlights = new ArrayList<>(permit.flights().size());
        List<RowValidationError> errors = new ArrayList<>();
        for (int index = 0; index < permit.flights().size(); index++) {
            ScheduleFlight flight = permit.flights().get(index);
            if (flight.craftId() > 0) {
                resolvedFlights.add(flight);
                continue;
            }
            String sourceType = AircraftTypeNotation.normalize(flight.sourceAircraftType());
            ScheduleFlight normalizedFlight = Objects.equals(sourceType, flight.sourceAircraftType())
                    ? flight
                    : flight.withSourceAircraftType(sourceType);
            if (sourceType == null || sourceType.isBlank()) {
                if (permit.revision() && !flight.aircraftTablePresent()) {
                    addOrThrow(allowMissing, errors,
                            error(index + 1, "BR-REVISION-AIRCRAFT-BASELINE-NOT-FOUND",
                                    "Original permit not found to obtain aircraft type", ""));
                    resolvedFlights.add(withFallbackAircraft(normalizedFlight, allowMissing));
                    continue;
                }
                addOrThrow(allowMissing, errors,
                        error(index + 1,
                                flight.aircraftTablePresent() ? "BR-AIRCRAFT-NOT-FOUND" : "BR-AIRCRAFT-MISSING",
                                flight.aircraftTablePresent()
                                        ? "Aircraft type not found in ATFM dictionary"
                                        : "Missing aircraft type in permit file",
                                ""));
                resolvedFlights.add(withFallbackAircraft(normalizedFlight, allowMissing));
                continue;
            }
            List<String> candidates = aliases.candidates(sourceType);
            try {
                AtfmAircraftTypeResolver.ResolvedAircraft resolved = resolver.resolve(candidates);
                resolvedFlights.add(normalizedFlight.withResolvedAircraft(
                        resolved.craftId(), resolved.mtow()));
                if (candidates.size() > 1) {
                    LOGGER.warn(
                            "Aircraft type '{}' offered candidates {}; selected '{}' (craftId={})",
                            sourceType, candidates, resolved.matchedCode(), resolved.craftId());
                }
            } catch (AtfmAircraftTypeResolver.AircraftTypeNotFoundException exception) {
                addOrThrow(allowMissing, errors,
                        error(index + 1, "BR-AIRCRAFT-NOT-FOUND",
                                "Aircraft type not found in ATFM dictionary: %s (tried: %s)".formatted(
                                        sourceType,
                                        exception.getCandidates().isEmpty()
                                                ? "<empty>"
                                                : String.join(", ", exception.getCandidates())),
                                sourceType));
                resolvedFlights.add(withFallbackAircraft(normalizedFlight, allowMissing));
            } catch (AtfmAircraftTypeResolver.AmbiguousAircraftTypeException exception) {
                addOrThrow(allowMissing, errors,
                        error(index + 1, "BR-AIRCRAFT-AMBIGUOUS",
                                "Ambiguous aircraft type %s; matching craft IDs: %s".formatted(
                                        sourceType, exception.getCraftIds()),
                                sourceType));
                resolvedFlights.add(withFallbackAircraft(normalizedFlight, allowMissing));
            }
        }

        if (!errors.isEmpty()) {
            context.getRowValidationErrors().addAll(errors);
        }
        context.setSchedulePermit(permit.withFlights(resolvedFlights));
    }

    private void addOrThrow(boolean allowMissing,
                            List<RowValidationError> errors,
                            RowValidationError error) {
        errors.add(error);
    }

    private ScheduleFlight withFallbackAircraft(ScheduleFlight flight, boolean allowMissing) {
        if (!allowMissing) {
            return flight;
        }
        try {
            AtfmAircraftTypeResolver.ResolvedAircraft fallback = resolver.resolve(List.of("A000"));
            return flight.withResolvedAircraft(fallback.craftId(), fallback.mtow());
        } catch (AtfmAircraftTypeResolver.AircraftTypeNotFoundException
                 | AtfmAircraftTypeResolver.AmbiguousAircraftTypeException exception) {
            throw new BusinessRuleException(
                    "BR-AIRCRAFT-FALLBACK-MISSING",
                    "Fallback aircraft type A000 is missing or ambiguous in M_CRAFT; "
                            + "cannot write an unresolved aircraft safely");
        }
    }

    private RowValidationError error(int rowNumber, String code, String message, String value) {
        return new RowValidationError(rowNumber, "aircraftType", code, message, value);
    }
}
