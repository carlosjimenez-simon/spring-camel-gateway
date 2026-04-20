package com.simon.camel.gateway.routes.rest;


import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class GenericRestRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Configuración del consumidor REST
        rest("/api/v1/rest")
            .post("/gateway-to/{organizacion}/{operacion}")
                .consumes("application/json")
                .produces("application/json")
                .routeId("generic-rest-gateway")
                .to("direct:procesar-rest");

        from("direct:procesar-rest")
	        .routeId("logic-rest-core")
	        .convertBodyTo(Map.class)
	        
	        // 1. Extraemos el método dinámico del JSON (Header interno de nuestra App)
	        .setHeader("MetodoDestino", simple("${body[header][method]}", String.class))
	        .choice()
	            .when(header("MetodoDestino").isNull())
	                .setHeader("MetodoDestino", constant("GET")) // Por defecto GET si no envían nada
	        .end()
	        
	        .log("Procesando REST [${header.MetodoDestino}] - Org: ${header.organizacion} - Op: ${header.operacion}")
	        
	        // 2. Procesador de seguridad (AWS Secrets, Auth, etc.)
	        .process("restHeaderProcessor") 
	        
	        .log("Headers tras estrategia: ${headers[Authorization]} - Tenant: ${headers[Fineract-Platform-TenantId]}")
	        
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
	        .removeHeaders("*", "breadcrumbId", "organizacion", "operacion")
	        .setHeader("Content-Type", constant("application/json"))
	        .setHeader("HttpCharacterEncoding", constant("UTF-8"))
	        
	        .log("Enviando a Postman: ${body}");
    }
}