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
        SchedulePermit permit = context.getSchedulePermit();
        if (permit == null) {
            return;
        }

        List<ScheduleFlight> resolvedFlights = new ArrayList<>(permit.flights().size());
        List<RowValidationError> errors = new ArrayList<>();
        for (int index = 0; index < permit.flights().size(); index++) {
            ScheduleFlight flight = permit.flights().get(index);
            String sourceType = flight.sourceAircraftType();
            if ((sourceType == null || sourceType.isBlank()) && flight.craftId() > 0) {
                resolvedFlights.add(flight);
                continue;
            }
            List<String> candidates = aliases.candidates(sourceType);
            try {
                AtfmAircraftTypeResolver.ResolvedAircraft resolved = resolver.resolve(candidates);
                resolvedFlights.add(flight.withResolvedAircraft(
                        resolved.craftId(), resolved.mtow()));
                if (candidates.size() > 1) {
                    LOGGER.warn(
                            "Aircraft type '{}' offered candidates {}; selected '{}' (craftId={})",
                            sourceType, candidates, resolved.matchedCode(), resolved.craftId());
                }
            } catch (AtfmAircraftTypeResolver.AircraftTypeNotFoundException exception) {
                errors.add(error(
                        index + 1,
                        "BR-AIRCRAFT-NOT-FOUND",
                        "Unsupported aircraft type: %s (tried: %s)".formatted(
                                sourceType,
                                exception.getCandidates().isEmpty()
                                        ? "<empty>"
                                        : String.join(", ", exception.getCandidates())),
                        sourceType));
            } catch (AtfmAircraftTypeResolver.AmbiguousAircraftTypeException exception) {
                errors.add(error(
                        index + 1,
                        "BR-AIRCRAFT-AMBIGUOUS",
                        "Ambiguous aircraft type %s; matching craft IDs: %s".formatted(
                                sourceType, exception.getCraftIds()),
                        sourceType));
            }
        }

        if (!errors.isEmpty()) {
            context.getRowValidationErrors().addAll(errors);
            RowValidationError first = errors.getFirst();
            throw new BusinessRuleException(
                    first.code(),
                    "Schedule row %d: %s (%d aircraft resolution errors)".formatted(
                            first.rowNumber(), first.message(), errors.size()),
                    errors);
        }
        context.setSchedulePermit(permit.withFlights(resolvedFlights));
    }

    private RowValidationError error(int rowNumber, String code, String message, String value) {
        return new RowValidationError(rowNumber, "aircraftType", code, message, value);
    }
}
