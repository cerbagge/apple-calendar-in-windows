package icloudcal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/**
 * Claude 가 iCloud 캘린더를 다룰 수 있게 하는 MCP 서버 (stdio, 한 줄에 JSON-RPC 메시지 하나).
 * 계정은 달력 앱이 저장해 둔 것을 그대로 쓴다. stdout 에는 프로토콜 메시지만 쓴다.
 */
public final class McpServer {
    static final String PROTOCOL_VERSION = "2025-06-18";
    static final String VERSION = "3.2.0";

    private final ObjectMapper json = new ObjectMapper();
    private final CalendarTools tools;

    McpServer(CalendarTools tools) {
        this.tools = tools;
    }

    public static void main(String[] args) throws IOException {
        CalendarTools tools = new CalendarTools(() -> {
            CredentialStore.Account account = CredentialStore.load().orElseThrow(() ->
                    new IllegalStateException("저장된 iCloud 계정이 없습니다. 달력 앱을 실행해 계정을 먼저 연결하십시오."));
            return new CalDavClient(account.appleId(), account.password());
        }, ZoneId.systemDefault());
        new McpServer(tools).serve(System.in, System.out);
    }

    void serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, false, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("﻿")) {
                line = line.substring(1); // 일부 클라이언트가 붙이는 UTF-8 BOM
            }
            if (line.isBlank()) {
                continue;
            }
            JsonNode response;
            try {
                response = handle(json.readTree(line));
            } catch (JsonProcessingException e) {
                response = error(null, -32700, "JSON 을 해석하지 못했습니다.");
            }
            if (response != null) {
                writer.print(json.writeValueAsString(response) + "\n");
                writer.flush();
            }
        }
    }

    /** 요청 하나를 처리한다. 알림(id 없음)에는 응답하지 않으므로 null 을 돌려준다. */
    JsonNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (id == null) {
            return null;
        }
        String method = request.path("method").asText();
        JsonNode params = request.path("params");
        try {
            JsonNode result;
            switch (method) {
                case "initialize" -> result = initialize(params);
                case "ping" -> result = json.createObjectNode();
                case "tools/list" -> result = tools.list();
                case "tools/call" -> {
                    String name = params.path("name").asText();
                    result = tools.call(name, params.path("arguments"));
                    if (result == null) {
                        return error(id, -32602, "알 수 없는 도구: " + name);
                    }
                }
                default -> {
                    return error(id, -32601, "지원하지 않는 메서드: " + method);
                }
            }
            ObjectNode response = json.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (RuntimeException e) {
            return error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private JsonNode initialize(JsonNode params) {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText(PROTOCOL_VERSION));
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "icloud-calendar");
        info.put("version", VERSION);
        result.put("instructions", "iCloud 캘린더의 일정을 조회하고 추가합니다. 날짜와 시간은 "
                + ZoneId.systemDefault().getId() + " 시간대 기준입니다.");
        return result;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
