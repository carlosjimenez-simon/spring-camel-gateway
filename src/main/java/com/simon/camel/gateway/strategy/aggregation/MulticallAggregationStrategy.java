package com.simon.camel.gateway.strategy.aggregation;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import java.util.ArrayList;
import java.util.List;

public class MulticallAggregationStrategy implements AggregationStrategy {
    @SuppressWarnings("unchecked")
    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // El cuerpo que va respondiendo cada llamado individual (un Map o String ya parseado)
        Object newBody = newExchange.getIn().getBody();
        
        if (oldExchange == null) {
            // Primer elemento: inicializamos la lista de respuestas
            List<Object> list = new ArrayList<>();
            list.add(newBody);
            newExchange.getIn().setBody(list);
            return newExchange;
        }
        
        // Elementos subsecuentes: acumulamos en la lista existente
        List<Object> list = oldExchange.getIn().getBody(List.class);
        list.add(newBody);
        return oldExchange;
    }
}