package com.simon.camel.gateway.routes.audit;


import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class AuditRoutes extends RouteBuilder {

	@Override
    public void configure() throws Exception {
		
		// 1. EL RESOLVER (Lo que antes estaba en los Routes)
        from("direct:audit-logic")
            .routeId("audit-router-resolver")
            .choice()
                .when(header("audit-implementation").isNotNull())
                    .log("Ejecutando estrategia de auditoría: ${header.audit-implementation}")
                    .routingSlip(header("audit-implementation"))
                    .endChoice()
                .otherwise()
                    .log("WARN: No se especificó 'audit-implementation' en el JSON.")
            .end();

        // --- IMPLEMENTACIÓN SOAP ---
        from("direct:audit-soap-s3")
            .routeId("audit-soap-strategy")
            .process(exchange -> {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("type", "SOAP_TRANSACTION");
                log.put("id", exchange.getIn().getHeader("breadcrumbId"));
                log.put("request_json", exchange.getProperty("rawRequest"));
                log.put("xml_sent", exchange.getProperty("xmlSent"));
                log.put("xml_received", exchange.getProperty("xmlReceived"));
                log.put("response_final", exchange.getIn().getBody());
                exchange.getIn().setBody(log);
            })
            .to("direct:common-s3-upload");

        // --- IMPLEMENTACIÓN REST ---
        from("direct:audit-rest-s3")
            .routeId("audit-rest-strategy")
            .process(exchange -> {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("type", "REST_TRANSACTION");
                log.put("id", exchange.getIn().getHeader("breadcrumbId"));
                log.put("request_data", exchange.getProperty("rawRequest"));
                log.put("response_final", exchange.getIn().getBody());
                exchange.getIn().setBody(log);
            })
            .to("direct:common-s3-upload");

        // --- MOTOR DE CARGA COMÚN ---
        from("direct:common-s3-upload")
            .routeId("s3-upload-engine")
            .marshal().json()
            .setHeader("year", simple("${date:now:yyyy}"))
            .setHeader("month", simple("${date:now:MM}"))
            .setHeader("day", simple("${date:now:dd}"))
            .setHeader("CamelAwsS3Key", simple("year=${header.year}/month=${header.month}/day=${header.day}/org=${header.organizacion}/op=${header.operacion}/${header.breadcrumbId}.json"))
            .toD("aws2-s3://simon-camel-gateway-logs?region=us-east-1&useDefaultCredentialsProvider=true")
            .log("Auditoría [${header.audit-implementation}] guardada en S3");
    }
}
