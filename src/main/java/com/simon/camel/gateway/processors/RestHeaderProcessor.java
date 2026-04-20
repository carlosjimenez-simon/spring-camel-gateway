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
            
        }else if ("fineract-auth".equals(function)) {
            // Credenciales del demo: mifos / password
            String authHeader = "Basic bWlmb3M6cGFzc3dvcmQ="; 
            exchange.getIn().setHeader("Authorization", authHeader);
            // Ajustamos el nombre del header según el curl del navegador (v2)
            exchange.getIn().setHeader("Fineract-Platform-TenantId", "default");
            
            // Forzamos JSON ya que Fineract es estricto con el Content-Type
            exchange.getIn().setHeader("Content-Type", "application/json");
            exchange.getIn().setHeader("Accept", "application/json");
        } 
        
        // IMPORTANTE: Para REST, el body que sigue suele ser lo que está en "datos"
        if (body.containsKey("datos")) {
            exchange.getIn().setBody(body.get("datos"));
        }
    }
}