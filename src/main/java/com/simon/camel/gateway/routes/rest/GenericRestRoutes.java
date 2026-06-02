package com.simon.camel.gateway.routes.rest;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import com.simon.camel.gateway.constant.Constants;
import java.util.Map;

@Component
public class GenericRestRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_REST)
            .post("/gateway-to/{organizacion}/{operacion}")
                .consumes("application/json")
                .produces("application/json")
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_REST)
                .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_REST);

        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_REST)
	        .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_REST)
	        
            .setProperty("rawRequest", body())
	        .convertBodyTo(Map.class)
	        
            .setHeader("audit-implementation", simple("${body[audit-implementation]}"))
	        
	        .setHeader("MetodoDestino", simple("${body[header][method]}", String.class))
	        .choice()
	            .when(header("MetodoDestino").isNull())
	                .setHeader("MetodoDestino", constant("GET"))
	        .end()
	        
	        .log("Procesando REST [${header.MetodoDestino}] - Org: ${header.organizacion} - Op: ${header.operacion}")
	        
	        // Ejecuta tu estrategia: mapea datos y setea Authorization / X-API-Version
	        .process("restHeaderProcessor") 
	        
	        .log("ID Transacción: ${header.breadcrumbId}")
	        
	        // Transformamos el sub-nodo "datos" a JSON String
	        .marshal().json(JsonLibrary.Jackson)
	        
	        // =================================================================
	        // VIOLENCIA DE LIMPIEZA: Borramos TODO excepto lo estrictamente vital
	        // =================================================================
	        // Borra absolutamente todos los headers entrantes de Postman y residuos de Camel,
	        // manteniendo ÚNICAMENTE los que le pasamos como excepción en los siguientes parámetros:
	        .removeHeaders("*", "Authorization", "X-API-Version", "MetodoDestino", "organizacion", "operacion", "audit-implementation", "breadcrumbId")
	        
	        // Headers requeridos por el API de Finanzauto
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("Accept", constant("application/json"))
	        .setHeader("CamelHttpMethod", header("MetodoDestino"))
	        
	        // =================================================================
	        // ESPÍA DE TELEMETRÍA (PROCESADOR PARA IMPRIMIR EN LOGS)
	        // =================================================================
	        .process(exchange -> {
	            log.info("=========================================================");
	            log.info(" INSPECCIÓN DE PETICIÓN SALIENTE HACIA EL BACKEND");
	            log.info("=========================================================");
	            log.info("URL Destino teórica: " + exchange.getContext().resolvePropertyPlaceholders("{{simon.endpoint." + exchange.getIn().getHeader("organizacion") + "." + exchange.getIn().getHeader("operacion") + "}}"));
	            log.info("Verbo HTTP (CamelHttpMethod): " + exchange.getIn().getHeader("CamelHttpMethod"));
	            log.info("Body saliente (JSON): " + exchange.getIn().getBody(String.class));
	            log.info("--- Headers presentes en el envío ---");
	            exchange.getIn().getHeaders().forEach((k, v) -> {
	                // Ocultamos parte del token en el log para que no sea gigante, pero confirmamos si va bien
	                if ("Authorization".equals(k) && v != null) {
	                    log.info("  " + k + " => " + v.toString().substring(0, Math.min(v.toString().length(), 25)) + "...");
	                } else {
	                    log.info("  " + k + " => " + v);
	                }
	            });
	            log.info("=========================================================");
	        })
	        
	        .circuitBreaker()
	            .id("cb-${header.organizacion}-${header.operacion}") // ID Único por operación
	            .resilience4jConfiguration()
	                .failureRateThreshold(50.0f) // 50% de umbral quemado
	                .waitDurationInOpenState(5)  // 5 segundos de espera quemados
	            .end() //
	            
	            .log("🚀 [CIRCUITO CERRADO] Intentando pegar a la red para: ${header.operacion}...")
	            
	            // La misma llamada exacta que ya le funciona
	            .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false") //
	            //.toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false&httpClient.connectTimeout=15000&httpClient.socketTimeout=15000") //
	            //.toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=true&httpClient.connectTimeout=15000&httpClient.socketTimeout=15000") //
	        
	        .onFallback()
	            // Si algo falla, solo metemos un log y devolvemos un string vacío controlado
	            .log("⚠️ Alerta: Circuit Breaker activado para la operación: ${header.operacion}")
	            .setBody(constant(""))
	        .end() // Cierra el bloque completo del circuit breaker
	        
	        // 6. Manejo de respuesta
	        .convertBodyTo(String.class) 
	        .log("Respuesta REST recibida: ${body}")
	
	        .choice()
	            .when(simple("${body} != null && ${body} != ''"))
	                .unmarshal().json(JsonLibrary.Jackson)
	        .end()
	
	        .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation")
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("HttpCharacterEncoding", constant("UTF-8"))
	        
	        .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST)
	        .log("Enviando a Postman: ${body}");
    }
}