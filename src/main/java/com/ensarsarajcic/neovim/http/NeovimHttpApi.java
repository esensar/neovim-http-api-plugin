package com.ensarsarajcic.neovim.http;

import com.ensarsarajcic.neovim.java.handler.annotations.NeovimRequestHandler;
import com.ensarsarajcic.neovim.java.handler.errors.NeovimRequestException;
import com.ensarsarajcic.neovim.java.pluginhost.NeovimJavaPluginHost;
import com.ensarsarajcic.neovim.java.pluginhost.annotations.NeovimJavaHostedPlugin;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

@NeovimJavaHostedPlugin
public final class NeovimHttpApi {
    private final NeovimJavaPluginHost host;

    public NeovimHttpApi(NeovimJavaPluginHost host) {
        this.host = host;
    }

    @NeovimRequestHandler
    public boolean start(StartOptions options) throws NeovimRequestException {
        try {
            var handler = new NeovimHttpHandler(host.getApiInfo(), host.getReactiveClient(), options);
            var server = HttpServer.create();
            server.setExecutor(
                    Executors.newFixedThreadPool(
                            options.getThreadCount().orElse(Runtime.getRuntime().availableProcessors())
                    )
            );
            server.createContext("/", handler);
            server.bind(new InetSocketAddress(options.getPort()), 0);
            server.start();
        } catch (IOException e) {
            throw new NeovimRequestException("Starting server failed: " + e);
        }
        return true;
    }
}