package com.simon.camel.gateway.routes.soap;

import com.simon.camel.gateway.model.ClienteRequest; // Asegúrate de haber copiado el modelo aquí
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;


@Component
public class SoapEgressRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // No necesitas restConfiguration aquí porque ya está en la otra clase
        
        // 1. Definimos el nuevo endpoint de salida
        rest("/api")
            .post("/soap-proxy")
                .type(ClienteRequest.class) 
                .routeId("soap-proxy-route")
                .to("direct:call-mock-8081");

        from("direct:call-mock-8081")
	        .routeId("egress-logic")
	        // Quitamos el unmarshal porque el body ya es un objeto ClienteRequest
	        .log("El objeto ya está listo: ${body}")
	        
	        // 1. Convertimos el Objeto Java directamente a XML
	        .marshal().jacksonXml(true)
	        
	        // 2. Limpieza y envío
	        .removeHeaders("CamelHttp*")
	        .setHeader("Content-Type", constant("application/xml"))
	        .setHeader("Accept", constant("application/xml"))
	        
	        .log("Enviando este XML al Mock: ${body}")
	        
	        .to("http://localhost:8081/ws/clientes?bridgeEndpoint=true")
	        .log("Respuesta recibida del Mock: ${body}");
    }
}