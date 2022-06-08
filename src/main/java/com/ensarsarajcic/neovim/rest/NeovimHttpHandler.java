package com.ensarsarajcic.neovim.rest;

import com.ensarsarajcic.neovim.java.api.types.apiinfo.ApiInfo;
import com.ensarsarajcic.neovim.java.api.types.apiinfo.FunctionInfo;
import com.ensarsarajcic.neovim.java.corerpc.message.RequestMessage;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class NeovimHttpHandler implements HttpHandler  {
    private final ApiInfo info;
    private final ReactiveRpcClient client;
    private final ObjectMapper mapper;
    private final Map<String, FunctionInfo> functions;

    public NeovimHttpHandler(ApiInfo info, ReactiveRpcClient client) {
        this.info = info;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.functions = info.getFunctions()
                .stream()
                .collect(Collectors.toMap(FunctionInfo::getName, Function.identity()));
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        System.err.printf("Got request: %s %s%n", exchange.getRequestMethod(), exchange.getRequestURI());
        var name = "nvim_" + exchange.getRequestURI().getPath().replace("/", "");
        if (!functions.containsKey(name)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        var function = functions.get(name);

        var bodyBytes = exchange.getRequestBody().readAllBytes();
        var requestBody = mapper.reader().readTree(bodyBytes);
        if (!requestBody.isArray()) {
            var response = "Body should always be an array!".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        if (function.getParameters().size() != requestBody.size()) {
            var response = String.format(
                    "Expected %d arguments, but found %d",
                    function.getParameters().size(),
                    requestBody.size()
            ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }

        var arguments = new ArrayList<>();
        requestBody.elements().forEachRemaining(arguments::add);
        var builder = new RequestMessage.Builder(name)
                .addArguments(arguments);
        client.response(builder).thenAccept(
                responseMessage -> {
                    try (exchange) {
                        byte[] responseBody;
                        if (responseMessage.getError() == null) {
                            responseBody = mapper.writer().writeValueAsBytes(responseMessage.getResult());
                        } else {
                            responseBody = responseMessage.getError().toString().getBytes(StandardCharsets.UTF_8);
                        }
                        exchange.sendResponseHeaders(200, responseBody.length);
                        exchange.getResponseBody().write(responseBody);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }
}
