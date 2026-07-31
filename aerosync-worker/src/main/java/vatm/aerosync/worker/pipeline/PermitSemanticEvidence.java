package vatm.aerosync.worker.pipeline;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

record PermitSemanticEvidence(
        List<PermitIdentityCandidate> permitIdentities,
        SemanticValue<LocalDate> permitDate,
        SemanticValue<String> operatorIcao,
        SemanticValue<String> operatorIata,
        SemanticValue<String> billingAddress,
        Map<Integer, TableRoleEvidence> tableRoles
) {
    PermitSemanticEvidence {
        permitIdentities = List.copyOf(permitIdentities);
        tableRoles = Map.copyOf(tableRoles);
    }

    TableRoleEvidence tableRole(int tableIndex) {
        return tableRoles.get(tableIndex);
    }

    record PermitIdentityCandidate(
            String rawValue,
            String canonicalValue,
            String source,
            double confidence
    ) {
    }

    record SemanticValue<T>(
            T value,
            String source,
            double confidence,
            String method
    ) {
    }

    record TableRoleEvidence(
            TableRole role,
            String source,
            double confidence
    ) {
    }

    enum TableRole {
        ORIGINAL,
        REPLACEMENT,
        SUPPLEMENTAL
    }
}
