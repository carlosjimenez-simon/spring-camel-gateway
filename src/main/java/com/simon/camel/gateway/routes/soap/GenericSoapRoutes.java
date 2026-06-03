package com.simon.camel.gateway.routes.soap;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.constant.Constants;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class GenericSoapRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

    	// 1. Definición del Punto de Entrada REST
        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_SOAP)
        	.post("/gateway-to/{organizacion}/{operacion}")
	        	.consumes("application/json")
	            .produces("application/json")
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_SOAP)
                .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_SOAP_WITH_TEMPLATE);

        // 2. Lógica Maestra
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_SOAP_WITH_TEMPLATE)
            .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_SOAP)
            
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
            .process("soapHeaderProcessor")
            
            .log("ID Transacción: ${header.breadcrumbId}")
            
            .log("Headers procesados: ${headers}")
            .log("Datos para plantilla: ${body}")
            
            // 5. Cargamos la plantilla dinámicamente
            .toD("velocity:templates/${header.organizacion}/${header.operacion}.vm")
            
            // C. CAPTURA XML ENVIADO: La plantilla ya renderizada
            .setProperty("xmlSent", body())
            
            // 6. Limpieza estándar de Camel
            .removeHeaders("CamelHttp*")
            
            // CONFIGURACIÓN PARA SOAP 1.2
            .setHeader("Content-Type", simple("application/soap+xml; charset=utf-8; action=\"${exchangeProperty.soapNamespace}${header.TechnicalAction}\""))
            .setHeader("SOAPAction", simple("${exchangeProperty.soapNamespace}${header.TechnicalAction}"))
	        
            .log("XML generado para ${header.organizacion}: ${body}")
            
            // =================================================================
            // NUEVO: INTEGRACIÓN DEL CIRCUIT BREAKER PARA SOAP
            // =================================================================
            .circuitBreaker()
                .id("cb-soap-${header.organizacion}-${header.operacion}")
                .resilience4jConfiguration()
                    .failureRateThreshold(50.0f) // Mismo umbral del 50%
                    .waitDurationInOpenState(15) // Espera de 15 segundos en Open State
                .end() // Cierra resiliencia4j
                
                // Agregamos throwExceptionOnFailure=false para que las respuestas HTTP de error pasen al fallback de Camel
                .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true&throwExceptionOnFailure=false")
            
                .onFallback()
	                .log("⚠️ Alerta: Circuit Breaker activado en proceso SOAP para la operación: ${header.operacion}")
	                
	                .process(exchange -> {
	                    Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
	                    String org = exchange.getIn().getHeader("organizacion", String.class);
	                    String op = exchange.getIn().getHeader("operacion", String.class);
	                    
	                    Map<String, Object> errorResponse = new LinkedHashMap<>();
	                    errorResponse.put("status", "FAIL");
	                    errorResponse.put("code", "GW-503-SOAP-CB");
	                    errorResponse.put("message", "El servicio SOAP externo no se encuentra disponible temporalmente por protección del Gateway.");
	                    errorResponse.put("organization", org);
	                    errorResponse.put("operation", op);
	                    
	                    if (exception != null) {
	                        errorResponse.put("technical_reason", exception.getMessage());
	                        errorResponse.put("exception_type", exception.getClass().getSimpleName());
	                    } else {
	                        errorResponse.put("technical_reason", "CallNotPermittedException (Circuito abierto rebotando peticiones)");
	                    }
	                    
	                    exchange.getIn().setBody(errorResponse);
	                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
	                })
	                .marshal().json(JsonLibrary.Jackson)
	                
	                // === MODIFICADO: Cambiamos el convertBodyTo por un Unmarshal JSON ===
	                // Esto transforma el String JSON de bytes a un objeto Map vivo que el componente rest entiende nativo
	                .unmarshal().json(JsonLibrary.Jackson)
	                
	                .stop()
	            .end() // Cierra el bloque onFallback
            
            // =================================================================
            // CONTINUACIÓN DEL FLUJO FELIZ (Solo corre si el .toD andó melo)
            // =================================================================
             // D. CAPTURA XML RECIBIDO: Respuesta cruda del proveedor
            .setProperty("xmlReceived", body().convertToString())
            
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
            .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_SOAP)
            
            .log("Enviando a Postman: ${body}");
    }
}