package com.simon.camel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.simon.camel.gateway.processors.RestHeaderProcessor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class SpringCamelGatewayApplication {

	public static void main(String[] args) {
		log.info("Version 18 Jul 2026 14:40");
		SpringApplication.run(SpringCamelGatewayApplication.class, args);
	}

}
