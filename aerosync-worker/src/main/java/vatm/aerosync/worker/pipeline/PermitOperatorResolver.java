package vatm.aerosync.worker.pipeline;

import java.util.Optional;

@FunctionalInterface
public interface PermitOperatorResolver {

    Optional<String> resolve(String iataCode, String carrierName);
}
