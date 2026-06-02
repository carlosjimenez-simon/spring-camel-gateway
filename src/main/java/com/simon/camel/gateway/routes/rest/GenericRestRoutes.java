package com.simon.camel.gateway.routes.rest;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import com.simon.camel.gateway.constant.Constants;
import com.simon.camel.gateway.strategy.aggregation.MulticallAggregationStrategy;

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
            
            // Extrayendo el flag de multicall (por defecto false si viene nulo)
            .setHeader("Multicall", simple("${body[header][multicall]}"))
	        
            .choice()
	            .when(header("MetodoDestino").isNull())
	                .setHeader("MetodoDestino", constant("GET")) 
	        .end()
	        
	        .log("Procesando REST [${header.MetodoDestino}] - Org: ${header.organizacion} - Op: ${header.operacion} - Multicall: ${header.Multicall}") 
	        
            // Guardamos en propiedades los headers de control
            .setProperty("ControlHeader", simple("${body[header]}"))
            
            // =================================================================
            // EVALUACIÓN DE CAMINOS (Tipado estricto Camel 4)
            // =================================================================
            .choice()
                // --- CAMINO A: MULTICALL ACTIVADO ---
                .when(header("Multicall").isEqualTo(true))
                    .log("Detectado arreglo de datos. Iniciando Splitter...")
                    .split(simple("${body[datos]}"), new MulticallAggregationStrategy())
                        .process(exchange -> {
                            Object individualDatos = exchange.getIn().getBody();
                            Object headerConfig = exchange.getProperty("ControlHeader");
                            
                            java.util.Map<String, Object> virtualBody = new java.util.HashMap<>();
                            virtualBody.put("header", headerConfig);
                            virtualBody.put("datos", individualDatos);
                            
                            exchange.getIn().setBody(virtualBody);
                        })
                        .to("direct:sub-procesar-request-backend")
                    .end() // Este .end() cierra el SPLIT
                    .endChoice() // Este .endChoice() le avisa a Camel 4 que regresamos al CHOICE
                
                // --- CAMINO B: CONTROL TRADICIONAL ---
                .otherwise()
                    .to("direct:sub-procesar-request-backend")
            .end() // Este .end() cierra definitivamente el CHOICE principal
            
            // =================================================================
            // SALIDA Y RESPUESTA FINAL HACIA POSTMAN
            // =================================================================
            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation") 
	        .setHeader("Content-Type", constant("application/json")) 
	        .setHeader("HttpCharacterEncoding", constant("UTF-8")) 
	        
	        .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST) 
	        .log("Enviando respuesta final a Postman: ${body}"); 


        // =================================================================
        // RUTA CORE: UNITARIA AL BACKEND
        // =================================================================
        from("direct:sub-procesar-request-backend")
            .routeId("simon-sub-route-core-backend")
            
	        .process("restHeaderProcessor") 
	        .log("ID Transacción: ${header.breadcrumbId}") 
	        
	        .marshal().json(JsonLibrary.Jackson) 
	        
	        .removeHeaders("*", "Authorization", "X-API-Version", "MetodoDestino", "organizacion", "operacion", "audit-implementation", "breadcrumbId") 
	        
	        .setHeader("Content-Type", constant("application/json")) 
	        .setHeader("Accept", constant("application/json")) 
	        .setHeader("CamelHttpMethod", header("MetodoDestino")) 
	        
	        .circuitBreaker()
	            .id("cb-${header.organizacion}-${header.operacion}") 
	            .resilience4jConfiguration()
	                .failureRateThreshold(50.0f) 
	                .waitDurationInOpenState(5) 
	            .end()
	            
	            .log("🚀 [CIRCUITO CERRADO] Pegando al backend para la operación: ${header.operacion}...")
	            .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false") 
	        
	        .onFallback()
	            .log("⚠️ Alerta: Circuit Breaker activado en sub-proceso para la operación: ${header.operacion}") 
	            .setBody(constant("{\"error\": \"Backend no disponible (Circuit Breaker Abierto)\"}"))
	        .end()
	        
	        .convertBodyTo(String.class) 
            
	        .choice()
	            .when(simple("${body} != null && ${body} != ''"))
	                .unmarshal().json(JsonLibrary.Jackson) 
	        .end();
    }
}