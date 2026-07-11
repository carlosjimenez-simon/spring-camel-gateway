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
public class AssiyaAuthStrategy implements IRestSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    @Override
    public String getFunctionName() {
        return "assiya-auth";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        
        // 1. Obtener nombre del secreto
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/assiya-secret";
        if (params != null) {
            secretName = params.stream()
                .filter(p -> "secret-name".equals(p.get("name")))
                .map(p -> p.get("value"))
                .findFirst()
                .orElse("default/assiya-secret");
        }

        log.info("Buscando secreto en AWS Secrets Manager para Assiya: {}", secretName);

        // 2. Extraer credenciales y metadatos de AWS
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String apiKey = secrets.get("apiKey");
        String userId = secrets.get("userId");
        String ownerId = secrets.get("ownerId");
        String ownerName = secrets.get("ownerName");
        String buOwnerId = secrets.get("buOwnerId");
        String buOwnerName = secrets.get("buOwnerName");
        String companyId = secrets.get("companyId");
        

        if (apiKey == null) {
            throw new IllegalStateException("El secreto de AWS no contiene la llave 'apiKey' para Assiya");
        }

        // 3. Configurar Headers de la petición HTTP externa
        exchange.getIn().setHeader("Authorization", apiKey);
        exchange.getIn().setHeader("Content-Type", "application/json");

        // 4. Enriquecer el body (Mantenemos oculta la estructura institucional)
        if (datos != null) {
            log.info("Enriqueciendo el body de negocio con metadata de AWS...");
            
            String operacionActual = exchange.getIn().getHeader("operacion", String.class);
            
            if ("crear-servicio".equalsIgnoreCase(operacionActual)) {
            
	            Map<String, Object> owner = new HashMap<>();
	            owner.put("id", ownerId);
	            owner.put("name", ownerName);
	            owner.put("type", "Owner");
	            datos.put("owner", owner);
	
	            Map<String, Object> buOwner = new HashMap<>();
	            buOwner.put("id", buOwnerId);
	            buOwner.put("name", buOwnerName);
	            buOwner.put("type", "BuOwner");
	            datos.put("buOwner", buOwner);
	
	            Map<String, Object> creatorUser = new HashMap<>();
	            creatorUser.put("id", userId);
	            creatorUser.put("name", "Gateway Automático " + ownerName);
	            datos.put("creatorUser", creatorUser);
	            
            }if ("buscar-servicio".equalsIgnoreCase(operacionActual)) {
            	datos.put("companyId", companyId);
            	datos.put("userId", userId);
            }if ("detalle-suscripcion".equalsIgnoreCase(operacionActual)) {
            	datos.put("companyId", companyId);
            } else {
                log.info("Operación '{}' no requiere enriquecimiento de metadata en el body.", operacionActual);
            }
        }
    }
}