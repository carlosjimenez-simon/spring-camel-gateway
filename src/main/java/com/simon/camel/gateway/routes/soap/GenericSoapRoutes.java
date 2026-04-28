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
        rest("/api/v1/soap")
        	.post("/gateway-to/{organizacion}/{operacion}")
                .routeId("generic-soap-gateway")
                .to("direct:procesar-plantilla");

        // 2. Lógica Maestra
        from("direct:procesar-plantilla")
            .routeId("logic-velocity")
            
            // A. CAPTURA INICIAL: El JSON que llega de Postman
            .setProperty("rawRequest", body())
            
            .log("ID Transacción Recibido: ${header.breadcrumbId}")
            // 3. Aseguramos que el body sea un Mapa para Velocity
            .convertBodyTo(Map.class)
            
            // B. ESTRATEGIA: Extraemos qué auditoría usar
            .setHeader("audit-implementation", simple("${body[audit-implementation]}"))
            
            .log("Procesando Org: ${header.organizacion} - Op: ${header.operacion}")
            
            .setHeader("TechnicalAction", simple("${body[function-end-point]}"))
            
            .log("Procesando Org: ${header.organizacion} - Op: ${header.operacion} - TechAction: ${header.TechnicalAction}")
            
            // 4. Llamamos al procesador de headers
            .process("headerProcessor") 
            
            .log("Headers procesados: ${headers}")
            .log("Datos para plantilla: ${body}")
            
            // 5. Cargamos la plantilla dinámicamente
            .toD("velocity:templates/${header.organizacion}/${header.operacion}.vm")
            
            // C. CAPTURA XML ENVIADO: La plantilla ya renderizada
            .setProperty("xmlSent", body())
            
            // 6. Limpieza estándar de Camel
            .removeHeaders("CamelHttp*")
            
            
            
            // CONFIGURACIÓN PARA SOAP 1.2
            .setHeader("Content-Type", simple("application/soap+xml; charset=utf-8; action=\"http://www.mundialseguros.com.co/${header.TechnicalAction}\""))
            .setHeader("SOAPAction", simple("http://www.mundialseguros.com.co/${header.TechnicalAction}"))
	        
            .log("XML generado para ${header.organizacion}: ${body}")
            
            .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true")
            
             // D. CAPTURA XML RECIBIDO: Respuesta cruda del proveedor
            .setProperty("xmlReceived", body())
            
            .log("Respuesta recibida: ${body}")
            
            // 7. Convertimos el XML String a un Map de Java para que el process pueda leerlo
            .unmarshal().jacksonXml(Map.class) 
            
            .process(exchange -> {
                Map<String, Object> map = exchange.getIn().getBody(Map.class);
                if (map != null && !map.isEmpty()) {
                    // Tomamos el primer valor (usualmente el contenido del Body)
                    Object firstChild = map.values().iterator().next();
                    exchange.getIn().setBody(firstChild);
                }
            })

            // 8. LIMPIEZA TOTAL DE HEADERS
            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion") 
            .setHeader("Content-Type", constant("application/json"))
            
            .log("Respuesta REST recibida: ${body}")
            .wireTap("direct:audit-logic")
            
            .log("Enviando a Postman: ${body}");
    }
}