package com.simon.camel.gateway.strategy.soap;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MaquinariaAmarillaPonalStrategy implements ISoapSecurityStrategy {

    private final AmazonSecretsService secretsService;

    @Override
    public String getFunctionName() {
        return "createHeaderPoliciaNacional";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        
        // 1. Obtener nombre del secreto dinámicamente desde el headerConfig
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        String secretName = "default/ponal-secret";
        if (params != null) {
            secretName = params.stream()
                    .filter(p -> "secret-name".equals(p.get("name")))
                    .map(p -> p.get("value"))
                    .findFirst()
                    .orElse("default/ponal-secret");
        }

        // 2. Extraer 'pNumeroValido' desde 'datos'
        String numeroValido = (datos != null) ? (String) datos.get("pNumeroValido") : null;
        if (numeroValido == null || numeroValido.trim().isEmpty()) {
            throw new IllegalArgumentException("El campo 'pNumeroValido' es obligatorio en el objeto 'datos'");
        }

        // 3. Extraer solo usuario y clave de AWS Secrets Manager
        Map<String, String> secrets = secretsService.getAwsSecret(secretName);
        if (secrets == null) {
            throw new IllegalStateException("No se pudo obtener el secreto de AWS: " + secretName);
        }

        String usuario = secrets.get("pUsuarioProv");
        String clave = secrets.get("pClaveProv");
        
        if (usuario == null || clave == null) {
            throw new IllegalStateException(
                String.format("El secreto '%s' debe contener 'pUsuarioProv' y 'pClaveProv'", secretName)
            );
        }

        // 4. INYECTAR LAS CREDENCIALES DIRECTAMENTE EN EL MAPA 'datos'
        // De esta forma no se pierde ninguna propiedad y 'soapHeaderProcessor' o la ruta
        // entregarán el mapa completo a Velocity.
        datos.put("pUsuarioProv", usuario);
        datos.put("pClaveProv", clave);

        log.info("Mapa 'datos' actualizado con secretos de AWS: {}", datos);

        // 5. Headers HTTP para SOAP
        exchange.getIn().setHeader("Content-Type", "text/xml; charset=utf-8");
        exchange.getIn().setHeader("SOAPAction", "\"http://policia.gov.co/webservice/ValIngreso\"");
    }
}