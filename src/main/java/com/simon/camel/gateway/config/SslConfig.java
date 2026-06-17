package com.simon.camel.gateway.config;

import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@Configuration
public class SslConfig {

    @Bean(name = "allowAllHostnameVerifier")
    public HostnameVerifier allowAllHostnameVerifier() {
        return new NoopHostnameVerifier();
    }
    
    @Bean(name = "sslInseguroFineract")
    public SSLContextParameters sslInseguroFineract() {
        // 1. Crear un TrustManager que confíe en cualquier certificado ciegamente (Evita el PKIX path building failed)
        X509TrustManager trustAllCerts = new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() { return null; }
            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        };

        TrustManagersParameters tmp = new TrustManagersParameters();
        tmp.setTrustManager(trustAllCerts);

        // 2. Configurar los parámetros del contexto SSL para Camel
        SSLContextParameters scp = new SSLContextParameters();
        scp.setTrustManagers(tmp);
        
        return scp;
    }
}