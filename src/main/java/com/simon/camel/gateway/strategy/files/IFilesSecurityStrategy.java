package com.simon.camel.gateway.strategy.files;

import org.apache.camel.Exchange;
import java.util.Map;

public interface IFilesSecurityStrategy {
    String getFunctionName();
    void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception;
}