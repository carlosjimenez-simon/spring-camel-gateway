package com.simon.camel.gateway.strategy.files;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;

@Slf4j
@Component
public class DummyFilesStrategy implements IFilesSecurityStrategy {

    @Override
    public String getFunctionName() {
        return "print-files-auth";
    }

    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        log.info("Executing Dummy Strategy for Files: Auth print completed successfully.");
    }
}