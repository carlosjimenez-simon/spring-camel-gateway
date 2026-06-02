package com.simon.camel.gateway.strategy.rest;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FinanzautoRuntAuthStrategy implements IRestSecurityStrategy{
	
	@Autowired
    private AmazonSecretsService _secretsService;

    // Usamos un único RestTemplate para las peticiones HTTP
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() { 
        return "create-header-finanzauto-runt"; 
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        // 1. Obtener el nombre del secreto desde los parámetros de configuración
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        
        String secretName = params.stream()
            .filter(p -> "secret-name".equals(p.get("name")))
            .map(p -> p.get("value"))
            .findFirst()
            .orElse("default/finanzauto-secret");

        // 2. Extraer las credenciales del AWS Secret Manager
        Map<String, String> secrets = _secretsService.getAwsSecret(secretName);
        String username = secrets.get("username");
        String password = secrets.get("password");
        String endpoint = secrets.get("end-point");
        String apiVersion = secrets.get("x-api-version");

        log.info("Iniciando autenticación en Finanzauto para el usuario: {}", username);

        // 3. Consumir el servicio de login para obtener el JWT Token
        String token = obtenerBearerToken(endpoint, username, password);

        // 4. Inyectar el token en el Header de Camel como Bearer token
        if (token != null && !token.isEmpty()) {
            exchange.getIn().setHeader("Authorization", "Bearer " + token);
            log.info("Token de Finanzauto Runt asignado exitosamente al header Authorization.");
            log.info("====inicio======");
            log.info("Token obtenido:{}", token);
            log.info("====fin======");
            if (apiVersion != null && !apiVersion.isEmpty()) {
                exchange.getIn().setHeader("X-API-Version", apiVersion);
                log.info("Header X-API-Version asignado exitosamente con valor: {}", apiVersion);
            } else {
                log.warn("El campo 'x-api-version' no se encontró o está vacío en el secreto.");
            }
        } else {
            throw new IllegalStateException("No se pudo obtener el token de autenticación de Finanzauto");
        }
    }

    private String obtenerBearerToken(String endpoint, String username, String password) {
        try {
            // Configurar los headers de la petición de login
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Crear el cuerpo del JSON (Request)
            LoginRequest requestBody = new LoginRequest(username, password);
            HttpEntity<LoginRequest> entity = new HttpEntity<>(requestBody, headers);

            // Hacer el POST
            ResponseEntity<LoginResponse> response = restTemplate.postForEntity(endpoint, entity, LoginResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().getToken();
            } else {
                log.error("Error en la respuesta del servicio de autenticación: {}", response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("Error al intentar autenticarse con Finanzauto en el endpoint: {}", endpoint, e);
            return null;
        }
    }

    // --- DTOs Internos para mapear Request y Response con Jackson automáticamente ---

    @Data
    private static class LoginRequest {
        private String userName; // Ojo a la 'N' mayúscula según tu ejemplo de JSON
        private String password;

        public LoginRequest(String userName, String password) {
            this.userName = userName;
            this.password = password;
        }
    }

    @Data
    private static class LoginResponse {
        private String userId;
        private String userName;
        private String token;
        private String refreshToken;
        private String applicationName;
        private String rolId;
        private String rolName;
    }

}
