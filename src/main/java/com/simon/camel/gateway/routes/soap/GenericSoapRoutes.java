package com.simon.camel.gateway.routes.soap;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class GenericSoapRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // 1. Definición del REST DSL
        rest("/api/v1")
            .post("/seguros/{operacion}")
                .routeId("generic-soap-gateway")
                // Aquí ya no definimos .type() porque queremos que sea GENÉRICO (Map)
                .to("direct:procesar-plantilla");

        // 2. Lógica Maestra con Velocity
        from("direct:procesar-plantilla")
	        .routeId("logic-velocity")
	        
	        // 1. ELIMINAMOS EL UNMARSHAL. 
	        // Si Camel ya lo convirtió a Mapa, esto sobra. 
	        // Si por casualidad llegara como String, Camel lo convierte a Mapa automáticamente al usar Velocity si es necesario.
	        
	        .log("Datos que llegan a la plantilla: ${body}")
	        
	        // 2. Cargamos la plantilla
	        .toD("velocity:templates/${header.operacion}.vm")
	        
	        // 3. Headers y envío
	        .removeHeaders("CamelHttp*")
	        .setHeader("Content-Type", constant("application/xml"))
	        .log("XML generado: ${body}")
	        
	        .to("http://localhost:8081/ws/clientes?bridgeEndpoint=true")
	        .log("Respuesta: ${body}");
    }
}