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

@Slf4j
@Component("compositionAuthProcessor")
public class CompositionAuthProcessor implements Processor {

    private final Map<String, IRestSecurityStrategy> strategies = new HashMap<>();

    @Autowired
    public CompositionAuthProcessor(List<IRestSecurityStrategy> strategyList) {
        for (IRestSecurityStrategy strategy : strategyList) {
            log.info("[CompositionAuthProcessor] Registrada estrategia: {}", strategy.getFunctionName());
            strategies.put(strategy.getFunctionName(), strategy);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        if (body == null || !body.containsKey("header")) {
            log.debug("[CompositionAuthProcessor] Body sin 'header'; no se aplica estrategia de auth.");
            return;
        }

        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        Map<String, Object> datos = (Map<String, Object>) body.get("datos");

        String function = (String) headerConfig.get("function");
        if (function == null || function.isBlank()) {
            log.debug("[CompositionAuthProcessor] header.function ausente; composición sin autenticación aplicada.");
            return;
        }

        IRestSecurityStrategy strategy = strategies.get(function);
        if (strategy == null) {
            log.warn("[CompositionAuthProcessor] No existe estrategia REST para function='{}'; se omite auth.", function);
            return;
        }

        strategy.apply(exchange, headerConfig, datos);
        log.info("[CompositionAuthProcessor] Auth '{}' aplicada 1× para la composición.", function);

        if (datos != null) {
            exchange.getIn().setBody(datos);
        }
    }
}