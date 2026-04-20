package com.simon.camel.gateway.strategy.rest;

import java.util.Map;

import org.apache.camel.Exchange;

public interface IRestSecurityStrategy {

	void apply(Exchange exchange, Map<String, Object> headerConfig);
    String getFunctionName();
}
