package com.simon.camel.gateway.strategy.rest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GoogleSheetAuthStrategy implements IRestSecurityStrategy {

    private static final Pattern SPREADSHEET_ID_PATTERN =
            Pattern.compile("docs\\.google\\.com/spreadsheets/d/([^/]+)");

    private static final String DEFAULT_PROXY_BASE_URL = "https://sheets.googleapis.com/v4";
    private static final int MAX_ROWS_SCAN = 1000;

    @Autowired
    private AmazonSecretsService _secretsService;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() {
        return "google-sheet-auth";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

        // 1. Resolver parámetros (secret-name, proxy-base-url)
        String secretName = readParam(headerConfig, "secret-name", "default/google-sheet-secret");
        String proxyBaseUrl = readParam(headerConfig, "proxy-base-url", DEFAULT_PROXY_BASE_URL);
        proxyBaseUrl = proxyBaseUrl.replaceAll("/$", "");

        // 2. Extraer credenciales del AWS Secret Manager
        Map<String, String> secrets = _secretsService.getAwsSecret(secretName);
        String email = secrets.get("google-service-account");
        String password = secrets.get("password");
        if (email == null || password == null) {
            throw new IllegalStateException("El secreto '" + secretName
                    + "' debe contener 'google-service-account' y 'password'.");
        }

        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> authEntity = new HttpEntity<>(headers);

        // 3. Parsear bloque excel-sheet
        Map<String, Object> xl = (Map<String, Object>) headerConfig.get("excel-sheet");
        if (xl == null) {
            throw new IllegalArgumentException("Falta el bloque 'excel-sheet' en el header.");
        }
        String url = asString(xl.get("url"));
        int rowInit = asInt(xl.get("row_id_init"));
        int colInit = asInt(xl.get("column_id_init"));
        String sheetNameRequest = asString(xl.get("sheet-name"));
        List<Map<String, Object>> colsRet = (List<Map<String, Object>>) xl.get("column_return");
        if (colsRet == null) colsRet = List.of();

        // 4. Extraer spreadsheetId
        Matcher m = SPREADSHEET_ID_PATTERN.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("URL no es un Google Sheets valido: " + url);
        }
        String spreadsheetId = m.group(1);

        // 5. Resolver nombre de pestaña
        String sheetName = (sheetNameRequest == null || sheetNameRequest.isBlank())
                ? discoverFirstSheetTitle(restTemplate, authEntity, proxyBaseUrl, spreadsheetId)
                : sheetNameRequest;

        // 6. Construir range y llamar al endpoint
        String range = "'" + sheetName + "'!A" + rowInit + ":Z" + (rowInit + MAX_ROWS_SCAN);
        String fullUrl = proxyBaseUrl
                + "/spreadsheets/" + spreadsheetId
                + "/values/" + URLEncoder.encode(range, StandardCharsets.UTF_8);

        log.info("Google Sheets lookup | spreadsheet={} | range={} | proxy={}",
                spreadsheetId, range, proxyBaseUrl);

        ResponseEntity<Map> response = restTemplate.exchange(
                URI.create(fullUrl), HttpMethod.GET, authEntity, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Google Sheets respondio "
                    + response.getStatusCode() + " para spreadsheet " + spreadsheetId);
        }
        List<List<Object>> values = (List<List<Object>>) response.getBody().get("values");

        // 7. Lookup del codigo en columna de referencia
        String lookupCode = datos == null ? null : asString(datos.get("id"));
        int matchIdx = -1;
        if (values != null && lookupCode != null) {
            for (int i = 0; i < values.size(); i++) {
                List<Object> row = values.get(i);
                if (row != null && row.size() >= colInit) {
                    Object cell = row.get(colInit - 1);
                    if (cell != null && lookupCode.trim().equals(String.valueOf(cell).trim())) {
                        matchIdx = i;
                        break;
                    }
                }
            }
        }

        // 8. Proyectar columnas solicitadas y armar respuesta
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lookupCode", lookupCode);
        body.put("matchedRow", matchIdx >= 0 ? rowInit + matchIdx : null);

        List<Map<String, Object>> projected = new ArrayList<>();
        if (matchIdx >= 0) {
            List<Object> matchedRow = values.get(matchIdx);
            for (Map<String, Object> c : colsRet) {
                int n = asInt(c.get("column"));
                Object v = (n - 1) < matchedRow.size() ? matchedRow.get(n - 1) : null;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("column", n);
                cell.put("value", v);
                projected.add(cell);
            }
        }
        body.put("values", projected);

        exchange.getIn().setBody(body);
        exchange.setProperty("googleSheet.handled", true);
        exchange.setProperty("gsheet.lookupCode", lookupCode);
        exchange.setProperty("gsheet.matchedRow", body.get("matchedRow"));
        log.info("Google Sheet lookup finalizado | matchedRow={} | valores proyectados={}",
                body.get("matchedRow"), projected.size());
    }

    // ---------- helpers ----------

    private static String readParam(Map<String, Object> headerConfig, String name, String def) {
        Object paramsObj = headerConfig.get("function-parameters");
        if (!(paramsObj instanceof List)) return def;
        for (Object p : (List<?>) paramsObj) {
            if (p instanceof Map) {
                Map<?, ?> pm = (Map<?, ?>) p;
                if (name.equals(pm.get("name"))) {
                    Object v = pm.get("value");
                    if (v != null) return String.valueOf(v);
                }
            }
        }
        return def;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private String discoverFirstSheetTitle(RestTemplate rt, HttpEntity<Void> entity,
                                           String baseUrl, String spreadsheetId) {
        String url = baseUrl + "/spreadsheets/" + spreadsheetId + "?fields=sheets/properties/title";
        try {
            ResponseEntity<Map> r = rt.exchange(URI.create(url), HttpMethod.GET, entity, Map.class);
            if (r.getBody() == null) return "Hoja1";
            Object sheetsObj = r.getBody().get("sheets");
            if (sheetsObj instanceof List && !((List<?>) sheetsObj).isEmpty()) {
                Object first = ((List<?>) sheetsObj).get(0);
                if (first instanceof Map) {
                    Object props = ((Map<?, ?>) first).get("properties");
                    if (props instanceof Map) {
                        Object title = ((Map<?, ?>) props).get("title");
                        if (title != null) return String.valueOf(title);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("No se pudo descubrir el nombre de la primera pestana de {}: {}",
                    spreadsheetId, e.getMessage());
        }
        return "Hoja1";
    }
}
