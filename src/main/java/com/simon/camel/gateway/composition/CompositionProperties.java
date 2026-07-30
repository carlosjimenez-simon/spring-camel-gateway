package com.simon.camel.gateway.composition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "simon")
public class CompositionProperties {

    private Map<String, Map<String, ComposicionDef>> composicion = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class ComposicionDef {
        private String operaciones;
        private String plantilla;
    }

    public List<String> resolveOperations(String organizacion, String composicion) {
        Map<String, ComposicionDef> byOrg = this.composicion.get(organizacion);
        if (byOrg == null) {
            return List.of();
        }
        ComposicionDef def = byOrg.get(composicion);
        if (def == null || def.getOperaciones() == null || def.getOperaciones().isBlank()) {
            return List.of();
        }
        return List.of(def.getOperaciones().split(","));
    }
}