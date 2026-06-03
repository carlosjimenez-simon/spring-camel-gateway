package com.simon.camel.gateway.routes.rest;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import com.simon.camel.gateway.constant.Constants;
import com.simon.camel.gateway.strategy.aggregation.MulticallAggregationStrategy;

import java.util.LinkedHashMap;
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
            
            // Extrayendo las nuevas variables de control
            .setHeader("Multicall", simple("${body[header][multicall]}"))
            .setHeader("CacheOnline", simple("${body[header][online]}", Boolean.class))
            .setHeader("CacheKeyField", simple("${body[header][key]}", String.class))
	        
            .choice()
	            .when(header("MetodoDestino").isNull())
	                .setHeader("MetodoDestino", constant("GET"))
	        .end()
	        
	        .log("Procesando REST [${header.MetodoDestino}] - Org: ${header.organizacion} - Op: ${header.operacion} - Online: ${header.CacheOnline}") 
	        
            // Guardamos en propiedades los headers de control
            .setProperty("ControlHeader", simple("${body[header]}"))
            
            // =================================================================
            // EVALUACIÓN DE CAMINOS (Splitter para Multicall)
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
                    .end() // Cierra el SPLIT
                    .endChoice()
                
                // --- CAMINO B: CONTROL TRADICIONAL ---
                .otherwise()
                    .to("direct:sub-procesar-request-backend")
            .end() // Cierra el CHOICE principal
            
            // =================================================================
            // SALIDA Y RESPUESTA FINAL HACIA POSTMAN
            // =================================================================
            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation")
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("HttpCharacterEncoding", constant("UTF-8"))
	        
	        .convertBodyTo(String.class)
            // Una vez convertido a String, volvemos a parsearlo a Objeto de Java interno (Map/List) 
            // para que Postman lo renderice hermoso y AuditRoutes lo pueda procesar sin romper Jackson
            .unmarshal().json(org.apache.camel.model.dataformat.JsonLibrary.Jackson)

	        .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST)
	        .log("Enviando respuesta final a Postman: ${body}");


        // =================================================================
        // RUTA CORE: UNITARIA AL BACKEND (CON INTEGRACIÓN DE CACHÉ S3)
        // =================================================================
        from("direct:sub-procesar-request-backend")
            .routeId("simon-sub-route-core-backend")
            
	        .process("restHeaderProcessor")
	        .log("ID Transacción: ${header.breadcrumbId}")
	        
	        .process(exchange -> {
                String keyValue = "N-A";
                String keyField = exchange.getIn().getHeader("CacheKeyField", String.class);
                
                // Recuperamos la petición JSON original que entró al Gateway
                Object rawRequest = exchange.getProperty("rawRequest");
                
                if (rawRequest instanceof Map) {
                    Map<?, ?> rootMap = (Map<?, ?>) rawRequest;
                    Object datosObj = rootMap.get("datos");
                    
                    if (datosObj instanceof Map) {
                        // Caso A: Petición Unitaria (datos es un objeto/mapa)
                        Map<?, ?> datosMap = (Map<?, ?>) datosObj;
                        if (keyField != null && datosMap.containsKey(keyField)) {
                            keyValue = String.valueOf(datosMap.get(keyField));
                        }
                    } else {
                        // Caso B: Viene del Splitter de Multicall (el body actual es el sub-mapa directo)
                        Map<?, ?> currentBody = exchange.getIn().getBody(Map.class);
                        if (currentBody != null) {
                            if (currentBody.containsKey("datos") && currentBody.get("datos") instanceof Map) {
                                Map<?, ?> innerDatos = (Map<?, ?>) currentBody.get("datos");
                                if (keyField != null && innerDatos.containsKey(keyField)) {
                                    keyValue = String.valueOf(innerDatos.get(keyField));
                                }
                            } else if (keyField != null && currentBody.containsKey(keyField)) {
                                keyValue = String.valueOf(currentBody.get(keyField));
                            }
                        }
                    }
                }
                
                // Seteamos las propiedades fijas globales para toda la sub-ruta
                exchange.setProperty("CacheKeyValue", keyValue);
                exchange.setProperty("CacheFileName", keyValue + ".json");
            })
            
            // =================================================================
            // EVALUACIÓN DE ESTRATEGIA: ONLINE VS CACHÉ S3
            // =================================================================
            .choice()
                // --- CAMINO 1: INTENTAR LEER DE S3 EN FRÍO ---
                .when(header("CacheOnline").isEqualTo(false))
                    .log("🕵️ Modo Online Desactivado. Buscando respaldo en S3 para la llave: ${exchangeProperty.CacheKeyValue}")
                    .doTry()
                        // Armamos las cabeceras requeridas por el componente AWS S3 de Camel para descargar
	                    .setHeader("CamelAwsS3Key", simple("runt-cache/${header.organizacion}/${header.operacion}/${exchangeProperty.CacheFileName}"))
	                    .to("aws2-s3://simon-camel-gateway-cache?region=us-east-1&useDefaultCredentialsProvider=true&operation=getObject") 
	                    .convertBodyTo(String.class)
                        .unmarshal().json(JsonLibrary.Jackson)
                        .log("🎯 Registro recuperado con éxito desde S3 Cache.")
                    .doCatch(Exception.class)
                        .log("⚠️ No se encontró caché en S3 o falló la conexión. Forzando llamado Online de respaldo...")
                        // Plan de contingencia: Si no está en S3, vamos online de una para no romper la experiencia
                        .to("direct:invocar-backend-real")
                    .end()
                .endChoice()
                
                // --- CAMINO 2: TRÁFICO ONLINE (Flujo Tradicional + Actualizar S3) ---
                .otherwise()
	                .to("direct:invocar-backend-real")
	                // Una vez el backend real responde con éxito, actualizamos la foto en S3
	                .doTry()
	                    // 1. Guardamos la respuesta nativa del backend en una propiedad
	                    .setProperty("ResponseToCache", body())
	                    
	                    .setProperty("CacheFileName", simple("${exchangeProperty.CacheKeyValue}.json"))
	                    .setHeader("CamelAwsS3Key", simple("runt-cache/${header.organizacion}/${header.operacion}/${exchangeProperty.CacheFileName}"))
	                    
	                    .removeHeader("CamelAwsS3Operation")
	                    
	                    // 2. Convertimos temporalmente a String para S3
	                    .process(exchange -> {
	                        Object currentBody = exchange.getIn().getBody();
	                        try {
	                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
	                            String jsonString = mapper.writeValueAsString(currentBody);
	                            exchange.getIn().setBody(jsonString);
	                        } catch (Exception e) {
	                            String rawString = exchange.getIn().getBody(String.class);
	                            exchange.getIn().setBody(rawString);
	                        }
	                    })
	                    
	                    // 3. Subimos de forma dinámica a AWS S3
	                    .toD("aws2-s3://simon-camel-gateway-cache?region=us-east-1&useDefaultCredentialsProvider=true")
	                    
	                    // === LA JUGADA MAESTRA: Restauramos el body original para que Postman lo pinte hermoso ===
	                    .setBody(exchangeProperty("ResponseToCache"))
	                    
	                    .log("💾 Foto de caché actualizada en S3 para la llave: ${exchangeProperty.CacheKeyValue}")
	                .doCatch(Exception.class)
	                    // Respaldo: Si algo falla con S3, también restauramos el body para no romper la respuesta del cliente
	                    .setBody(exchangeProperty("ResponseToCache"))
	                    .log("❌ No se pudo guardar la actualización de caché en S3: ${exception.message}")
	                .end() // Cierra doTry
	            .end(); // Cierra CHOICE Online/Caché

        
        // =================================================================
        // SUB-RUTA: INVOCACIÓN REAL AL PROVEEDOR BLINDADA POR CB + TIMEOUT
        // =================================================================
        from("direct:invocar-backend-real")
            .routeId("simon-sub-route-invocar-backend-real")
            .marshal().json(JsonLibrary.Jackson)
            
            .removeHeaders("*", "Authorization", "X-API-Version", "MetodoDestino", "organizacion", "operacion", "audit-implementation", "breadcrumbId", "CacheOnline", "CacheKeyField")
            
            .setHeader("Content-Type", constant("application/json"))
            .setHeader("Accept", constant("application/json"))
            .setHeader("CamelHttpMethod", header("MetodoDestino"))
            
            .circuitBreaker()
                .id("cb-${header.organizacion}-${header.operacion}")
                .resilience4jConfiguration()
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(30)
                    .timeoutEnabled(true)       
                    .timeoutDuration(15000) // Cambiado a tus 15 segundos prudenciales
                    .bulkheadEnabled(true)
                    .bulkheadMaxConcurrentCalls(30)
                .end()
                
                .log("🚀 [CIRCUITO CERRADO] Pegando al backend para la operación: ${header.operacion}...")
                .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false")
            
            .onFallback()
                .log("⚠️ Alerta: Circuit Breaker activado en sub-proceso para la operación: ${header.operacion}")
                
                .process(exchange -> {
                        Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                        String org = exchange.getIn().getHeader("organizacion", String.class);
                        String op = exchange.getIn().getHeader("operacion", String.class);
                        
                        Map<String, Object> errorResponse = new LinkedHashMap<>();
                        errorResponse.put("status", "FAIL");
                        errorResponse.put("code", "GW-503-CB");
                        errorResponse.put("message", "El servicio no se encuentra disponible temporalmente por protección del Gateway.");
                        errorResponse.put("organization", org);
                        errorResponse.put("operation", op);
                        
                        if (exception != null) {
                            errorResponse.put("technical_reason", exception.getMessage());
                            errorResponse.put("exception_type", exception.getClass().getSimpleName());
                        } else {
                            errorResponse.put("technical_reason", "CallNotPermittedException (Circuito abierto rechazando peticiones)");
                            errorResponse.put("exception_type", "CircuitBreakerOpenException");
                        }
                        
                        exchange.getIn().setBody(errorResponse);
                        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
                    })
                    .marshal().json(JsonLibrary.Jackson)
                    .unmarshal().json(JsonLibrary.Jackson)
                    .stop()
            .end(); // Cierra el bloque onFallback
    }
}