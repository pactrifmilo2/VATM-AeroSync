package vatm.aerosync.api.security;

public record LegacyTUserAccount(
        long id,
        String username,
        String passwordHash,
        boolean active,
        boolean canEditPermits,
        boolean canPublishPermits
) {
}
