package com.simon.camel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.simon.camel.gateway.processors.RestHeaderProcessor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class SpringCamelGatewayApplication {

	public static void main(String[] args) {
		log.info("Version 03 Sept 2026 12:42");
		SpringApplication.run(SpringCamelGatewayApplication.class, args);
	}

}
