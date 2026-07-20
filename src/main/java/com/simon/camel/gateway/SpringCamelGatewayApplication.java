package com.simon.camel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.simon.camel.gateway.processors.RestHeaderProcessor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class SpringCamelGatewayApplication {

	public static void main(String[] args) {
		log.info("Version 20 Jul 2026 09:38");
		SpringApplication.run(SpringCamelGatewayApplication.class, args);
	}

}
