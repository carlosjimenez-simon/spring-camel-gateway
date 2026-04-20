package com.simon.camel.gateway.processors;

import com.simon.camel.gateway.services.AmazonSecretsService;
import com.simon.camel.gateway.strategy.rest.IRestSecurityStrategy;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("restHeaderProcessor")
public class RestHeaderProcessor implements Processor {

	private final Map<String, IRestSecurityStrategy> strategies = new HashMap<>();
	
	@Autowired
    public RestHeaderProcessor(List<IRestSecurityStrategy> strategyList) {
        // Mapeamos cada estrategia por su nombre de función
        for (IRestSecurityStrategy strategy : strategyList) {
            strategies.put(strategy.getFunctionName(), strategy);
        }
    }

	@Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        
        if (body == null || !body.containsKey("header")) return;

        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        String function = (String) headerConfig.get("function");

        // Buscamos la estrategia y la aplicamos sin un solo IF
        IRestSecurityStrategy strategy = strategies.get(function);
        if (strategy != null) {
            strategy.apply(exchange, headerConfig);
        }

        // Mantenemos la lógica de pasar los datos al body
        if (body.containsKey("datos")) {
            exchange.getIn().setBody(body.get("datos"));
        }
    }
}