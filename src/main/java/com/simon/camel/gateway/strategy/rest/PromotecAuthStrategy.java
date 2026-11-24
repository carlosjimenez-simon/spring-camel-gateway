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
public class PromotecAuthStrategy implements IRestSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

    @Override
    public String getFunctionName() {
        return "promotec-auth";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

        // 1. Obtener nombre del secreto
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/promotec-secret";
        if (params != null) {
            secretName = params.stream()
                    .filter(p -> "secret-name".equals(p.get("name")))
                    .map(p -> p.get("value"))
                    .findFirst()
                    .orElse("default/promotec-ecret");
        }

        log.info("Buscando secreto en AWS Secrets Manager para promotec {}", secretName);

        // 2. Extraer credenciales y metadatos de AWS
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String authUrl = secrets.get("auth-url");
        String username = secrets.get("user_name");
        String password = secrets.get("pass");

        if (username == null || password == null || authUrl == null) {
            throw new IllegalStateException(
                    "El secreto de AWS no contiene las llaves 'username', 'password' o 'auth-url' para promotec");
        }

        // 3. Obtener el Token de promotec
        // String authUrl = "https://qaservicios.promotec.com.co"; //url de Auth

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, String> authBody = new HashMap<>();
        authBody.put("usr_Login", username);
        authBody.put("Usr_PassIng", password);

        org.springframework.http.HttpEntity<Map<String, String>> request = new org.springframework.http.HttpEntity<>(
                authBody, headers);

        log.info("Llamando al servicio de autenticacion de promotec (POST {})...", authUrl);

        org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(authUrl, request,
                String.class);

        String body = response.getBody();

        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Respuesta de auth promotec invalida o sin data");
        }

        // La respuesta es un JSON: { "token": "eyJ..." }
        String token = body.trim();
        if (token.startsWith("{")) {
            com.fasterxml.jackson.databind.JsonNode tokenNode = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(token).get("token");
            token = (tokenNode != null && !tokenNode.isNull()) ? tokenNode.asText() : null;
        } else if (token.startsWith("\"") && token.endsWith("\"")) {
            // Fallback: texto plano entre comillas ("ey...")
            token = token.substring(1, token.length() - 1);
        }

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Respuesta de auth promotec sin campo 'token'");
        }

        log.info("Token de promotec recuperado exitosamente.");

        // 4. Inyectar las cabeceras en el mensaje de Camel para los endpoints
        // subsecuentes
        exchange.getIn().setHeader("Authorization", "Bearer " + token);
        exchange.getIn().setHeader("Content-Type", "application/json");
        // exchange.getIn().setHeader("Accept", "application/json, text/plain, /");
    }
}