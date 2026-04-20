package com.simon.camel.gateway.strategy.soap;

import org.apache.camel.Exchange;
import java.util.Map;

public interface ISoapSecurityStrategy {
    void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception;
    String getFunctionName();
}
