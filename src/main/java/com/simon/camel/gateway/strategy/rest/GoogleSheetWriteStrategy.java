package com.simon.camel.gateway.strategy.rest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

import com.google.auth.oauth2.GoogleCredentials;
import com.simon.camel.gateway.services.AmazonSecretsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GoogleSheetWriteStrategy implements IRestSecurityStrategy {

    private static final Pattern SPREADSHEET_ID_PATTERN = Pattern.compile("docs\\.google\\.com/spreadsheets/d/([^/]+)");

    private static final String DEFAULT_PROXY_BASE_URL = "https://sheets.googleapis.com/v4";
    private static final String DEFAULT_VALUE_INPUT_OPTION = "USER_ENTERED";
    private static final int MAX_ROWS_SCAN = 1000;

    @Autowired
    private AmazonSecretsService _secretsService;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() {
        return "google-sheet-write";
    }

    @SuppressWarnings("unchecked")
    @Override
    public void apply(Exchange exchange, Map<String, Object> headerConfig, Map<String, Object> datos) throws Exception {

        // 1. Resolver parámetros
        String secretName = readParam(headerConfig, "secret-name", "default/google-sheet-secret");
        String proxyBaseUrl = readParam(headerConfig, "proxy-base-url", DEFAULT_PROXY_BASE_URL);
        proxyBaseUrl = proxyBaseUrl.replaceAll("/$", "");
        String valueInputOption = readParam(headerConfig, "value-input-option", DEFAULT_VALUE_INPUT_OPTION);

        // 2. Extraer credenciales de AWS Secret Manager con scope de escritura
        Map<String, String> secrets = _secretsService.getAwsSecret(secretName);
        String serviceAccountJson = secrets.get("googleServiceAccountJson");

        if (serviceAccountJson == null) {
            throw new IllegalStateException("El secreto '" + secretName
                    + "' debe contener 'googleServiceAccountJson'.");
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)))
                .createScoped(List.of("https://www.googleapis.com/auth/spreadsheets"));

        credentials.refreshIfExpired();
        String token = credentials.getAccessToken().getTokenValue();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> authGetEntity = new HttpEntity<>(headers);

        // 3. Parsear bloque excel-sheet
        Map<String, Object> xl = (Map<String, Object>) headerConfig.get("excel-sheet");
        if (xl == null) {
            throw new IllegalArgumentException("Falta el bloque 'excel-sheet' en el header.");
        }
        String url = asString(xl.get("url"));
        String sheetNameRequest = asString(xl.get("sheet-name"));

        // 4. Extraer spreadsheetId
        Matcher m = SPREADSHEET_ID_PATTERN.matcher(url != null ? url : "");
        if (!m.find()) {
            throw new IllegalArgumentException("URL no es un Google Sheets valido: " + url);
        }
        String spreadsheetId = m.group(1);

        // 5. Resolver nombre de pestaña
        String sheetName = (sheetNameRequest == null || sheetNameRequest.isBlank())
                ? discoverFirstSheetTitle(restTemplate, authGetEntity, proxyBaseUrl, spreadsheetId)
                : sheetNameRequest;

        // 6. Determinar el modo de escritura
        // MODO A: Actualización por Filas y Columnas específicas (batchUpdate)
        // Ejemplo: datos.rows = [ { "row": 18, "cells": [{"column": 3, "value": "x"}, {"column": 4, "value": "y"}] } ]
        // o datos.cells = [ {"row": 18, "column": 3, "value": "x"} ]
        List<Map<String, Object>> batchData = buildBatchUpdateData(sheetName, datos, xl, proxyBaseUrl, spreadsheetId, authGetEntity);

        if (!batchData.isEmpty()) {
            // Ejecutar batchUpdate
            String batchUrl = proxyBaseUrl + "/spreadsheets/" + spreadsheetId + "/values:batchUpdate";
            Map<String, Object> batchRequestBody = new LinkedHashMap<>();
            batchRequestBody.put("valueInputOption", valueInputOption);
            batchRequestBody.put("data", batchData);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(batchRequestBody, headers);
            log.info("Google Sheets batchUpdate | spreadsheet={} | sheet={} | totalRanges={}",
                    spreadsheetId, sheetName, batchData.size());

            ResponseEntity<Map> response = restTemplate.exchange(
                    URI.create(batchUrl), HttpMethod.POST, requestEntity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Google Sheets batchUpdate respondio "
                        + response.getStatusCode() + " para spreadsheet " + spreadsheetId);
            }

            Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
            if (datos != null) {
                datos.put("status", "SUCCESS");
                datos.put("operation", "batchUpdate");
                datos.put("spreadsheetId", spreadsheetId);
                datos.put("sheetName", sheetName);
                datos.put("updatedRangesCount", batchData.size());
                datos.put("response", responseBody);
            }

            exchange.setProperty("googleSheet.handled", true);
            exchange.setProperty("gsheet.spreadsheetId", spreadsheetId);
            return;
        }

        // MODO B: Append clásico (añadir filas al final o en rango general)
        String customRange = asString(xl.get("range"));
        String targetRange = customRange != null && !customRange.isBlank()
                ? customRange
                : "'" + sheetName + "'!A1";

        List<List<Object>> rowsToWrite = extractRowsToWrite(datos, xl);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("range", targetRange);
        requestBody.put("majorDimension", "ROWS");
        requestBody.put("values", rowsToWrite);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
        String appendUrl = proxyBaseUrl
                + "/spreadsheets/" + spreadsheetId
                + "/values/" + URLEncoder.encode(targetRange, StandardCharsets.UTF_8)
                + ":append?valueInputOption=" + URLEncoder.encode(valueInputOption, StandardCharsets.UTF_8)
                + "&insertDataOption=INSERT_ROWS";

        log.info("Google Sheets append | spreadsheet={} | range={} | rowsCount={}",
                spreadsheetId, targetRange, rowsToWrite.size());

        ResponseEntity<Map> response = restTemplate.exchange(
                URI.create(appendUrl), HttpMethod.POST, requestEntity, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Google Sheets append respondio "
                    + response.getStatusCode() + " para spreadsheet " + spreadsheetId);
        }

        Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
        if (datos != null) {
            datos.put("status", "SUCCESS");
            datos.put("operation", "append");
            datos.put("spreadsheetId", spreadsheetId);
            datos.put("sheetName", sheetName);
            datos.put("writtenRows", rowsToWrite.size());
            datos.put("updates", responseBody.getOrDefault("updates", responseBody));
        }

        exchange.setProperty("googleSheet.handled", true);
        exchange.setProperty("gsheet.spreadsheetId", spreadsheetId);
        exchange.setProperty("gsheet.writtenRows", rowsToWrite.size());
    }

    // ---------- Construcción de batch update para filas y columnas específicas ----------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildBatchUpdateData(String sheetName, Map<String, Object> datos,
            Map<String, Object> xl, String proxyBaseUrl, String spreadsheetId, HttpEntity<Void> authEntity) {

        if (datos == null || datos.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> dataRanges = new ArrayList<>();

        // Caso 1: Lista de filas explícitas en datos: "rows": [ { "row": 18, "cells": [{"column": 3, "value": "val"}] } ]
        Object rowsObj = datos.get("rows");
        if (rowsObj instanceof List<?>) {
            for (Object rObj : (List<?>) rowsObj) {
                if (rObj instanceof Map<?, ?>) {
                    Map<String, Object> rMap = (Map<String, Object>) rObj;
                    int rowNum = asInt(rMap.get("row"));
                    if (rowNum <= 0) continue;

                    // Celdas por lista de objetos
                    Object cellsObj = rMap.get("cells");
                    if (cellsObj instanceof List<?>) {
                        for (Object cObj : (List<?>) cellsObj) {
                            if (cObj instanceof Map<?, ?>) {
                                Map<String, Object> cMap = (Map<String, Object>) cObj;
                                int colNum = asInt(cMap.get("column"));
                                Object val = cMap.get("value");
                                if (colNum > 0) {
                                    dataRanges.add(createCellRange(sheetName, rowNum, colNum, val));
                                }
                            }
                        }
                    }

                    // Celdas por mapa directo columna->valor (ej: {"3": "valA", "4": "valB"})
                    Object valuesMapObj = rMap.get("values");
                    if (valuesMapObj instanceof Map<?, ?>) {
                        Map<?, ?> vMap = (Map<?, ?>) valuesMapObj;
                        for (Map.Entry<?, ?> entry : vMap.entrySet()) {
                            int colNum = asInt(entry.getKey());
                            if (colNum > 0) {
                                dataRanges.add(createCellRange(sheetName, rowNum, colNum, entry.getValue()));
                            }
                        }
                    }
                }
            }
            if (!dataRanges.isEmpty()) {
                return dataRanges;
            }
        }

        // Caso 2: Lista plana de celdas: "cells": [ {"row": 18, "column": 3, "value": "x"}, ... ]
        Object cellsFlatObj = datos.get("cells");
        if (cellsFlatObj instanceof List<?>) {
            for (Object cObj : (List<?>) cellsFlatObj) {
                if (cObj instanceof Map<?, ?>) {
                    Map<String, Object> cMap = (Map<String, Object>) cObj;
                    int rowNum = asInt(cMap.get("row"));
                    int colNum = asInt(cMap.get("column"));
                    Object val = cMap.get("value");
                    if (rowNum > 0 && colNum > 0) {
                        dataRanges.add(createCellRange(sheetName, rowNum, colNum, val));
                    }
                }
            }
            if (!dataRanges.isEmpty()) {
                return dataRanges;
            }
        }

        // Caso 3: Búsqueda por ID (Lookup) y actualización de columnas en la fila coincidente
        // Si datos tiene "id" y (datos o xl tiene "column_write" o "columns")
        String lookupCode = asString(datos.get("id"));
        Object colWriteObj = datos.get("column_write") != null ? datos.get("column_write") : xl.get("column_write");

        if (lookupCode != null && colWriteObj instanceof List<?>) {
            int rowInit = asInt(xl.get("row_id_init")) > 0 ? asInt(xl.get("row_id_init")) : 1;
            int colInit = asInt(xl.get("column_id_init")) > 0 ? asInt(xl.get("column_id_init")) : 1;

            int matchedRow = findRowById(proxyBaseUrl, spreadsheetId, sheetName, rowInit, colInit, lookupCode, authEntity);
            if (matchedRow > 0) {
                for (Object cObj : (List<?>) colWriteObj) {
                    if (cObj instanceof Map<?, ?>) {
                        Map<String, Object> cMap = (Map<String, Object>) cObj;
                        int colNum = asInt(cMap.get("column"));
                        Object val = cMap.get("value");
                        if (colNum > 0) {
                            dataRanges.add(createCellRange(sheetName, matchedRow, colNum, val));
                        }
                    }
                }
                datos.put("matchedRow", matchedRow);
            }
        }

        return dataRanges;
    }

    private static Map<String, Object> createCellRange(String sheetName, int row, int col, Object value) {
        String colLetter = getColumnLetter(col);
        String cellCoord = "'" + sheetName + "'!" + colLetter + row;

        Map<String, Object> rangeEntry = new LinkedHashMap<>();
        rangeEntry.put("range", cellCoord);
        rangeEntry.put("values", List.of(List.of(value != null ? value : "")));
        return rangeEntry;
    }

    public static String getColumnLetter(int columnNumber) {
        StringBuilder sb = new StringBuilder();
        while (columnNumber > 0) {
            int rem = (columnNumber - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            columnNumber = (columnNumber - 1) / 26;
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private int findRowById(String baseUrl, String spreadsheetId, String sheetName, int rowInit, int colInit,
            String lookupCode, HttpEntity<Void> authEntity) {
        try {
            String range = "'" + sheetName + "'!A" + rowInit + ":Z" + (rowInit + MAX_ROWS_SCAN);
            String fullUrl = baseUrl + "/spreadsheets/" + spreadsheetId + "/values/"
                    + URLEncoder.encode(range, StandardCharsets.UTF_8);

            ResponseEntity<Map> response = restTemplate.exchange(
                    URI.create(fullUrl), HttpMethod.GET, authEntity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<List<Object>> values = (List<List<Object>>) response.getBody().get("values");
                if (values != null) {
                    for (int i = 0; i < values.size(); i++) {
                        List<Object> row = values.get(i);
                        if (row != null && row.size() >= colInit) {
                            Object cell = row.get(colInit - 1);
                            if (cell != null && lookupCode.trim().equals(String.valueOf(cell).trim())) {
                                return rowInit + i;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error en findRowById para {}: {}", lookupCode, e.getMessage());
        }
        return -1;
    }

    // ---------- Extracción dinámica de filas para Append ----------

    @SuppressWarnings("unchecked")
    private List<List<Object>> extractRowsToWrite(Map<String, Object> datos, Map<String, Object> xl) {
        if (datos == null || datos.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Object>> result = new ArrayList<>();

        Object valuesObj = datos.get("values");
        if (valuesObj == null) valuesObj = datos.get("rows");
        if (valuesObj == null) valuesObj = datos.get("data");

        if (valuesObj instanceof List<?>) {
            List<?> list = (List<?>) valuesObj;
            if (!list.isEmpty()) {
                if (list.get(0) instanceof List<?>) {
                    for (Object item : list) {
                        if (item instanceof List<?>) {
                            result.add(new ArrayList<>((List<Object>) item));
                        }
                    }
                } else {
                    result.add(new ArrayList<>((List<Object>) list));
                }
                return result;
            }
        }

        List<Map<String, Object>> colOrder = (List<Map<String, Object>>) xl.get("column_order");
        if (colOrder != null && !colOrder.isEmpty()) {
            List<Object> row = new ArrayList<>();
            for (Map<String, Object> col : colOrder) {
                String key = asString(col.get("field"));
                row.add(key != null ? datos.get(key) : null);
            }
            result.add(row);
            return result;
        }

        List<Object> row = new ArrayList<>(datos.values());
        result.add(row);
        return result;
    }

    // ---------- helpers ----------

    private static String readParam(Map<String, Object> headerConfig, String name, String def) {
        Object paramsObj = headerConfig.get("function-parameters");
        if (!(paramsObj instanceof List))
            return def;
        for (Object p : (List<?>) paramsObj) {
            if (p instanceof Map) {
                Map<?, ?> pm = (Map<?, ?>) p;
                if (name.equals(pm.get("name"))) {
                    Object v = pm.get("value");
                    if (v != null)
                        return String.valueOf(v);
                }
            }
        }
        return def;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int asInt(Object o) {
        if (o == null)
            return 0;
        if (o instanceof Number)
            return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private String discoverFirstSheetTitle(RestTemplate rt, HttpEntity<Void> entity,
            String baseUrl, String spreadsheetId) {
        String url = baseUrl + "/spreadsheets/" + spreadsheetId + "?fields=sheets/properties/title";
        try {
            ResponseEntity<Map> r = rt.exchange(URI.create(url), HttpMethod.GET, entity, Map.class);
            if (r.getBody() == null)
                return "Hoja1";
            Object sheetsObj = r.getBody().get("sheets");
            if (sheetsObj instanceof List && !((List<?>) sheetsObj).isEmpty()) {
                Object first = ((List<?>) sheetsObj).get(0);
                if (first instanceof Map) {
                    Object props = ((Map<?, ?>) first).get("properties");
                    if (props instanceof Map) {
                        Object title = ((Map<?, ?>) props).get("title");
                        if (title != null)
                            return String.valueOf(title);
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
