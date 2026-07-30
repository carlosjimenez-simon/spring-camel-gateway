package com.simon.camel.gateway.processors;

import com.simon.camel.gateway.strategy.files.IFilesSecurityStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("filesHeaderProcessor")
public class FilesHeaderProcessor implements Processor {

    private final Map<String, IFilesSecurityStrategy> strategies = new HashMap<>();

    @Autowired
    public FilesHeaderProcessor(List<IFilesSecurityStrategy> strategyList) {
        for (IFilesSecurityStrategy strategy : strategyList) {
            log.info("[FilesHeaderProcessor] Registrada estrategia: {}", strategy.getFunctionName());
            strategies.put(strategy.getFunctionName(), strategy);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);

        if (body == null || !body.containsKey("header")) {
            return;
        }

        Map<String, Object> datos = (Map<String, Object>) body.get("datos");
        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        String function = (String) headerConfig.get("function");

        IFilesSecurityStrategy strategy = strategies.get(function);
        if (strategy != null) {
            strategy.apply(exchange, headerConfig, datos);
        }

        if (body.containsKey("datos")) {
            exchange.getIn().setBody(body.get("datos"));
        }
    }
}