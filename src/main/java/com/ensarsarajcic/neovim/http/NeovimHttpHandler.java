package com.ensarsarajcic.neovim.http;

import com.ensarsarajcic.neovim.java.api.types.apiinfo.ApiInfo;
import com.ensarsarajcic.neovim.java.api.types.apiinfo.FunctionInfo;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Buffer;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Tabpage;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Window;
import com.ensarsarajcic.neovim.java.corerpc.message.RequestMessage;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcClient;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamer;
import com.ensarsarajcic.neovim.java.corerpc.reactive.RpcException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class NeovimHttpHandler implements HttpHandler  {
    private static final Logger log = LoggerFactory.getLogger(NeovimHttpHandler.class);

    private final ApiInfo info;
    private final ReactiveRpcStreamer client;
    private final ObjectMapper mapper;
    private final Map<String, FunctionInfo> functions;
    private final int requestTimeoutMs;

    public NeovimHttpHandler(ApiInfo info, ReactiveRpcStreamer client, StartOptions startOptions) {
        this.info = info;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.functions = info.getFunctions()
                .stream()
                .collect(Collectors.toMap(FunctionInfo::getName, Function.identity()));
        this.requestTimeoutMs = startOptions.getRequestTimeoutMs().orElse(10000);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        log.info("Got request: {} {}", exchange.getRequestMethod(), exchange.getRequestURI());

        var arguments = new ArrayList<>();
        var prefix = "nvim_";
        String name;
        try {
            if (exchange.getRequestURI().getPath().startsWith("/win")) {
                prefix = prefix + "win_";
                name = exchange.getRequestURI().getPath().replaceFirst("/win/\\d*", "");
                var id = Integer.parseInt(exchange.getRequestURI().getPath().split("/")[2]);
                arguments.add(new Window(id));
            } else if (exchange.getRequestURI().getPath().startsWith("/buf")) {
                prefix = prefix + "buf_";
                name = exchange.getRequestURI().getPath().replaceFirst("/buf/\\d*", "");
                var id = Integer.parseInt(exchange.getRequestURI().getPath().split("/")[2]);
                arguments.add(new Buffer(id));
            } else if (exchange.getRequestURI().getPath().startsWith("/tabpage")) {
                prefix = prefix + "tabpage_";
                name = exchange.getRequestURI().getPath().replaceFirst("/tabpage/\\d*", "");
                var id = Integer.parseInt(exchange.getRequestURI().getPath().split("/")[2]);
                arguments.add(new Tabpage(id));
            } else {
                name = exchange.getRequestURI().getPath();
            }
            name = name.replace("/", "");
        } catch (Exception ex) {
            log.info("Error: {}", ex.getMessage());
            ex.printStackTrace();
            var response = ex.getMessage().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "GET":
                prefix = prefix + "get_";
                break;
            case "PUT":
                prefix = prefix + "set_";
                break;
            case "DELETE":
                if (name.isEmpty()) {
                    // Special case for nvim_buf_delete
                    prefix =  prefix + "delete";
                } else {
                    prefix = prefix + "del_";
                }
                break;
        }

        name = prefix + name;

        log.info("Final method name: {}", name);
        log.info("PreNext log");
        if (!functions.containsKey(name)) {
            log.info("Name: {}  - not found", name);
            var response = String.format(
                    "Unknown function: %s",
                    name
            ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        log.info("Next log");

        var function = functions.get(name);

        Map<String, Object> requestMap = new HashMap<>();

        var queryParamsString = exchange.getRequestURI().getQuery();
        log.info("Parsing query: {}", queryParamsString);
        if (queryParamsString != null) {
            for (var param : queryParamsString.split("&")) {
                var values = param.split("=");
                if (values.length > 2) {
                    var response = String.format(
                            "Invalid query parameters string: %s",
                            queryParamsString
                    ).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
                var key = values[0];
                var container = requestMap;
                while (key.contains(".")) {
                    var parent = key.split("\\.")[0];
                    container.putIfAbsent(parent, new HashMap<>());
                    container = (Map<String, Object>) container.get(parent);
                    key = key.replaceFirst(parent + "\\.", "");
                }
                if (values.length == 1) {
                    container.put(key, null);
                } else {
                    try {
                        container.put(key, Long.parseLong(values[1]));
                    } catch (Exception ex) {
                        try {
                            container.put(key, Boolean.parseBoolean(values[1]));
                        } catch (Exception e) {
                            try {
                                container.put(key, Double.parseDouble(values[1]));
                            } catch (Exception exception) {
                                container.put(key, values[1]);
                            }
                        }
                    }
                }
            }
        }

        log.info("Starting body parse");
        try {
            if (exchange.getRequestBody().available() > 0) {
                var bodyBytes = exchange.getRequestBody().readAllBytes();
                var requestBody = mapper.reader().readTree(bodyBytes);
                if (bodyBytes.length > 0 && !requestBody.isObject()) {
                    var response = "Body should be an object!".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }

                requestMap.putAll(mapper.convertValue(requestBody, new TypeReference<>(){}));
            }
        } catch (IOException ex) {
            log.error("Body parse fail", ex);
        }

        for (int i = arguments.size(); i < function.getParameters().size(); i++) {
            var key = function.getParameters().get(i).getName();
            if (requestMap.containsKey(key)) {
                arguments.add(requestMap.get(key));
            } else {
                var response = String.format(
                        "Required argument not found: %s",
                        key
                ).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
        }

        log.info("Arguments prepared: {}", arguments);
        if (function.getParameters().size() != arguments.size()) {
            var response = String.format(
                    "Expected %d arguments, but found %d",
                    function.getParameters().size() - arguments.size(),
                    requestMap.size()
            ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }

        var builder = new RequestMessage.Builder(name)
                .addArguments(arguments);

        log.info("Message prepared: {}", builder);
        client.response(builder).orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete(
                        (responseMessage, throwable) -> {
                            try (exchange) {
                                byte[] responseBody;
                                if (throwable != null) {
                                    if (throwable instanceof RpcException) {
                                        responseBody = mapper.writer().writeValueAsBytes(((RpcException) throwable).getRpcError());
                                        exchange.sendResponseHeaders(400, responseBody.length);
                                    } else {
                                        responseBody = throwable.getMessage().getBytes(StandardCharsets.UTF_8);
                                        exchange.sendResponseHeaders(500, responseBody.length);
                                    }
                                    exchange.getResponseBody().write(responseBody);
                                } else {
                                    if (responseMessage.getError() == null) {
                                        responseBody = mapper.writer().writeValueAsBytes(responseMessage.getResult());
                                    } else {
                                        responseBody = responseMessage.getError().toString().getBytes(StandardCharsets.UTF_8);
                                    }
                                    exchange.sendResponseHeaders(200, responseBody.length);
                                    exchange.getResponseBody().write(responseBody);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
    }
}
