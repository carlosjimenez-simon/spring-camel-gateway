package com.simon.camel.gateway.routes.soap;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class GenericSoapRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

    	// 1. La URL
        rest("/api/v1")
        	.post("/gateway-to/{organizacion}/{operacion}")
                .routeId("generic-soap-gateway")
                .to("direct:procesar-plantilla");

        // 2. Lógica Maestra
        from("direct:procesar-plantilla")
            .routeId("logic-velocity")
            // Aseguramos que el body sea un Mapa para Velocity
            .convertBodyTo(Map.class)
            .log("Procesando Org: ${header.organizacion} - Op: ${header.operacion}")
            
            // 2. Cargamos la plantilla dinámicamente
            .toD("velocity:templates/${header.organizacion}/${header.operacion}.vm")
            .removeHeaders("CamelHttp*")
            .setHeader("Content-Type", constant("application/xml"))
            .log("XML generado para ${header.organizacion}: ${body}")
            
            .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true")
            .log("Respuesta recibida: ${body}")
            
            // Convertimos el XML String a un Map de Java para que el process pueda leerlo
            .unmarshal().jacksonXml(Map.class) 
            
            .process(exchange -> {
                Map<String, Object> map = exchange.getIn().getBody(Map.class);
                if (map != null && !map.isEmpty()) {
                    // Tomamos el primer valor (usualmente el contenido del Body)
                    Object firstChild = map.values().iterator().next();
                    exchange.getIn().setBody(firstChild);
                }
            })

            // 3. Convertimos a JSON String explícitamente
            .marshal().json(JsonLibrary.Jackson)
            .convertBodyTo(String.class)

            // 4. LIMPIEZA TOTAL DE HEADERS
            // Eliminamos todo lo que pudo venir del servicio SOAP que confunda a Postman
            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion") 
            .setHeader("Content-Type", constant("application/json"))
            
            .log("Enviando a Postman: ${body}");
    }
}