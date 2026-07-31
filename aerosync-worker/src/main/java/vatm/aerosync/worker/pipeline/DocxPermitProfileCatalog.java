package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
class DocxPermitProfileCatalog {

    private static final String PROFILE_PATTERN = "classpath*:permit-formats/*.yaml";

    private final List<DocxPermitFormatProfile> profiles;
    private final Map<String, DocxPermitFormatProfile> declaredProfiles;
    private final PermitTrainingCandidateRepository trainingCandidateRepository;

    DocxPermitProfileCatalog() {
        this(new PermitSemanticAliasCatalog(), null);
    }

    @Autowired
    DocxPermitProfileCatalog(PermitSemanticAliasCatalog semanticAliasCatalog,
                             PermitTrainingCandidateRepository trainingCandidateRepository) {
        List<DocxPermitFormatProfile> declared = loadProfiles();
        this.declaredProfiles = declared.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DocxPermitFormatProfile::id, profile -> profile));
        this.profiles = declared.stream()
                .map(profile -> resolve(profile, semanticAliasCatalog))
                .peek(this::validateResolvedAliases)
                .toList();
        this.trainingCandidateRepository = trainingCandidateRepository;
    }

    List<DocxPermitFormatProfile> profiles() {
        return activeProfiles().profiles();
    }

    DocxPermitFormatProfile declaredProfile(String id) {
        DocxPermitFormatProfile profile = activeProfiles().declaredProfiles().get(id);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown Word permit profile: " + id);
        }
        return profile;
    }

    ActiveProfiles activeProfiles() {
        if (trainingCandidateRepository == null) {
            return new ActiveProfiles(profiles, declaredProfiles);
        }
        List<PermitTrainingCandidate> candidates =
                trainingCandidateRepository.findAllByStatus(PermitTrainingStatus.APPROVED);
        if (candidates.isEmpty()) {
            return new ActiveProfiles(profiles, declaredProfiles);
        }
        Map<String, List<PermitTrainingCandidate>> byProfile = candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PermitTrainingCandidate::getProfileId));
        List<DocxPermitFormatProfile> activeResolved = profiles.stream()
                .map(profile -> applyApprovedAliases(
                        profile, byProfile.getOrDefault(profile.id(), List.of())))
                .peek(this::validateResolvedAliases)
                .toList();
        Map<String, DocxPermitFormatProfile> activeDeclared = declaredProfiles.values().stream()
                .map(profile -> applyApprovedAliases(
                        profile, byProfile.getOrDefault(profile.id(), List.of())))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DocxPermitFormatProfile::id, profile -> profile));
        return new ActiveProfiles(activeResolved, activeDeclared);
    }

    private List<DocxPermitFormatProfile> loadProfiles() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PROFILE_PATTERN);
            if (resources.length == 0) {
                throw new IllegalStateException("No DOCX permit format profiles found at " + PROFILE_PATTERN);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
            List<DocxPermitFormatProfile> loaded = Arrays.stream(resources)
                    .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                    .map(resource -> readProfile(mapper, resource))
                    .toList();
            Set<String> ids = new HashSet<>();
            for (DocxPermitFormatProfile profile : loaded) {
                validate(profile);
                if (!ids.add(profile.id())) {
                    throw new IllegalStateException("Duplicate Word permit profile id: " + profile.id());
                }
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Word permit profiles", exception);
        }
    }

    private DocxPermitFormatProfile readProfile(ObjectMapper mapper, Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return mapper.readValue(input, DocxPermitFormatProfile.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Word permit profile " + resource.getFilename(), exception);
        }
    }

    private void validate(DocxPermitFormatProfile profile) {
        if (profile.id() == null || profile.id().isBlank()) {
            throw new IllegalStateException("Word permit profile id is required");
        }
        if (profile.family() == null || profile.family().isBlank()) {
            throw invalid(profile, "family is required");
        }
        if (profile.profileVersion() < 1) {
            throw invalid(profile, "profileVersion must be at least 1");
        }
        if (profile.detectionPatterns() == null || profile.detectionPatterns().isEmpty()) {
            throw invalid(profile, "at least one detection pattern is required");
        }
        profile.detectionPatterns().forEach(pattern -> validatePattern(profile, pattern, "detection"));
        if (profile.permit() == null) {
            throw invalid(profile, "permit identity is required");
        }
        validatePattern(profile, profile.permit().pattern(), "permit identity");
        if (blank(profile.permit().numberGroup()) || blank(profile.permit().normalizedTemplate())) {
            throw invalid(profile, "permit number group and normalized template are required");
        }
        validateDateField(profile, profile.permitDate(), "permit date");
        validateTextField(profile, profile.operator(), "operator");
        validateTextField(profile, profile.billingAddress(), "billing address");
        validateTextField(profile, profile.reference(), "reference");
        if (profile.purpose() != null) {
            if (blank(profile.purpose().defaultId())) {
                throw invalid(profile, "purpose default is required");
            }
            if (profile.purpose().mappings() != null) {
                profile.purpose().mappings().forEach(mapping -> {
                    if (blank(mapping.value())) {
                        throw invalid(profile, "purpose mapping value is required");
                    }
                    validatePattern(profile, mapping.pattern(), "purpose mapping");
                });
            }
        }
        if (profile.master() == null) {
            throw invalid(profile, "master defaults are required");
        }
        if (profile.schedule() == null
                || profile.schedule().columns() == null
                || profile.schedule().requiredColumns() == null
                || profile.schedule().requiredColumns().isEmpty()
                || profile.schedule().dateFormats() == null
                || profile.schedule().dateFormats().isEmpty()
                || profile.schedule().timeFormats() == null
                || profile.schedule().timeFormats().isEmpty()
                || blank(profile.schedule().purposeId())) {
            throw invalid(profile, "schedule aliases, selection, date/time formats and purpose are required");
        }
        if (!profile.schedule().columns().keySet().containsAll(profile.schedule().requiredColumns())) {
            throw invalid(profile, "every required schedule column must define aliases");
        }
        if (profile.schedule().tableContextPatterns() != null) {
            profile.schedule().tableContextPatterns().forEach(
                    pattern -> validatePattern(profile, pattern, "schedule table context"));
        }
        if (profile.schedule().preferredTableContextPatterns() != null) {
            profile.schedule().preferredTableContextPatterns().forEach(
                    pattern -> validatePattern(profile, pattern, "preferred schedule table context"));
        }
        if (profile.schedule().supplementalTableContextPatterns() != null) {
            profile.schedule().supplementalTableContextPatterns().forEach(
                    pattern -> validatePattern(profile, pattern, "supplemental schedule table context"));
        }
        if (profile.route() != null && profile.route().staticAirways() != null) {
            profile.route().staticAirways().forEach((sector, airways) -> {
                if (blank(sector) || blank(airways)
                        || !sector.matches("(?i)^[A-Z]{3,4}\\s*[-–—]\\s*[A-Z]{3,4}$")) {
                    throw invalid(profile, "static airway mappings require ROUTE: AIRWAYS values");
                }
            });
        }
        if (profile.aircraft() == null) {
            throw invalid(profile, "aircraft mapping is required");
        }
        boolean hasDefaultType = !blank(profile.aircraft().defaultType());
        boolean hasScheduleType = !blank(profile.aircraft().scheduleColumn());
        boolean hasAuxiliaryType = !blank(profile.aircraft().auxiliaryTypeColumn())
                && profile.aircraft().auxiliaryColumns() != null
                && !profile.aircraft().auxiliaryColumns().isEmpty();
        if (!hasDefaultType && !hasScheduleType && !hasAuxiliaryType) {
            throw invalid(profile, "aircraft must define a source column or default type");
        }
    }

    private DocxPermitFormatProfile resolve(DocxPermitFormatProfile profile,
                                            PermitSemanticAliasCatalog semanticAliasCatalog) {
        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        DocxPermitFormatProfile.ScheduleDefinition resolvedSchedule =
                new DocxPermitFormatProfile.ScheduleDefinition(
                        semanticAliasCatalog.resolve(profile.family(), schedule.columns()),
                        schedule.requiredColumns(),
                        schedule.excludeColumns(),
                        schedule.tableContextPatterns(),
                        schedule.preferredTableContextPatterns(),
                        schedule.supplementalTableContextPatterns(),
                        schedule.dateFormats(),
                        schedule.timeFormats(),
                        schedule.locale(),
                        schedule.purposeId(),
                        schedule.includeEta(),
                        schedule.lastMatchingTable(),
                        schedule.inferIataPrefix());

        DocxPermitFormatProfile.RouteDefinition route = profile.route();
        DocxPermitFormatProfile.RouteDefinition resolvedRoute = route == null
                ? null
                : new DocxPermitFormatProfile.RouteDefinition(
                        semanticAliasCatalog.resolve(
                                profile.family(), safeMap(route.columns())),
                        route.requiredColumns(),
                        route.staticAirways(),
                        route.tableRequired(),
                        route.allowEmpty(),
                        route.fallbackToFirst(),
                        route.lastMatchingTable(),
                        route.filterSchedule());

        DocxPermitFormatProfile.AircraftDefinition aircraft = profile.aircraft();
        DocxPermitFormatProfile.AircraftDefinition resolvedAircraft =
                new DocxPermitFormatProfile.AircraftDefinition(
                        aircraft.scheduleColumn(),
                        semanticAliasCatalog.resolve(
                                profile.family(), safeMap(aircraft.auxiliaryColumns())),
                        aircraft.auxiliaryRequiredColumns(),
                        aircraft.auxiliaryTypeColumn(),
                        aircraft.remarkPrefix(),
                        aircraft.defaultType(),
                        aircraft.lastMatchingTable());

        return new DocxPermitFormatProfile(
                profile.id(),
                profile.family(),
                profile.profileVersion(),
                profile.priority(),
                profile.detectionPatterns(),
                profile.permit(),
                profile.permitDate(),
                profile.operator(),
                profile.billingAddress(),
                profile.reference(),
                profile.referenceColumn(),
                profile.purpose(),
                profile.master(),
                resolvedSchedule,
                resolvedRoute,
                resolvedAircraft,
                profile.validation());
    }

    private DocxPermitFormatProfile applyApprovedAliases(
            DocxPermitFormatProfile profile,
            List<PermitTrainingCandidate> candidates) {
        List<PermitTrainingCandidate> compatible = candidates.stream()
                .filter(candidate -> candidate.getProfileVersion() == profile.profileVersion())
                .toList();
        if (compatible.isEmpty()) {
            return profile;
        }

        DocxPermitFormatProfile.ScheduleDefinition schedule = profile.schedule();
        DocxPermitFormatProfile.ScheduleDefinition promotedSchedule =
                new DocxPermitFormatProfile.ScheduleDefinition(
                        addAliases(schedule.columns(), compatible, "schedule."),
                        schedule.requiredColumns(),
                        schedule.excludeColumns(),
                        schedule.tableContextPatterns(),
                        schedule.preferredTableContextPatterns(),
                        schedule.supplementalTableContextPatterns(),
                        schedule.dateFormats(),
                        schedule.timeFormats(),
                        schedule.locale(),
                        schedule.purposeId(),
                        schedule.includeEta(),
                        schedule.lastMatchingTable(),
                        schedule.inferIataPrefix());

        DocxPermitFormatProfile.RouteDefinition route = profile.route();
        DocxPermitFormatProfile.RouteDefinition promotedRoute = route == null
                ? null
                : new DocxPermitFormatProfile.RouteDefinition(
                        addAliases(route.columns(), compatible, "route."),
                        route.requiredColumns(),
                        route.staticAirways(),
                        route.tableRequired(),
                        route.allowEmpty(),
                        route.fallbackToFirst(),
                        route.lastMatchingTable(),
                        route.filterSchedule());

        DocxPermitFormatProfile.AircraftDefinition aircraft = profile.aircraft();
        DocxPermitFormatProfile.AircraftDefinition promotedAircraft =
                new DocxPermitFormatProfile.AircraftDefinition(
                        aircraft.scheduleColumn(),
                        addAliases(
                                aircraft.auxiliaryColumns(), compatible, "aircraft."),
                        aircraft.auxiliaryRequiredColumns(),
                        aircraft.auxiliaryTypeColumn(),
                        aircraft.remarkPrefix(),
                        aircraft.defaultType(),
                        aircraft.lastMatchingTable());

        return new DocxPermitFormatProfile(
                profile.id(),
                profile.family(),
                profile.profileVersion(),
                profile.priority(),
                profile.detectionPatterns(),
                profile.permit(),
                profile.permitDate(),
                profile.operator(),
                profile.billingAddress(),
                profile.reference(),
                profile.referenceColumn(),
                profile.purpose(),
                profile.master(),
                promotedSchedule,
                promotedRoute,
                promotedAircraft,
                profile.validation());
    }

    private Map<String, List<String>> addAliases(
            Map<String, List<String>> configured,
            List<PermitTrainingCandidate> candidates,
            String prefix) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        safeMap(configured).forEach((semantic, aliases) ->
                result.put(semantic, List.copyOf(safeList(aliases))));
        candidates.stream()
                .filter(candidate -> candidate.getSemanticField().startsWith(prefix))
                .forEach(candidate -> {
                    String semantic = candidate.getSemanticField().substring(prefix.length());
                    if (!result.containsKey(semantic)) {
                        return;
                    }
                    LinkedHashSet<String> aliases =
                            new LinkedHashSet<>(result.get(semantic));
                    aliases.add(candidate.getAliasValue());
                    result.put(semantic, List.copyOf(aliases));
                });
        return Map.copyOf(result);
    }

    private void validateResolvedAliases(DocxPermitFormatProfile profile) {
        validateAliasConflicts(profile, "schedule", profile.schedule().columns());
        if (profile.route() != null) {
            validateAliasConflicts(profile, "route", profile.route().columns());
        }
        validateAliasConflicts(profile, "aircraft", profile.aircraft().auxiliaryColumns());
    }

    private void validateAliasConflicts(DocxPermitFormatProfile profile,
                                        String section,
                                        Map<String, List<String>> aliases) {
        Map<String, String> owners = new LinkedHashMap<>();
        safeMap(aliases).forEach((semantic, values) -> safeList(values).forEach(alias -> {
            String canonical = PermitTextNormalizer.canonicalHeader(alias);
            if (canonical.isBlank()) {
                throw invalid(profile, section + " alias cannot be blank");
            }
            String previous = owners.putIfAbsent(canonical, semantic);
            if (previous != null && !previous.equals(semantic)) {
                throw invalid(profile,
                        section + " alias '" + alias + "' conflicts between "
                                + previous + " and " + semantic);
            }
        }));
    }

    private void validateDateField(DocxPermitFormatProfile profile,
                                   DocxPermitFormatProfile.DateField field,
                                   String description) {
        if (field == null || blank(field.pattern()) || blank(field.group())
                || field.formats() == null || field.formats().isEmpty()) {
            throw invalid(profile, description + " extraction and formats are required");
        }
        validatePattern(profile, field.pattern(), description);
    }

    private void validateTextField(DocxPermitFormatProfile profile,
                                   DocxPermitFormatProfile.TextField field,
                                   String description) {
        if (field != null && !blank(field.pattern())) {
            validatePattern(profile, field.pattern(), description);
        }
        if (field != null && field.valueMappings() != null
                && field.valueMappings().entrySet().stream()
                .anyMatch(entry -> blank(entry.getKey()) || blank(entry.getValue()))) {
            throw invalid(profile, description + " value mappings cannot contain blank keys or values");
        }
    }

    private void validatePattern(DocxPermitFormatProfile profile,
                                 String pattern,
                                 String description) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException | NullPointerException exception) {
            throw invalid(profile, "invalid " + description + " regex: " + exception.getMessage());
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private IllegalStateException invalid(DocxPermitFormatProfile profile, String detail) {
        String id = profile.id() == null ? "<unknown>" : profile.id();
        return new IllegalStateException("Invalid Word permit profile " + id + ": " + detail);
    }

    record ActiveProfiles(
            List<DocxPermitFormatProfile> profiles,
            Map<String, DocxPermitFormatProfile> declaredProfiles
    ) {
        ActiveProfiles {
            profiles = List.copyOf(profiles);
            declaredProfiles = Map.copyOf(declaredProfiles);
        }

        DocxPermitFormatProfile declaredProfile(String id) {
            DocxPermitFormatProfile profile = declaredProfiles.get(id);
            if (profile == null) {
                throw new IllegalArgumentException("Unknown Word permit profile: " + id);
            }
            return profile;
        }
    }
}
