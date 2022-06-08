package com.ensarsarajcic.neovim.rest;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.types.apiinfo.ApiInfo;
import com.ensarsarajcic.neovim.java.api.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NeovimHttpHandler implements HttpHandler  {
    private final NeovimApi neovim;
    private final ObjectMapper mapper;

    public NeovimHttpHandler(NeovimApi neovim) {
        this.neovim = neovim;
        this.mapper = ObjectMappers.defaultNeovimMapper();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        System.err.printf("Got request: %s %s%n", exchange.getRequestMethod(), exchange.getRequestURI());
        switch (exchange.getRequestURI().getPath()) {
            case "/api-info":
                if (exchange.getRequestMethod().equals("GET")) {
                    neovim.getApiInfo().thenAccept(info -> {
                        try {
                            var body = info.toString().getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                            try {
                                exchange.sendResponseHeaders(500, -1);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        } finally {
                            exchange.close();
                        }
                    });
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
                break;
            default:
                exchange.sendResponseHeaders(404, -1);
        }
    }
}
