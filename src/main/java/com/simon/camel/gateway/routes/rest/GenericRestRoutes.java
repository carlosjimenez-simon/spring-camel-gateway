package com.simon.camel.gateway.routes.rest;


import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.constant.Constants;

import java.util.Map;

@Component
public class GenericRestRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Configuración del consumidor REST
        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_REST)
            .post("/gateway-to/{organizacion}/{operacion}")
                .consumes("application/json")
                .produces("application/json")
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_REST)
                .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_REST);

        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_REST)
	        .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_REST)
	        
	        // A. CAPTURA INICIAL: Guardamos el Request original antes de cualquier cambio
            .setProperty("rawRequest", body())
	        .convertBodyTo(Map.class)
	        
	        // B. DINAMISMO: Extraemos qué auditoría usar desde el JSON de entrada
            .setHeader("audit-implementation", simple("${body[audit-implementation]}"))
	        
	        // 1. Extraemos el método dinámico del JSON (Header interno de nuestra App)
	        .setHeader("MetodoDestino", simple("${body[header][method]}", String.class))
	        .choice()
	            .when(header("MetodoDestino").isNull())
	                .setHeader("MetodoDestino", constant("GET")) // Por defecto GET si no envían nada
	        .end()
	        
	        .log("Procesando REST [${header.MetodoDestino}] - Org: ${header.organizacion} - Op: ${header.operacion}")
	        
	        // 2. Procesador de seguridad (AWS Secrets, Auth, etc.)
	        .process("restHeaderProcessor") 
	        
	        .log("ID Transacción: ${header.breadcrumbId}")
	        .log("Headers: Auth=${headers[Authorization]} - Tenant=${headers[Fineract-Platform-TenantId]}")
	        
	        .marshal().json(JsonLibrary.Jackson)
	        
	        // 3. LIMPIEZA TOTAL: Eliminamos headers de Camel y quemados como el Origin
	        .removeHeaders("CamelHttp*")
	        .removeHeaders("Host")
	        
	        // Headers estándar de un API JSON
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("Accept", constant("application/json"))
	        
	        // 4. Aplicamos el verbo dinámico que extrajimos al inicio
	        .setHeader("CamelHttpMethod", header("MetodoDestino"))
	        
	        // 5. Invocación dinámica con el bypass de SSL para entornos internos/inseguros
	        .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false")
	        
	        // 6. Manejo de respuesta
	        .convertBodyTo(String.class) 
	        .log("Respuesta REST recibida: ${body}")
	
	        // Validamos que el body no esté vacío antes de intentar el JSON (Pasa mucho en OPTIONS o DELETE)
	        .choice()
	            .when(simple("${body} != null && ${body} != ''"))
	                .unmarshal().json(JsonLibrary.Jackson)
	        .end()
	
	        // 7. Limpieza para el cliente final (Postman/App)
	        .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation")
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("HttpCharacterEncoding", constant("UTF-8"))
	        
	        .log("Respuesta REST recibida: ${body}")
	        
	        // C. AUDITORÍA DINÁMICA: Enviamos a la ruta puente
            .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST)
	        .log("Enviando a Postman: ${body}");

    }
}