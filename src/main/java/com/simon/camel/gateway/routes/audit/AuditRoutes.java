package com.simon.camel.gateway.routes.audit;


import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.constant.Constants;

@Component
public class AuditRoutes extends RouteBuilder {

	@Override
    public void configure() throws Exception {
		
        // --- IMPLEMENTACIÓN SOAP ---
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_SOAP)
            .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_AUDIT_SOAP)
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
            .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_UPLOAD_S3);

        // --- IMPLEMENTACIÓN REST ---
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST)
            .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_AUDIT_REST)
            .process(exchange -> {
                Map<String, Object> log = new LinkedHashMap<>();
                log.put("type", "REST_TRANSACTION");
                log.put("id", exchange.getIn().getHeader("breadcrumbId"));
                log.put("request_data", exchange.getProperty("rawRequest"));
                log.put("response_final", exchange.getIn().getBody());
                exchange.getIn().setBody(log);
            })
            .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_UPLOAD_S3);

        // --- MOTOR DE CARGA COMÚN ---
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_UPLOAD_S3)
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
