package com.simon.camel.gateway.strategy.soap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SimitStrategy implements ISoapSecurityStrategy {

    @Override
    public String getFunctionName() {
        return "createHeaderSimit";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {
        List<Map<String, String>> params = (List<Map<String, String>>) headerConfig.get("function-parameters");

        // 4. Armar el Map que consumirá la plantilla Velocity ${body.get(...)}
        Map<String, Object> bodyMap = new HashMap<>();

        // Asignar el mapa al Body de Camel
        exchange.getIn().setBody(bodyMap);

        exchange.getIn().setHeader("Content-Type", "text/xml; charset=utf-8");
        exchange.getIn().setHeader("SOAPAction", "http://Servicios/WsEstadoCuentaAlerta");
        exchange.getIn().setHeader("Host", "www3.simit.org.co");
    }

}
