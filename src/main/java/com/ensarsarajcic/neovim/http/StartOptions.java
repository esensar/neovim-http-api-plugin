package com.ensarsarajcic.neovim.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StartOptions {
    private final int port;
    private final Integer threadCount;
    private final String rootUrl;
    private final Integer requestTimeoutMs;

    public StartOptions(
            @JsonProperty("port") int port,
            @JsonProperty("thread_count") Integer threadCount,
            @JsonProperty("root_url") String rootUrl,
            @JsonProperty("request_timeout_ms") Integer requestTimeoutMs) {
        this.port = port;
        this.threadCount = threadCount;
        this.rootUrl = rootUrl;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getPort() {
        return port;
    }

    public Optional<Integer> getThreadCount() {
        return Optional.ofNullable(threadCount);
    }

    public Optional<String> getRootUrl() {
        return Optional.ofNullable(rootUrl);
    }

    public Optional<Integer> getRequestTimeoutMs() {
        return Optional.ofNullable(requestTimeoutMs);
    }
}
