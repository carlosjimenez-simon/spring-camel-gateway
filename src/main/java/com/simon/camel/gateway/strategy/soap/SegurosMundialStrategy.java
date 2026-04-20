package com.simon.camel.gateway.strategy.soap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.services.AmazonSecretsService;

@Component
public class SegurosMundialStrategy implements ISoapSecurityStrategy {
    
    @Autowired
    private AmazonSecretsService _secretsService;

    @Override
    public String getFunctionName() { return "createHeaderSegurosMundial"; }

    @SuppressWarnings("unchecked")
	@Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");
        
        String secretName = params.stream()
            .filter(p -> "secret-name".equals(p.get("name")))
            .map(p -> p.get("value"))
            .findFirst()
            .orElse("default/secret");

        Map<String, String> secrets = _secretsService.getAwsSecret(secretName);

        // Lógica de firma HMAC (la misma que tenías antes)
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String clientId = secrets.get("clientId");
        String secretKey = secrets.get("clientSecret");
        
        String message = timeStamp + "." + clientId + "." + datos.get("placa");
        String firma = calcularHMACSHA256(message, secretKey);

        exchange.getIn().setHeader("X-MUN-TIMESTAMP", timeStamp);
        exchange.getIn().setHeader("X-MUN-CLIENT", clientId);
        exchange.getIn().setHeader("X-MUN-SIGN", firma);
        exchange.getIn().setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((secrets.get("username") + ":" + secrets.get("password")).getBytes()));
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
