package com.simon.camel.gateway.routes.composition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.composition.CompositionRegistry;
import com.simon.camel.gateway.constant.Constants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenericCompositionRoutes extends RouteBuilder {

    private final CompositionRegistry compositionRegistry;

    private static final String PROP_VALIDATION_FAILED = "compositionValidationFailed";
    private static final String PROP_COMPOSITION_RESULTS = "compositionResults";
    private static final String PROP_ORIGINAL_REQUEST = "rawRequest";

    @Override
    public void configure() throws Exception {

        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_COMPOSITION)
                .post("/gateway-to/{organizacion}/{composicion}")
                    .consumes("application/json")
                    .produces("application/json")
                    .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_COMPOSITION)
                    .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_COMPOSITION);

        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_COMPOSITION)
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_COMPOSITION)

                .setProperty(PROP_ORIGINAL_REQUEST, body())
                .convertBodyTo(Map.class)

                .process(this::validateAndExtractControlHeaders)

                .choice()
                    .when(exchangeProperty(PROP_VALIDATION_FAILED).isEqualTo(true))
                        .marshal().json(JsonLibrary.Jackson)
                        .unmarshal().json(JsonLibrary.Jackson)
                        .log("Composición rechazada por validación de entrada.")
                        .stop()
                    .endChoice()
                .end()

                .setProperty("audit-implementation", simple("${body[audit-implementation]}"))

                .log("Procesando COMPOSICIÓN [${header.MetodoDestino}] - Org: ${header.organizacion} - Comp: ${header.composicion} - Online: ${header.CacheOnline}")

                .setProperty("ControlHeader", simple("${body[header]}"))

                .process("compositionAuthProcessor")
                .log("Auth de composición aplicada. Authorization presente: ${header.Authorization != null}")

                .process(this::dispatchInternalOperations)

                .process(this::buildTemplateInput)
                .log("Renderizando plantilla velocity:templates/${header.organizacion}/${header.composicion}.vm con ${exchangeProperty.compositionResults.size()} respuestas internas")

                .toD("velocity:templates/${header.organizacion}/${header.composicion}.vm")
                .convertBodyTo(String.class)

                .process(this::normalizeVelocityOutput)

                .removeHeaders("*", "breadcrumbId", "organizacion", "composicion", "audit-implementation")
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("HttpCharacterEncoding", constant("UTF-8"))
                .setHeader("operacion", header("composicion"))

                .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_COMPOSITION)
                .log("Composición final enviada al cliente");
    }

    @SuppressWarnings("unchecked")
    private void validateAndExtractControlHeaders(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        String org = exchange.getIn().getHeader("organizacion", String.class);
        String comp = exchange.getIn().getHeader("composicion", String.class);

        if (body == null || !body.containsKey("header")) {
            failWith(exchange, 400, "GW-400-COMP-BODY",
                    "Body entrante sin clave 'header'.", org, comp);
            return;
        }

        if (org == null || org.isBlank() || comp == null || comp.isBlank()) {
            failWith(exchange, 400, "GW-400-COMP-PATH",
                    "Faltan variables de ruta 'organizacion' o 'composicion'.", org, comp);
            return;
        }

        if (!compositionRegistry.exists(org, comp)) {
            failWith(exchange, 404, "GW-404-COMP",
                    "La composición solicitada no está declarada en simon.composicion.*", org, comp);
            return;
        }

        Map<String, Object> headerConfig = (Map<String, Object>) body.get("header");

        Object multicall = headerConfig.get("multicall");
        if (Boolean.TRUE.equals(multicall)) {
            failWith(exchange, 400, "GW-400-COMP-MULTI",
                    "Una composición no admite 'multicall=true'. Cada composición ya es multi-operación.",
                    org, comp);
            return;
        }

        Object method = headerConfig.get("method");
        String metodoDestino = (method == null || method.toString().isBlank()) ? "GET" : method.toString();

        Object online = headerConfig.get("online");
        boolean cacheOnline;
        if (online instanceof Boolean) {
            cacheOnline = (Boolean) online;
        } else {
            cacheOnline = true;
        }

        exchange.getIn().setHeader("MetodoDestino", metodoDestino);
        exchange.getIn().setHeader("CacheOnline", cacheOnline);
        exchange.getIn().setHeader("CacheKeyField", headerConfig.get("key"));
        exchange.getIn().setHeader("DynamicPath", headerConfig.get("dynamic-path"));
        exchange.getIn().setHeader("DynamicQueryParams", headerConfig.get("query-params"));
        exchange.getIn().setHeader("Multicall", false);
    }

    @SuppressWarnings("unchecked")
    private void dispatchInternalOperations(Exchange exchange) throws Exception {
        String org = exchange.getIn().getHeader("organizacion", String.class);
        String comp = exchange.getIn().getHeader("composicion", String.class);
        List<String> operations = compositionRegistry.getOperations(org, comp);

        Map<String, Object> rawRequest = exchange.getProperty(PROP_ORIGINAL_REQUEST, Map.class);
        Map<String, Object> headerConfig = (Map<String, Object>) rawRequest.get("header");
        Map<String, Object> datosOriginales = (Map<String, Object>) rawRequest.get("datos");

        Map<String, Object> dynamicPathMap = headerConfig != null && headerConfig.get("dynamic-path") instanceof Map 
                ? (Map<String, Object>) headerConfig.get("dynamic-path") : null;
                
        Map<String, Object> queryParamsMap = headerConfig != null && headerConfig.get("query-params") instanceof Map 
                ? (Map<String, Object>) headerConfig.get("query-params") : null;

        List<Object> responses = new ArrayList<>();
        for (String opName : operations) {
            log.info("[Composición {}/{}] Ejecutando operación interna: {}", org, comp, opName);

            // Re-asegurar los headers clave en cada sub-petición
            exchange.getIn().setHeader("organizacion", org);
            exchange.getIn().setHeader("composicion", comp);
            exchange.getIn().setHeader("operacion", opName);
            exchange.getIn().setHeader("audit-implementation", exchange.getProperty("audit-implementation"));

            if (dynamicPathMap != null && dynamicPathMap.containsKey(opName)) {
                exchange.getIn().setHeader("DynamicPath", dynamicPathMap.get(opName));
            } else {
                exchange.getIn().removeHeader("DynamicPath");
            }

            if (queryParamsMap != null && queryParamsMap.containsKey(opName)) {
                exchange.getIn().setHeader("DynamicQueryParams", queryParamsMap.get(opName));
            } else {
                exchange.getIn().removeHeader("DynamicQueryParams");
            }

            Map<String, Object> virtualBody = new LinkedHashMap<>();
            virtualBody.put("datos", datosOriginales);
            virtualBody.put("header", headerConfig);
            
            exchange.getIn().setBody(virtualBody);

            exchange.getContext().createProducerTemplate()
                    .send("direct:sub-procesar-request-backend", exchange);

            Object opResult = exchange.getIn().getBody();
            responses.add(opResult);
        }

        // Restaurar los headers explícitamente para el paso de la plantilla Velocity
        exchange.getIn().setHeader("organizacion", org);
        exchange.getIn().setHeader("composicion", comp);

        exchange.setProperty(PROP_COMPOSITION_RESULTS, responses);
    }

    @SuppressWarnings("unchecked")
    private void buildTemplateInput(Exchange exchange) throws Exception {
        List<Object> responses = exchange.getProperty(PROP_COMPOSITION_RESULTS, List.class);
        Map<String, Object> rawRequest = exchange.getProperty(PROP_ORIGINAL_REQUEST, Map.class);

        Map<String, Object> templateInput = new LinkedHashMap<>();
        templateInput.put("results", responses);
        templateInput.put("request", rawRequest);
        templateInput.put("organizacion", exchange.getIn().getHeader("organizacion", String.class));
        templateInput.put("composicion", exchange.getIn().getHeader("composicion", String.class));

        exchange.getIn().setBody(templateInput);
    }

    private void normalizeVelocityOutput(Exchange exchange) throws Exception {
        Object currentBody = exchange.getIn().getBody();
        if (currentBody == null) {
            exchange.getIn().setBody("{}");
            return;
        }
        String raw = currentBody.toString().trim();
        if (raw.isEmpty()) {
            exchange.getIn().setBody("{}");
        } else {
            exchange.getIn().setBody(raw);
        }
    }

    private void failWith(Exchange exchange, int httpCode, String code, String message,
                          String org, String comp) {
        Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("status", "FAIL");
        errorResponse.put("code", code);
        errorResponse.put("message", message);
        errorResponse.put("organization", org);
        errorResponse.put("composition", comp);

        exchange.getIn().setBody(errorResponse);
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, httpCode);
        exchange.setProperty(PROP_VALIDATION_FAILED, Boolean.TRUE);
    }
}