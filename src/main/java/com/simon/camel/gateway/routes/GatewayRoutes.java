package com.simon.camel.gateway.routes;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

@Component
public class GatewayRoutes extends RouteBuilder {

	@Override
    public void configure() throws Exception {
        
        // Configuración global del REST DSL
        restConfiguration()
            .component("servlet")
            .bindingMode(RestBindingMode.auto);

        // Tus rutas del Gateway
        rest("/api")
            .get("/test")
                .routeId("test-rest")
                .to("direct:saludo-final");

        from("direct:saludo-final")
            .setBody(constant("¡Gateway configurado correctamente en /gateway/api/test!"));
            
        // PREPARADO PARA SALIDA:
        // Aquí es donde luego pondremos los .to("http://...") hacia afuera
    }
}