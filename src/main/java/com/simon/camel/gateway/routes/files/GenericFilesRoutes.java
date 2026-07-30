package com.simon.camel.gateway.routes.files;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.springframework.stereotype.Component;

import com.simon.camel.gateway.constant.Constants;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class GenericFilesRoutes extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // 1. Configuración del Formato CSV (usando cabeceras de la primera línea)
    	CsvDataFormat csvFormat = new CsvDataFormat();
    	csvFormat.setUseMaps(true);           // Para que retorne List<Map<String, Object>>
    	csvFormat.setCaptureHeaderRecord(true);     // Lee los encabezados de la primera fila
    	csvFormat.setIgnoreSurroundingSpaces(true);

        // 2. Definición del REST DSL
        rest(Constants.SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_FILES)
            .post("/gateway-to/{organizacion}/{operacion}")
                .consumes("application/json")
                .produces("application/json")
                .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_FILES)
                .to(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_FILES);

        // 3. Flujo Core de Procesamiento
        from(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_FILES)
            .routeId(Constants.SIMON_SPRING_CAMEL_ROUTE_ID_FILES)

            // A. Guardar solicitud original y loguear
            .setProperty("rawRequest", body())
            .convertBodyTo(Map.class)

            .setHeader("audit-implementation", simple("${body[audit-implementation]}"))
            .log("Procesando ARCHIVO PLANO - Org: ${header.organizacion} - Op: ${header.operacion}")

            // B. Procesar Estrategia (Ej: dummy logger o validaciones)
            .process("filesHeaderProcessor")

            // C. Cargar DINÁMICAMENTE el archivo plano desde el JSON recibido
            .process(exchange -> {
                String org = exchange.getIn().getHeader("organizacion", String.class);
                String op = exchange.getIn().getHeader("operacion", String.class);
                
                // Recuperamos el mapa entrante (rawRequest o body)
                Map<?, ?> rawRequest = exchange.getProperty("rawRequest", Map.class);
                Map<?, ?> datos = (rawRequest != null && rawRequest.containsKey("datos")) 
                        ? (Map<?, ?>) rawRequest.get("datos") : null;
                Map<?, ?> headerConfig = (rawRequest != null && rawRequest.containsKey("header")) 
                        ? (Map<?, ?>) rawRequest.get("header") : null;

                String fileName = null;

                // 1. Intentamos obtener 'file-name' o 'file_path' desde 'datos'
                if (datos != null) {
                    if (datos.get("file-name") != null) {
                        fileName = datos.get("file-name").toString();
                    } else if (datos.get("file_path") != null) {
                        fileName = datos.get("file_path").toString();
                    }
                }

                // 2. Si no viene en 'datos', buscamos en 'header'
                if (fileName == null && headerConfig != null) {
                    if (headerConfig.get("file-name") != null) {
                        fileName = headerConfig.get("file-name").toString();
                    }
                }

                // 3. Fallback: Si no mandan nada en el JSON, usamos el nombre de la operación
                if (fileName == null || fileName.trim().isEmpty()) {
                    fileName = op + ".csv";
                }

                // Aseguramos la extensión .csv si no la enviaron
                if (!fileName.endsWith(".csv") && !fileName.endsWith(".txt")) {
                    fileName += ".csv";
                }

                // Construcción de la ruta dinámica en src/main/resources/data/{organizacion}/{fileName}
                String resourcePath = String.format("data/%s/%s", org, fileName);
                
                log.info("Cargando archivo plano dinámico desde classpath: {}", resourcePath);

                InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
                
                if (is == null) {
                    throw new IllegalArgumentException("No se encontró el archivo plano especificado en la ruta: " + resourcePath);
                }

                exchange.getIn().setBody(is);
            })

            // D. Unmarshal del CSV a List<Map<String, String>>
            .unmarshal(csvFormat)

            // E. Limpieza de Headers y preparación de respuesta REST
            .removeHeaders("*", "breadcrumbId", "organizacion", "operacion", "audit-implementation")
            .setHeader("Content-Type", constant("application/json"))
            .setHeader("HttpCharacterEncoding", constant("UTF-8"))

            // F. Auditoría asíncrona mediante wireTap
            .wireTap(Constants.SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_FILES)
            .log("Respuesta de archivo plano enviada exitosamente a Postman");
    }
}