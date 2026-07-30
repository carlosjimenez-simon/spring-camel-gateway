package com.simon.camel.gateway.composition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.camel.CamelContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompositionRegistry {

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final CompositionProperties properties;
    private final org.springframework.core.env.Environment env;

    @Getter
    @RequiredArgsConstructor
    public static class CompositionConfig {
        private final List<String> operations;
        private final String plantilla;
    }

    private Map<String, Map<String, CompositionConfig>> cache = new LinkedHashMap<>();

    @PostConstruct
    public void initialize() {
        Map<String, Map<String, CompositionProperties.ComposicionDef>> raw =
                properties.getComposicion();

        if (raw == null || raw.isEmpty()) {
            log.warn("[CompositionRegistry] No se encontraron composiciones declaradas bajo simon.composicion.*");
            return;
        }

        Map<String, Map<String, CompositionConfig>> built = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, CompositionProperties.ComposicionDef>> orgEntry : raw.entrySet()) {
            String org = orgEntry.getKey();
            validateIdentifier(org, "organizacion");

            Map<String, CompositionConfig> byComposicion = new LinkedHashMap<>();
            for (Map.Entry<String, CompositionProperties.ComposicionDef> compEntry : orgEntry.getValue().entrySet()) {
                String composicion = compEntry.getKey();
                validateIdentifier(composicion, "composicion");

                CompositionProperties.ComposicionDef def = compEntry.getValue();
                String rawOps = def == null ? null : def.getOperaciones();

                if (rawOps == null || rawOps.isBlank()) {
                    throw new IllegalStateException(
                            "Composición '" + composicion + "' de la organización '" + org
                                    + "' no tiene 'operaciones' declaradas.");
                }

                List<String> ops = new ArrayList<>();
                for (String op : Arrays.asList(rawOps.split(","))) {
                    String trimmed = op.trim();
                    if (trimmed.isEmpty()) {
                        throw new IllegalStateException(
                                "Composición '" + composicion + "' de '" + org
                                        + "' tiene un nombre de operación vacío en la lista.");
                    }
                    if (trimmed.contains(" ")) {
                        throw new IllegalStateException(
                                "Operación '" + trimmed + "' dentro de composición '" + composicion
                                        + "' de '" + org + "' contiene espacios. Use kebab-case (ej: mi-operacion).");
                    }
                    if (!KEBAB_CASE.matcher(trimmed).matches()) {
                        throw new IllegalStateException(
                                "Operación '" + trimmed + "' dentro de composición '" + composicion
                                        + "' de '" + org + "' no cumple el patrón kebab-case (a-z0-9 separados por '-').");
                    }
                    validateEndpointDeclared(org, trimmed);
                    ops.add(trimmed);
                }

                if (ops.isEmpty()) {
                    throw new IllegalStateException(
                            "Composición '" + composicion + "' de '" + org + "' quedó sin operaciones válidas.");
                }

                String plantilla = def != null ? def.getPlantilla() : null;
                byComposicion.put(composicion, new CompositionConfig(Collections.unmodifiableList(ops), plantilla));
                log.info("[CompositionRegistry] OK composición '{}/{}' → ops={}, plantilla={}", org, composicion, ops, plantilla);
            }
            built.put(org, Collections.unmodifiableMap(byComposicion));
        }

        this.cache = Collections.unmodifiableMap(built);
        log.info("[CompositionRegistry] {} organización(es) con {} composición(es) total.",
                built.size(),
                built.values().stream().mapToInt(Map::size).sum());
    }

    public boolean exists(String organizacion, String composicion) {
        Map<String, CompositionConfig> byOrg = cache.get(organizacion);
        return byOrg != null && byOrg.containsKey(composicion);
    }

    public List<String> getOperations(String organizacion, String composicion) {
        Map<String, CompositionConfig> byOrg = cache.get(organizacion);
        if (byOrg == null) {
            return List.of();
        }
        CompositionConfig config = byOrg.get(composicion);
        return config == null ? List.of() : config.getOperations();
    }

    public String getPlantilla(String organizacion, String composicion) {
        Map<String, CompositionConfig> byOrg = cache.get(organizacion);
        if (byOrg == null) {
            return null;
        }
        CompositionConfig config = byOrg.get(composicion);
        return config == null ? null : config.getPlantilla();
    }

    public Map<String, Map<String, CompositionConfig>> snapshot() {
        return cache;
    }

    private void validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("El identificador '" + label + "' es nulo o vacío.");
        }
        if (value.contains(" ")) {
            throw new IllegalStateException(
                    "El identificador '" + label + "' = '" + value + "' contiene espacios. Use kebab-case.");
        }
        if (!KEBAB_CASE.matcher(value).matches()) {
            throw new IllegalStateException(
                    "El identificador '" + label + "' = '" + value
                            + "' no cumple el patrón kebab-case (a-z0-9 separados por '-').");
        }
    }

    private void validateEndpointDeclared(String org, String operacion) {
        String key = "simon.endpoint." + org + "." + operacion;
        try {
            String endpoint = env.getProperty(key);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException(
                        "Composición referencia operación '" + operacion + "' de '" + org
                                + "' pero no existe la propiedad '" + key + "' en application*.yml.");
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException(
                    "No se pudo resolver '" + key + "' para validar la composición: " + e.getMessage(), e);
        }
    }

    }