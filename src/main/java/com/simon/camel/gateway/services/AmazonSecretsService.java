package com.simon.camel.gateway.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import java.util.Map;

@Service
public class AmazonSecretsService {

    private final SecretsManagerClient secretsClient = SecretsManagerClient.builder()
            .region(Region.US_EAST_1)
            .build();
            
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, String> getAwsSecret(String secretName) {
        try {
            GetSecretValueRequest valueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse valueResponse = secretsClient.getSecretValue(valueRequest);
            return objectMapper.readValue(valueResponse.secretString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo secreto: " + secretName, e);
        }
    }
}