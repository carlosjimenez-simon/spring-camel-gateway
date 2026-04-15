package com.simon.camel.gateway.model;

import lombok.Data;
import jakarta.xml.bind.annotation.XmlRootElement;

@Data
@XmlRootElement(name = "ClienteRequest")
public class ClienteRequest {
    private String id;
    private String nombre;
}
