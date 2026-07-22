package com.simon.camel.gateway.routes.rest;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.simon.camel.gateway.constant.Constants;
import com.simon.camel.gateway.strategy.aggregation.MulticallAggregationStrategy;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GenericRestRoutes extends RouteBuilder {

	// Inyección de propiedades de Caché S3 desde application.yml
    @Value("${cache.s3.bucket}")
    private String cacheS3Bucket;

    @Value("${cache.s3.region}")
    private String cacheS3Region;

    @Value("${cache.s3.use-default-credentials}")
    private String cacheS3UseDefaultCredentials;
    
    
    @Override
    public void configure() throws Exception {
        
    	// Construcción de los endpoints estáticos para S3 con las propiedades inyectadas
    	String cacheS3GetObjectEndpoint = String.format(
		    "aws2-s3://%s?region=%s&useDefaultCredentialsProvider=%s&operation=getObject&autoCreateBucket=false",
		    cacheS3Bucket, cacheS3Region, cacheS3UseDefaultCredentials
		);
        
        log.info(cacheS3GetObjectEndpoint);

        String cacheS3PutObjectEndpoint = String.format(
    	    "aws2-s3://%s?region=%s&useDefaultCredentialsProvider=%s&autoCreateBucket=false",
    	    cacheS3Bucket, cacheS3Region, cacheS3UseDefaultCredentials
    	);
        
        log.info(cacheS3PutObjectEndpoint);
        
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
            .setHeader("DynamicPath", simple("${body[header][dynamic-path]}"))
            .setHeader("DynamicQueryParams", simple("${body[header][query-params]}"))
	        
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
	        
	        .process(exchange -> {
                Object currentBody = exchange.getIn().getBody();
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String cleanJsonString = null;
                    
                    // === CASO ESPECIAL MULTICALL: Si es una lista o arreglo de datos de Java ===
                    if (currentBody instanceof java.util.List) {
                        java.util.List<?> bodyList = (java.util.List<?>) currentBody;
                        java.util.List<Object> processedList = new java.util.ArrayList<>();
                        
                        // Iteramos de forma segura cada elemento del arreglo consolidado por el Aggregator
                        for (Object item : bodyList) {
                            if (item instanceof org.apache.camel.converter.stream.InputStreamCache || item instanceof java.io.InputStream) {
                                // Convertimos el stream binario individual a String JSON válido usando Camel
                                String resolvedStr = exchange.getContext().getTypeConverter().convertTo(String.class, exchange, item);
                                // Lo parseamos a mapa de Java para que no conserve comillas escapadas internas
                                processedList.add(mapper.readValue(resolvedStr, Object.class));
                            } else if (item instanceof String) {
                                processedList.add(mapper.readValue((String) item, Object.class));
                            } else {
                                // Si ya es un mapa nativo (Caso Offline de S3) lo añadimos directo
                                processedList.add(item);
                            }
                        }
                        // Serializamos la lista limpia de flujos binarios a String JSON real
                        cleanJsonString = mapper.writeValueAsString(processedList);
                        
                    } else if (currentBody instanceof org.apache.camel.converter.stream.InputStreamCache || currentBody instanceof java.io.InputStream) {
                        // === CASO UNITARIO ONLINE: Si es un stream binario de red único ===
                        cleanJsonString = exchange.getContext().getTypeConverter().convertTo(String.class, exchange, currentBody);
                        
                    } else if (currentBody instanceof String) {
                        // Si ya es una cadena JSON directa
                        cleanJsonString = (String) currentBody;
                        
                    } else if (currentBody != null) {
                        // === CASO UNITARIO OFFLINE: Si es un mapa único de Java recuperado de S3 ===
                        cleanJsonString = mapper.writeValueAsString(currentBody);
                    }
                    
                    // Re-mapeamos la estructura limpia de texto JSON a objetos POJO indexables (Map/List)
                    // Con esto, Spring Boot se encarga de pintarlo impecable con ':' y formateado en Postman
                    if (cleanJsonString != null) {
                        Object parsedObject = mapper.readValue(cleanJsonString, Object.class);
                        exchange.getIn().setBody(parsedObject);
                    }
                } catch (Exception e) {
                    log.error("Error en formateo estético de salida troncal: " + e.getMessage());
                }
            })
            // Una vez convertido a String, volvemos a parsearlo a Objeto de Java interno (Map/List) 
            // para que Postman lo renderice hermoso y AuditRoutes lo pueda procesar sin romper Jackson
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
	                    //.to("aws2-s3://{{cache.s3.bucket}}?region={{cache.s3.region}}&useDefaultCredentialsProvider={{cache.s3.use-default-credentials}}&operation=getObject")
	                    .to(cacheS3GetObjectEndpoint)
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
	                    //.toD("aws2-s3://{{cache.s3.bucket}}?region={{cache.s3.region}&useDefaultCredentialsProvider={{cache.s3.use-default-credentials}}")
	                    .to(cacheS3PutObjectEndpoint)
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
            
            // === 1. MAPEO DINÁMICO DE QUERY PARAMS (Soporta GET y POST) ===
            .process(exchange -> {
                String metodo = exchange.getIn().getHeader("MetodoDestino", String.class); //
                String explicitQueryParams = exchange.getIn().getHeader("DynamicQueryParams", String.class); //
                Object currentBody = exchange.getIn().getBody(); //
                
                // 🛠️ JUGA MAESTRA: Leer la URL del yml para ver si trae parámetros base (ej: ?companyId=...)
                String org = exchange.getIn().getHeader("organizacion", String.class);
                String op = exchange.getIn().getHeader("operacion", String.class);
                String rawBaseEndpoint = exchange.getContext().resolvePropertyPlaceholders("{{simon.endpoint." + org + "." + op + "}}");
                
                StringBuilder queryFinal = new StringBuilder();
                
                // Si el yml ya tiene parámetros, los guardamos como base de la query
                if (rawBaseEndpoint != null && rawBaseEndpoint.contains("?")) {
                    String[] parts = rawBaseEndpoint.split("\\?", 2);
                    queryFinal.append(parts[1]); // Guarda el "companyId=693042b8c120e2c6e2d928d5"
                }
                
                // Opción A: Si el usuario mandó query-params explícitos en el JSON de entrada
                if (explicitQueryParams != null && !explicitQueryParams.trim().isEmpty() && !"null".equalsIgnoreCase(explicitQueryParams)) {
                    if (queryFinal.length() > 0) queryFinal.append("&");
                    queryFinal.append(explicitQueryParams.trim());
                } 
                // Opción B: Mapeo automático de datos en métodos GET (si vienen dentro del objeto datos)
                else if ("GET".equalsIgnoreCase(metodo) && currentBody instanceof Map) { //
                    Map<?, ?> bodyMap = (Map<?, ?>) currentBody; //
                    Object datosObj = bodyMap.get("datos"); //
                    
                    if (datosObj instanceof Map) { //
                        Map<?, ?> datosMap = (Map<?, ?>) datosObj; //
                        
                        for (Map.Entry<?, ?> entry : datosMap.entrySet()) {
                            if (queryFinal.length() > 0) queryFinal.append("&");
                            queryFinal.append(entry.getKey()).append("=").append(entry.getValue());
                        }
                    }
                }
                
                // Guardamos el resultado definitivo en el header oficial de Camel
                if (queryFinal.length() > 0) {
                    exchange.getIn().setHeader(Exchange.HTTP_QUERY, queryFinal.toString());
                }
            })
            
            // === 2. FILTRO CRÍTICO: EXTRACCIÓN DE PAYLOAD LIMPIO (MAPA NATIVO) ===
            .process(exchange -> {
                Object currentBody = exchange.getIn().getBody();
                
                // Caso 1: Viene del flujo tradicional (Map completo con 'header' y 'datos')
                if (currentBody instanceof Map && ((Map<?, ?>) currentBody).containsKey("datos")) {
                    Map<?, ?> bodyMap = (Map<?, ?>) currentBody;
                    exchange.getIn().setBody(bodyMap.get("datos"));
                } 
                // Caso 2: Viene del Splitter de Multicall
                else if (currentBody instanceof Map && ((Map<?, ?>) currentBody).get("datos") instanceof Map) {
                    Map<?, ?> bodyMap = (Map<?, ?>) currentBody;
                    exchange.getIn().setBody(bodyMap.get("datos"));
                }
            })
            
            // === 3. CONSTRUCCIÓN DE LA URL DESTINO (Path Variables Dinámicos) ===
            .process(exchange -> {
                String dynamicPath = exchange.getIn().getHeader("DynamicPath", String.class);
                
                // Si el usuario envió un path variable (ej: 000000001/transactions), le ponemos el slash inicial
                if (dynamicPath != null && !dynamicPath.trim().isEmpty() && !"null".equalsIgnoreCase(dynamicPath)) {
                    exchange.setProperty("CalculatedDynamicPath", "/" + dynamicPath.trim());
                } else {
                    exchange.setProperty("CalculatedDynamicPath", "");
                }
            })
            
            // === 4. SERIALIZACIÓN NATIVA A BYTE STREAM ===
            // Convierte el mapa extraído en un JSON binario real que el componente HTTP de Camel necesita.
            .marshal().json(JsonLibrary.Jackson)
            
            .log("📤 JSON enviado al Backend para [${header.organizacion}/${header.operacion}]: ${body}")
            
            // === 5. LIMPIEZA DE HEADERS PRESERVANDO EL CAMELHTTPQUERY ===
            .removeHeaders("*", "Authorization", "X-API-Version", "MetodoDestino", "organizacion", "operacion", "audit-implementation", "breadcrumbId", "CacheOnline", "CacheKeyField", "Fineract-Platform-TenantId", "CamelHttpQuery")
            
            .setHeader("Content-Type", constant("application/json"))
            .setHeader("Accept", constant("application/json"))
            .setHeader("CamelHttpMethod", header("MetodoDestino"))
            
            .circuitBreaker()
                .id("cb-${header.organizacion}-${header.operacion}")
                .resilience4jConfiguration()
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(30)
                    .timeoutEnabled(true)       
                    .timeoutDuration(15000) 
                    .bulkheadEnabled(true)
                    .bulkheadMaxConcurrentCalls(30)
                .end()
                
                .log("🚀 [CIRCUITO CERRADO] Pegando al backend para la operación: ${header.operacion}...")
                
                //imprimimos la url calculada
                .process(exchange -> {
                    String baseEndpoint = exchange.getContext().resolvePropertyPlaceholders("{{simon.endpoint." + exchange.getIn().getHeader("organizacion") + "." + exchange.getIn().getHeader("operacion") + "}}");
                    String dynamicPath = exchange.getProperty("CalculatedDynamicPath", String.class);
                    String queryParams = exchange.getIn().getHeader(org.apache.camel.Exchange.HTTP_QUERY, String.class);
                    
                    // 🛠️ Si la URL base ya trae un '?', lo podamos para que el log quede perfecto
                    if (baseEndpoint != null && baseEndpoint.contains("?")) {
                        baseEndpoint = baseEndpoint.split("\\?")[0];
                    }
                    
                    String urlCompleta = baseEndpoint + (dynamicPath != null ? dynamicPath : "");
                    if (queryParams != null && !queryParams.isEmpty()) {
                        urlCompleta += "?" + queryParams;
                    }
                    
                    exchange.setProperty("SimonUrlLog", urlCompleta);
                })
                
                .log("🔗 URL Destino calculada: [${header.MetodoDestino}] -> ${exchangeProperty.SimonUrlLog}")
                
                // === 6. INVOCACIÓN USANDO LA PROPIEDAD MUTADA DINÁMICAMENTE ===
                .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}${exchangeProperty.CalculatedDynamicPath}?bridgeEndpoint=true&throwExceptionOnFailure=false&sslContextParameters=#sslInseguroFineract&x509HostnameVerifier=#allowAllHostnameVerifier")
                //.toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}.split('\\\\?')[0]}${exchangeProperty.CalculatedDynamicPath}?bridgeEndpoint=true&throwExceptionOnFailure=false&sslContextParameters=#sslInseguroFineract&x509HostnameVerifier=#allowAllHostnameVerifier")
                
                .convertBodyTo(String.class)
            .onFallback()
                .log("⚠️ Alerta: Circuit Breaker activado en sub-proceso para la operación: ${header.operacion}")
                .log(org.apache.camel.LoggingLevel.WARN, "⚠️ Alerta: Circuit Breaker ejecutando Fallback para: ${header.operacion}. Causa en Exchange: ${exception.message}")
                
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