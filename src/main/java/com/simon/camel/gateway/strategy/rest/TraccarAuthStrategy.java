package com.simon.camel.gateway.strategy.rest;

import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.SpringCamelGatewayApplication;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TraccarAuthStrategy implements IRestSecurityStrategy {
	
	@Override
    public String getFunctionName() { return "traccar-auth"; }

    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig) {

        String authHeader = "Basic bWlmb3M6cGFzc3"; 
        
        exchange.getIn().setHeader("Authorization", authHeader);
        exchange.getIn().setHeader("Cookie", "xxx");
    }

}
