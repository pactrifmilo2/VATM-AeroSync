package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
}
