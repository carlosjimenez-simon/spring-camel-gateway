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
public class CarSharingAuthStrategy implements IRestSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

    @Override
    public String getFunctionName() {
        return "carsharing-auth";
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

        log.info("Buscando secreto en AWS Secrets Manager para carsharing {}", secretName);

        // 2. Extraer credenciales y metadatos de AWS
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String authUrl = secrets.get("auth-url");
        String username = secrets.get("username");
        String password = secrets.get("password");
        String NIT = secrets.get("NIT");
        String RazonSocial = secrets.get("RazonSocial");

        if (username == null || password == null || NIT == null || authUrl == null) {
            throw new IllegalStateException(
                    "El secreto de AWS no contiene las llaves 'username', 'password', 'NIT' o 'auth-url' para carsharing");
        }

        // 3. Obtener el Token de carsharing
        // String authUrl =
        // "http://rerun.com.co:10182/administracion/autenticar/credenciales; //url de
        // Auth

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, String> authBody = new HashMap<>();
        authBody.put("strUsuario", username);
        authBody.put("strContrasena", password);
        authBody.put("strNIT", NIT);
        authBody.put("strRazonSocial", RazonSocial);

        org.springframework.http.HttpEntity<Map<String, String>> request = new org.springframework.http.HttpEntity<>(
                authBody, headers);

        log.info("Llamando al servicio de autenticacion de carsharing...");
        org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity(authUrl, request,
                String.class);

        String token = response.getBody();

        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("Respuesta de auth carsharing invalida o sin data");
        }

        // Eliminar las comillas dobles si la respuesta las incluye ("ey...")
        if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1);
        }

        log.info("Token de carsharing recuperado exitosamente.");

        // 4. Inyectar las cabeceras en el mensaje de Camel para los endpoints
        // subsecuentes
        exchange.getIn().setHeader("Authorization", "Bearer " + token);
        exchange.getIn().setHeader("Content-Type", "application/json");
        //exchange.getIn().setHeader("Accept", "application/json, text/plain, /");
    }
}