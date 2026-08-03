package com.simon.camel.gateway.strategy.rest;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FinandinaAuthStrategy implements IRestSecurityStrategy {
	
	@Autowired
	private AmazonSecretsService _secretsService;

	@Override
	public String getFunctionName() { 
		return "finandina-auth"; 
	}

	@SuppressWarnings("unchecked")
	@Override
	public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

		// 1. Obtener el nombre del secreto desde los parámetros de configuración (Igual que en Finanzauto)
		List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
		
		String secretName = "default/finandina-secret"; // Valor por defecto por si acaso
		if (params != null) {
			secretName = params.stream()
				.filter(p -> "secret-name".equals(p.get("name")))
				.map(p -> p.get("value"))
				.findFirst()
				.orElse("default/finandina-secret");
		}

		log.info("Buscando secreto en AWS Secrets Manager para Finandina: {}", secretName);

		// 2. Extraer las credenciales del AWS Secret Manager
		Map<String, String> secrets = _secretsService.getAwsSecret(secretName);
		String apiKeyFinandina = secrets.get("x-Gateway-APIKey");
		log.info("apiKeyFinandina: {}", apiKeyFinandina);
		
		if (apiKeyFinandina == null) {
			throw new IllegalStateException("El secreto de AWS no contiene las llaves 'apiKey' para Finandina");
		}

		// 4. Inyectar las cabeceras en el mensaje de Camel
		exchange.getIn().setHeader("x-Gateway-APIKey", apiKeyFinandina);
		
		
	}
}