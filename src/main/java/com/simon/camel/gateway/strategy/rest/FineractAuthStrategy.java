package com.simon.camel.gateway.strategy.rest;

import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.SpringCamelGatewayApplication;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
public class FineractAuthStrategy implements IRestSecurityStrategy {
	
	@Override
    public String getFunctionName() { return "fineract-auth"; }

    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) {

        String authHeader = "Basic bWlmb3M6cGFzc3dvcmQ="; 
        
        exchange.getIn().setHeader("Authorization", authHeader);
        exchange.getIn().setHeader("Fineract-Platform-TenantId", "default");
        exchange.getIn().setHeader("Content-Type", "application/json");
        exchange.getIn().setHeader("Accept", "application/json");
    }

}
