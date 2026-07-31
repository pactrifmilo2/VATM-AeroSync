package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
class DocxPermitProfileCatalog {

    private static final String PROFILE_PATTERN = "classpath*:permit-formats/*.yaml";

    private final List<DocxPermitFormatProfile> profiles;

    DocxPermitProfileCatalog() {
        this.profiles = List.copyOf(loadProfiles());
    }

    List<DocxPermitFormatProfile> profiles() {
        return profiles;
    }

    private List<DocxPermitFormatProfile> loadProfiles() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PROFILE_PATTERN);
            if (resources.length == 0) {
                throw new IllegalStateException("No DOCX permit format profiles found at " + PROFILE_PATTERN);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
            List<ProfileSource> sources = Arrays.stream(resources)
                    .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                    .map(resource -> readSource(mapper, resource))
                    .toList();
            Map<String, ProfileSource> sourcesById = new LinkedHashMap<>();
            for (ProfileSource source : sources) {
                ProfileSource previous = sourcesById.putIfAbsent(source.id(), source);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate Word permit profile id: " + source.id());
                }
            }
            Map<String, JsonNode> resolved = new HashMap<>();
            List<DocxPermitFormatProfile> loaded = sources.stream()
                    .map(source -> mapper.convertValue(
                            resolveProfile(source.id(), sourcesById, resolved, new HashSet<>()),
                            DocxPermitFormatProfile.class))
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

    private ProfileSource readSource(ObjectMapper mapper, Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            JsonNode node = mapper.readTree(input);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException(
                        "Word permit profile must be a YAML object: " + resource.getFilename());
            }
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                throw new IllegalStateException(
                        "Word permit profile id is required in " + resource.getFilename());
            }
            return new ProfileSource(id, node);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Word permit profile " + resource.getFilename(), exception);
        }
    }

    private JsonNode resolveProfile(String id,
                                    Map<String, ProfileSource> sources,
                                    Map<String, JsonNode> resolved,
                                    Set<String> resolving) {
        JsonNode cached = resolved.get(id);
        if (cached != null) {
            return cached;
        }
        ProfileSource source = sources.get(id);
        if (source == null) {
            throw new IllegalStateException("Unknown parent Word permit profile: " + id);
        }
        if (!resolving.add(id)) {
            throw new IllegalStateException("Cyclic Word permit profile inheritance involving " + id);
        }

        String parentId = source.node().path("extends").asText("");
        ObjectNode merged;
        if (parentId.isBlank()) {
            merged = source.node().deepCopy();
        } else {
            merged = resolveProfile(parentId, sources, resolved, resolving).deepCopy();
            deepMerge(merged, source.node());
        }
        merged.remove("extends");
        resolving.remove(id);
        resolved.put(id, merged);
        return merged;
    }

    private void deepMerge(ObjectNode target, JsonNode override) {
        override.properties().forEach(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (existing != null && existing.isObject() && value.isObject()) {
                deepMerge((ObjectNode) existing, value);
            } else {
                target.set(entry.getKey(), value.deepCopy());
            }
        });
    }

    private void validate(DocxPermitFormatProfile profile) {
        if (profile.id() == null || profile.id().isBlank()) {
            throw new IllegalStateException("Word permit profile id is required");
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

    private IllegalStateException invalid(DocxPermitFormatProfile profile, String detail) {
        String id = profile.id() == null ? "<unknown>" : profile.id();
        return new IllegalStateException("Invalid Word permit profile " + id + ": " + detail);
    }

    private record ProfileSource(String id, JsonNode node) {
    }
}
