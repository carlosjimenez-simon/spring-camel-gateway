package com.simon.camel.gateway.processors;




import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component("headerProcessor")
public class HeaderProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");
        Map<String, Object> datos = (Map<String, Object>) body.get("datos");

        String function = (String) headerConfig.get("function");
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");

        // Centralizamos la creación de headers por cliente
        if ("createHeaderSegurosMundial".equals(function)) {
            // Buscamos el valor del secret-name dentro de los parámetros
            String secretName = params.stream()
                .filter(p -> "secret-name".equals(p.get("name")))
                .map(p -> p.get("value"))
                .findFirst()
                .orElse("default/secret");

            aplicarSeguridadMundial(exchange, datos, secretName);
        }

        exchange.getIn().setBody(datos);
    }

    private void aplicarSeguridadMundial(Exchange exchange, Map<String, Object> datos, String secretName) throws Exception {
        // 1. Aquí llamarías a tu servicio de Secret Manager usando el 'secretName'
        // Por ahora simulamos que el servicio nos devuelve un mapa con las llaves
        Map<String, String> secrets = mockSecretManager(secretName);

        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String clientId = secrets.get("clientId");
        String secretKey = secrets.get("clientSecret"); // La llave para el HMAC
        String usuario = secrets.get("username");
        String password = secrets.get("password");
        
        String placa = (String) datos.get("placa");

        // 2. Cálculo de la Firma (HMAC-SHA256)
        String message = timeStamp + "." + clientId + "." + placa;
        String firma = calcularHMACSHA256(message, secretKey);

        // 3. Cálculo dinámico de Authorization
        String authRaw = usuario + ":" + password;
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(authRaw.getBytes(StandardCharsets.UTF_8));

        // 4. Inyeccion de Headers
        exchange.getIn().setHeader("X-MUN-TIMESTAMP", timeStamp);
        exchange.getIn().setHeader("X-MUN-CLIENT", clientId);
        exchange.getIn().setHeader("X-MUN-SIGN", firma);
        exchange.getIn().setHeader("Authorization", authHeader);
        
        System.out.println("Seguridad aplicada desde Secret: " + secretName + " | Placa: " + placa);
    }

    // Método simulado - Aquí iría la integración con AWS Secrets Manager o similar
    private Map<String, String> mockSecretManager(String secretPath) {
        // En la vida real, aquí harías: return awsSecretsClient.getSecret(secretPath);
        if ("dev/polizas/mundial-seguros".equals(secretPath)) {
            return Map.of(
                "clientId", "M2pxta1sNmqbnxFtp/wLpTMoNTXEolk48u3M21vs5G2AgQEZFQXj6g==",
                "clientSecret", "qtOtjv5F8jDgvychanjI01PhZkXS+4KEtgXKbL23uXw=",
                "username", "usrWSSoapSoatSimProm",
                "password", "Ipe3r660CZCa"
            );
        }
        return Map.of();
    }

    private String calcularHMACSHA256(String data, String key) throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(signingKey);
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }
}