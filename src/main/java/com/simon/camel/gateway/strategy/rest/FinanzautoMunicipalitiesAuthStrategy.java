package com.simon.camel.gateway.strategy.rest;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FinanzautoMunicipalitiesAuthStrategy implements IRestSecurityStrategy{
	
	@Autowired
    private AmazonSecretsService _secretsService;

    @Override
    public String getFunctionName() { 
        return "create-header-finanzauto-municipalities"; 
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
        String apikey = secrets.get("X-API-KEY");

        log.info("Iniciando autenticación en Finanzauto para el usuario: {}", apikey);


        // 4. Inyectar el token en el Header de Camel como Bearer token
        if (apikey != null && !apikey.isEmpty()) {
        	log.info("Asigno el header");
            exchange.getIn().setHeader("X-API-KEY", apikey);
        } else {
            throw new IllegalStateException("No se pudo obtener el token de autenticación de Finanzauto");
        }
    }

}
