package com.simon.camel.gateway.processors;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.services.AmazonSecretsService;
import com.simon.camel.gateway.strategy.soap.ISoapSecurityStrategy;

@Component("headerProcessor")
public class SoapHeaderProcessor implements Processor {
	
	private final Map<String, ISoapSecurityStrategy> strategies = new HashMap<>();

    @Autowired
    public SoapHeaderProcessor(List<ISoapSecurityStrategy> strategyList) {
        for (ISoapSecurityStrategy strategy : strategyList) {
            strategies.put(strategy.getFunctionName(), strategy);
        }
    }
	
    @Autowired
    private AmazonSecretsService _secretsService;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        Map<String, Object> datos = (Map<String, Object>) body.get("datos");

        String function = (String) headerConfig.get("function");

        ISoapSecurityStrategy strategy = strategies.get(function);
        if (strategy != null) {
            strategy.apply(exchange, headerConfig, datos);
        }

        exchange.getIn().setBody(datos);
    }

}