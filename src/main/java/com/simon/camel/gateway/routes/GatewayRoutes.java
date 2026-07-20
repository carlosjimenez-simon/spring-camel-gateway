package com.simon.camel.gateway.routes;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GatewayRoutes extends RouteBuilder {
	
	@Value("${netty.port:9000}")
    private int nettyPort;
	
	@Value("${netty.context-path:/simon-sprint-camel}")
    private String nettyContextPath;

	@Override
    public void configure() throws Exception {
        
        // Configuración REST DSL
        restConfiguration()
            .component("netty-http")  //defecto: servlet
            .host("0.0.0.0") // Permite conexiones desde cualquier IP
            .port(nettyPort)      // Puerto dedicado para el tráfico de alta velocidad
            .contextPath(nettyContextPath)
            .bindingMode(RestBindingMode.auto);
    }
}