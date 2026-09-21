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
public class ApiEnterpriseAuthStrategy implements IRestSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

    @Override
    public String getFunctionName() {
        return "api-enterprise-auth";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

        // 1. Obtener nombre del secreto
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/enterprise-secret";
        if (params != null) {
            secretName = params.stream()
                    .filter(p -> "secret-name".equals(p.get("name")))
                    .map(p -> p.get("value"))
                    .findFirst()
                    .orElse("default/enterprise-secret");
        }

        log.info("Buscando secreto en AWS Secrets Manager para enterprise {}", secretName);

        // 2. Extraer credenciales y metadatos de AWS
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String username = secrets.get("agentemotor-username");
        String password = secrets.get("agentemotor-password");
        String tenant = secrets.get("agentemotor-tenant");
        String authUrl = secrets.get("agentemotor-auth-url");

        if (username == null || password == null || tenant == null || authUrl == null) {
            throw new IllegalStateException(
                    "El secreto de AWS no contiene las llaves 'username', 'password', 'tenant' o 'auth-url' para enterprise");
        }

        // 3. Obtener el Token de SBS
        // String authUrl =
        // "https://devsyli.sbseguros.co/sbs-api-fileProcessing/api/auth"; //url de Auth

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, String> authBody = new HashMap<>();
        authBody.put("username", username);
        authBody.put("password", password);
        authBody.put("tenant", tenant);

        org.springframework.http.HttpEntity<Map<String, String>> request = new org.springframework.http.HttpEntity<>(
                authBody, headers);

        log.info("Llamando al servicio de autenticacion de enterprise...");
        org.springframework.http.ResponseEntity<Map> response = restTemplate.postForEntity(authUrl, request, Map.class);

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("access_token")) {
            throw new IllegalStateException("Respuesta de auth enterprise invalida o sin data");
        }

        // Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        String token = (String) responseBody.get("access_token");

        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("El token recuperado de enterprise esta vacio");
        }

        log.info("Token de enterprise recuperado exitosamente.");

        // 4. Inyectar las cabeceras en el mensaje de Camel para los endpoints
        // subsecuentes
        exchange.getIn().setHeader("Authorization", "Bearer " + token);
        exchange.getIn().setHeader("Content-Type", "application/json");
        exchange.getIn().setHeader("Accept", "application/json, text/plain, /");
    }
}