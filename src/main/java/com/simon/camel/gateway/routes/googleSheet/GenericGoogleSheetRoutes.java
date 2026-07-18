package com.simon.camel.gateway.routes.googleSheet;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.constant.Constants;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GenericGoogleSheetRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // ============================================================
        // ENTRADA REST
        // ============================================================
        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_GOOGLE_SHEET)
            .post("/gateway-to/{organizacion}/{operacion}")
                .consumes("application/json")
                .produces("application/json")
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_GOOGLE_SHEET)
                .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_GOOGLE_SHEET);

        // ============================================================
        // RECEPCION + DELEGACION AL SUB-FLUJO CON CIRCUIT BREAKER
        // ============================================================
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_GOOGLE_SHEET)
            .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GOOGLE_SHEET)

            .setProperty("rawRequest", body())
            .setHeader("audit-implementation", simple("${body[audit-implementation]}"))
            .setHeader("excel-sheet", simple("${body[header][excel-sheet]}", String.class))
            .setProperty("ControlHeader", simple("${body[header]}"))

            .log("Procesando Google Sheet - Org: ${header.organizacion} - Op: ${header.operacion}")

            .to("direct:sub-lookup-google-sheet");

        // ============================================================
        // SUB-FLUJO: ejecuta GoogleSheetAuthStrategy dentro de Circuit Breaker
        // ============================================================
        from("direct:sub-lookup-google-sheet")
            .routeId("simon-sub-route-google-sheet-lookup")

            .circuitBreaker()
                .id("cb-gsheet-${header.organizacion}-${header.operacion}")
                .resilience4jConfiguration()
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(30)
                    .timeoutEnabled(true)
                    .timeoutDuration(8000)
                    .bulkheadEnabled(true)
                    .bulkheadMaxConcurrentCalls(30)
                .end()

                .log("Google Sheet lookup disparado para op=${header.operacion}")
                .process("restHeaderProcessor")

            .onFallback()
                .log(LoggingLevel.WARN, "Circuit Breaker activado en Google Sheet para: ${header.operacion}. Causa: ${exception.message}")
                .process(exchange -> {
                    Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                    String org = exchange.getIn().getHeader("organizacion", String.class);
                    String op = exchange.getIn().getHeader("operacion", String.class);

                    Map<String, Object> errorResponse = new LinkedHashMap<>();
                    errorResponse.put("status", "FAIL");
                    errorResponse.put("code", "GW-503-CB-GSHEET");
                    errorResponse.put("message", "El servicio de Google Sheet no se encuentra disponible temporalmente por proteccion del Gateway.");
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
            .end() // cierra onFallback

            // ============================================================
            // RESPUESTA: re-marshal estetico + wireTap a auditoria
            // ============================================================
            .marshal().json(JsonLibrary.Jackson)
            .unmarshal().json(JsonLibrary.Jackson)
            .log("Respuesta Google Sheet generada: ${body}")

            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation")
            .setHeader("Content-Type", constant("application/json"))

            .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_GOOGLE_SHEET);
    }
}
