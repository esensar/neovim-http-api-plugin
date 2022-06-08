package com.ensarsarajcic.neovim.rest;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NeovimHttpHandler implements HttpHandler  {
    private final NeovimApi neovim;

    public NeovimHttpHandler(NeovimApi neovim) {
        this.neovim = neovim;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        System.err.println("Got request on " + exchange.getRequestURI().toString());
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write("Hello to you".getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
