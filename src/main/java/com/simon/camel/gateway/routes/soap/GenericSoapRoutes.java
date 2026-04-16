package com.simon.camel.gateway.routes.soap;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class GenericSoapRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

    	// 1. La URL
        rest("/api/v1")
        	.post("/gateway-to/{organizacion}/{operacion}")
                .routeId("generic-soap-gateway")
                .to("direct:procesar-plantilla");

        // 2. Lógica Maestra
     // 2. Lógica Maestra
        from("direct:procesar-plantilla")
            .routeId("logic-velocity")
            
            // Aseguramos que el body sea un Mapa para Velocity
            .convertBodyTo(Map.class)
            
            .log("Procesando Org: ${header.organizacion} - Op: ${header.operacion}")
            
            // 2. Cargamos la plantilla dinámicamente usando AMBOS headers
            // La ruta final será: templates/test/clientes.vm o templates/seguros_mundial/expedir.vm
            .toD("velocity:templates/${header.organizacion}/${header.operacion}.vm")
            
            // 3. Headers y envío
            .removeHeaders("CamelHttp*")
            .setHeader("Content-Type", constant("application/xml"))
            
            // TIP: Aquí podrías usar un switch o un bean para cambiar la URL de destino 
            // según la organización si no quieres enviarlo todo al mismo sitio.
            .log("XML generado para ${header.organizacion}: ${body}")
            
            .toD("${properties:simon.endpoint.${header.organizacion}.${header.operacion}}?bridgeEndpoint=true")
            .log("Respuesta recibida: ${body}");
    }
}