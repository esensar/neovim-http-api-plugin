package com.ensarsarajcic.neovim.http;

import com.ensarsarajcic.neovim.java.api.types.apiinfo.ApiInfo;
import com.ensarsarajcic.neovim.java.api.types.apiinfo.FunctionInfo;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Buffer;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Tabpage;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Window;
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

        System.err.printf("Final method name %s%n", name);
        if (!functions.containsKey(name)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        var function = functions.get(name);

        var bodyBytes = exchange.getRequestBody().readAllBytes();
        var requestBody = mapper.reader().readTree(bodyBytes);
        if (function.getParameters().size() > arguments.size() && !requestBody.isArray()) {
            var response = "Body should always be an array!".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        if (function.getParameters().size() != requestBody.size() + arguments.size()) {
            var response = String.format(
                    "Expected %d arguments, but found %d",
                    function.getParameters().size() - arguments.size(),
                    requestBody.size()
            ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }

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
