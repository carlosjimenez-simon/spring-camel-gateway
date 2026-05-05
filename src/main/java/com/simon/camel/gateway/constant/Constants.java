package com.simon.camel.gateway.constant;

public final class Constants {
	
	/**init - Routes Id*/
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_SOAP = "generic-soap-gateway";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_GATEWAY_GENERIC_REST = "generic-rest-gateway";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_SOAP = "simon-route-core-soap";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_REST = "simon-route-core-rest";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_AUDIT_SOAP = "simon-route-audit-soap";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_AUDIT_REST = "simon-route-audit-rest";
	public static final String SIMON_SPRING_CAMEL_ROUTE_ID_AUDIT_UPLOAD_S3 = "s3-upload-engine";
	/**end - Routes Id*/
	
	/**init - Rutas Base*/
	public static final String SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_SOAP = "/api/v1/soap";
	public static final String SIMON_SPRING_CAMEL_ROUTE_BASE_GENERIC_REST = "/api/v1/rest";
	/**end - Rutas Base*/
	
	
	/**init - End-Points From*/
	public static final String SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_REST = "direct:process-generic-rest";
	public static final String SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_GENERIC_SOAP_WITH_TEMPLATE = "direct:process-generic-soap-with-plantilla";
	public static final String SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_SOAP = "direct:audit-generic-soap-s3";
	public static final String SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_GENERIC_REST = "direct:audit-generic-rest-s3";
	public static final String SIMON_SPRING_CAMEL_DIRECT_FROM_PROCESAR_AUDIT_UPLOAD_S3 = "direct:audit-upload-s3";
	
	
	
	/**init - End-Points From*/
	
	

}
