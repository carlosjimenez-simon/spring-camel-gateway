package com.simon.camel.gateway.strategy.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.simon.camel.gateway.services.AmazonSecretsService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SBSPDFAuthStrategy implements IRestSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

    @Override
    public String getFunctionName() {
        return "sbs-pdf-auth";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

        // 1. Obtener nombre del secreto
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/sbs-secret";
        if (params != null) {
            secretName = params.stream()
                    .filter(p -> "secret-name".equals(p.get("name")))
                    .map(p -> p.get("value"))
                    .findFirst()
                    .orElse("default/sbs-secret");
        }

        log.info("Buscando secreto en AWS Secrets Manager para SBS {}", secretName);

        // 2. Extraer credenciales y metadatos de AWS
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String username = secrets.get("username");
        String password = secrets.get("password");

        if (username == null || password == null) {
            throw new IllegalStateException("El secreto de AWS no contiene las llaves 'appKey' o 'user' para SBS");
        }

        // 3. Obtener el Token de SBS
        String authUrl = "https://devsyli.sbseguros.co/api/v1/login";
        
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        
        Map<String, String> authBody = new HashMap<>();
        authBody.put("username", username);
        authBody.put("password", password);
        
        org.springframework.http.HttpEntity<Map<String, String>> request = new org.springframework.http.HttpEntity<>(authBody, headers);
        
        log.info("Llamando al servicio de autenticacion de SBS...");
        org.springframework.http.ResponseEntity<Map> response = restTemplate.postForEntity(authUrl, request, Map.class);
        
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("data")) {
            throw new IllegalStateException("Respuesta de auth SBS invalida o sin data");
        }
        
        Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        String token = (String) data.get("token");
        
        if (token == null || token.isEmpty()) {
             throw new IllegalStateException("El token recuperado de SBS esta vacio");
        }

        log.info("Token de SBS recuperado exitosamente.");

        // 4. Inyectar las cabeceras en el mensaje de Camel para los endpoints subsecuentes
        exchange.getIn().setHeader("Authorization", "Bearer " + token);
        exchange.getIn().setHeader("Content-Type", "application/json");
        exchange.getIn().setHeader("Accept", "*/*");
    }
}