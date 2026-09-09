package com.simon.camel.gateway.strategy.soap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.SpringCamelGatewayApplication;
import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MaquinariaAmarillaPonalStrategy implements ISoapSecurityStrategy {

    @Autowired
    private AmazonSecretsService secretsService;

    @Override
    public String getFunctionName() {
        return "createHeaderPoliciaNacional";
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        
        // 1. Obtener nombre del secreto
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/ponal-secret";
        if (params != null) {
            secretName = params.stream()
                    .filter(p -> "secret-name".equals(p.get("name")))
                    .map(p -> p.get("value"))
                    .findFirst()
                    .orElse("default/ponal-secret");
        }

        // 2. Extraer credenciales de AWS Secrets Manager
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        String usuario = secrets.get("pUsuarioProv");
        String clave = secrets.get("pClaveProv");
        String numeroValido = secrets.get("pNumeroValido");
        
        if (usuario == null || clave == null) {
            throw new IllegalStateException("El secreto no contiene 'username' o 'password' para PONAL");
        }


        // 4. Armar el Map que consumirá la plantilla Velocity ${body.get(...)}
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("pUsuarioProv", usuario);
        bodyMap.put("pClaveProv", clave);
        bodyMap.put("pNumeroValido", numeroValido);

        // Asignar el mapa al Body de Camel
        exchange.getIn().setBody(bodyMap);

        // 5. Configurar Headers HTTP requeridos por la Policía
        exchange.getIn().setHeader("Content-Type", "text/xml; charset=utf-8");
        exchange.getIn().setHeader("SOAPAction", "\"http://policia.gov.co/webservice/ValIngreso\"");
        

    }

}
