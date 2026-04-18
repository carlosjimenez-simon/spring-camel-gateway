package com.simon.camel.gateway.processors;

import com.simon.camel.gateway.services.AmazonSecretsService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component("restHeaderProcessor")
public class RestHeaderProcessor implements Processor {

    @Autowired
    private AmazonSecretsService secretsService;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        
        // Validación básica para no romper si el JSON es sencillo
        if (body == null || !body.containsKey("header")) {
            return; 
        }

        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        String function = (String) headerConfig.get("function");

        if ("security-basic-auth".equals(function)) {
            // Ejemplo: Sacar credenciales de AWS para un API REST con Basic Auth
            String secretPath = "dev/api/rest-auth"; // Podrías sacarlo de params
            Map<String, String> secrets = secretsService.getAwsSecret(secretPath);
            
        }
        
        // IMPORTANTE: Para REST, el body que sigue suele ser lo que está en "datos"
        if (body.containsKey("datos")) {
            exchange.getIn().setBody(body.get("datos"));
        }
    }
}