package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
class PermitSemanticAliasCatalog {

    private static final String RESOURCE = "permit-reference/permit-semantic-aliases.yaml";

    private final AliasConfiguration configuration;

    PermitSemanticAliasCatalog() {
        this.configuration = load();
    }

    Map<String, List<String>> resolve(String family,
                                      Map<String, List<String>> declaredAliases) {
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        Map<String, List<String>> familyAliases = configuration.families()
                .getOrDefault(family == null ? "" : family, Map.of());
        declaredAliases.forEach((semantic, aliases) -> {
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            addAliases(merged, aliases);
            addAliases(merged, familyAliases.get(semantic));
            addAliases(merged, configuration.global().get(semantic));
            resolved.put(semantic, List.copyOf(merged));
        });
        return Map.copyOf(resolved);
    }

    private void addAliases(LinkedHashSet<String> destination, List<String> aliases) {
        if (aliases == null) {
            return;
        }
        aliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .forEach(destination::add);
    }

    private AliasConfiguration load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        try (InputStream input = resource.getInputStream()) {
            AliasConfiguration loaded = mapper.readValue(input, AliasConfiguration.class);
            return new AliasConfiguration(
                    immutableAliases(loaded.global()),
                    immutableFamilies(loaded.families()));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load permit semantic aliases from " + RESOURCE,
                    exception);
        }
    }

    private Map<String, List<String>> immutableAliases(Map<String, List<String>> aliases) {
        if (aliases == null) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        aliases.forEach((key, value) ->
                result.put(key, List.copyOf(value == null ? List.of() : value)));
        return Map.copyOf(result);
    }

    private Map<String, Map<String, List<String>>> immutableFamilies(
            Map<String, Map<String, List<String>>> families) {
        if (families == null) {
            return Map.of();
        }
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        families.forEach((key, value) -> result.put(key, immutableAliases(value)));
        return Map.copyOf(result);
    }

    private record AliasConfiguration(
            Map<String, List<String>> global,
            Map<String, Map<String, List<String>>> families
    ) {
        AliasConfiguration {
            global = global == null ? Map.of() : global;
            families = families == null ? Map.of() : families;
        }
    }
}
