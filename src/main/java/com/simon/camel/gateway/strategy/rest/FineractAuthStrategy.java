package com.simon.camel.gateway.strategy.rest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FineractAuthStrategy implements IRestSecurityStrategy {
	
	@Autowired
	private AmazonSecretsService _secretsService;

	@Override
	public String getFunctionName() { 
		return "fineract-auth"; 
	}

	@SuppressWarnings("unchecked")
	@Override
	public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

		// 1. Obtener el nombre del secreto desde los parámetros de configuración (Igual que en Finanzauto)
		List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
		
		String secretName = "default/fineract-secret"; // Valor por defecto por si acaso
		if (params != null) {
			secretName = params.stream()
				.filter(p -> "secret-name".equals(p.get("name")))
				.map(p -> p.get("value"))
				.findFirst()
				.orElse("default/fineract-secret");
		}

		log.info("Buscando secreto en AWS Secrets Manager para Fineract: {}", secretName);

		// 2. Extraer las credenciales del AWS Secret Manager
		Map<String, String> secrets = _secretsService.getAwsSecret(secretName);
		String username = secrets.get("username");
		String password = secrets.get("password");
		String tenantId = secrets.getOrDefault("tenant-id", "default"); // Por si quieres parametrizar el Tenant también
		log.info("USERNAME: {}", username);
		log.info("PASSWORD: {}", password);

		if (username == null || password == null) {
			throw new IllegalStateException("El secreto de AWS no contiene las llaves 'username' o 'password' para Fineract");
		}

		// 3. Crear el String de Basic Authentication en Base64 dinámicamente
		String credentials = username + ":" + password;
		String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		String authHeader = "Basic " + encodedCredentials; 
		
		// 4. Inyectar las cabeceras en el mensaje de Camel
		exchange.getIn().setHeader("Authorization", authHeader);
		//exchange.getIn().setHeader("Fineract-Platform-TenantId", tenantId);
		exchange.getIn().setHeader("Fineract-Platform-TenantId", "default");
		exchange.getIn().setHeader("Content-Type", "application/json");
		exchange.getIn().setHeader("Accept", "application/json");

		log.info("Headers de Fineract configurados exitosamente para el usuario: {}", username);
	}
}