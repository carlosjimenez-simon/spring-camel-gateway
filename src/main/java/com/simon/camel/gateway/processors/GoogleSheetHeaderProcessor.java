package com.simon.camel.gateway.processors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.strategy.rest.IRestSecurityStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * Processor dedicado al flujo de Google Sheet.
 *
 * Sigue el mismo patron que {@link SoapHeaderProcessor} y {@link RestHeaderProcessor}:
 *  - Lee el campo {@code header.function} del body.
 *  - Busca la estrategia registrada con ese nombre y la ejecuta.
 *
 * A diferencia de {@link RestHeaderProcessor}, este procesador publica
 * el mapa {@code datos} enriquecido como body de salida, ya que
 * {@link com.simon.camel.gateway.strategy.rest.GoogleSheetAuthStrategy}
 * escribe lookupCode / matchedRow / values directamente dentro de {@code datos}.
 */
@Slf4j
@Component("googleSheetHeaderProcessor")
public class GoogleSheetHeaderProcessor implements Processor {

    private final Map<String, IRestSecurityStrategy> strategies = new HashMap<>();

    @Autowired
    public GoogleSheetHeaderProcessor(List<IRestSecurityStrategy> strategyList) {
        for (IRestSecurityStrategy strategy : strategyList) {
            log.info("[GoogleSheetHeaderProcessor] Registrada estrategia: {}", strategy.getFunctionName());
            strategies.put(strategy.getFunctionName(), strategy);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);

        if (body == null || !body.containsKey("header")) {
            log.warn("[GoogleSheetHeaderProcessor] Body sin bloque 'header'; no se aplica estrategia.");
            return;
        }

        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        Map<String, Object> datos        = (Map<String, Object>) body.get("datos");

        String function = (String) headerConfig.get("function");
        if (function == null || function.isBlank()) {
            log.warn("[GoogleSheetHeaderProcessor] header.function ausente; no se aplica estrategia.");
            return;
        }

        IRestSecurityStrategy strategy = strategies.get(function);
        if (strategy == null) {
            log.warn("[GoogleSheetHeaderProcessor] No existe estrategia para function='{}'; se omite.", function);
            return;
        }

        log.info("[GoogleSheetHeaderProcessor] Ejecutando estrategia '{}' para Google Sheet.", function);
        strategy.apply(exchange, headerConfig, datos);

        // Publicamos el mapa 'datos' enriquecido como body de salida.
        // GoogleSheetAuthStrategy ya escribio lookupCode / matchedRow / values dentro de 'datos'.
        if (datos != null) {
            exchange.getIn().setBody(datos);
        }
    }
}