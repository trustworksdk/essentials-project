package com.example.shop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

// S7: the spec is written from the running test context (which has its database) on `verify`.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT {
    @LocalServerPort
    int port;

    @Test
    void writes_the_contract() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var body = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                                   HttpResponse.BodyHandlers.ofString()).body();
            Files.writeString(Path.of("../contracts/openapi.json"), body);
        }
    }
}
