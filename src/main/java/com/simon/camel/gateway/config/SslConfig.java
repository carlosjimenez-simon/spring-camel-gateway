package com.simon.camel.gateway.config;

import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.net.ssl.HostnameVerifier;

@Configuration
public class SslConfig {

    @Bean(name = "allowAllHostnameVerifier")
    public HostnameVerifier allowAllHostnameVerifier() {
        return new NoopHostnameVerifier();
    }
}