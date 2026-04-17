package com.simon.camel.gateway.processors;




import java.util.Base64;
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

        // Centralizamos la creación de headers por cliente
        if ("createHeaderSegurosMundial".equals(function)) {
            aplicarSeguridadMundial(exchange, datos);
        }

        // Dejamos el body listo para Velocity
        exchange.getIn().setBody(datos);
    }

    private void aplicarSeguridadMundial(Exchange exchange, Map<String, Object> datos) throws Exception {
        // 1. Datos base y Credenciales
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String clientId = "M2pxta1sNmqbnxFtp/wLpTMoNTXEolk48u3M21vs5G2AgQEZFQXj6g=="; // Esto vendrá de Secrets
        String secret = "qtOtjv5F8jDgvychanjI01PhZkXS+4KEtgXKbL23uXw="; // Key para la firma HMAC
        
        // Variables para el Basic Auth (Próximamente desde properties/secrets)
        String usuario = "usrWSSoapSoatSimProm"; 
        String password = "Ipe3r660CZCa";
        
        String placa = (String) datos.get("placa");

        // 2. Cálculo de la Firma (HMAC-SHA256)
        // Según manual: timeStamp + "." + clientId + "." + placa
        String message = timeStamp + "." + clientId + "." + placa;
        String firma = calcularHMACSHA256(message, secret);

        // 3. Cálculo dinámico de Authorization (Base64)
        String authRaw = usuario + ":" + password;
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(authRaw.getBytes(StandardCharsets.UTF_8));

        // 4. Inyectamos los 4 headers obligatorios
        exchange.getIn().setHeader("X-MUN-TIMESTAMP", timeStamp);
        exchange.getIn().setHeader("X-MUN-CLIENT", clientId);
        exchange.getIn().setHeader("X-MUN-SIGN", firma);
        exchange.getIn().setHeader("Authorization", authHeader);
        
        // Log de auditoría
        System.out.println("Seguridad Mundial aplicada. Placa: " + placa + " | TS: " + timeStamp);
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