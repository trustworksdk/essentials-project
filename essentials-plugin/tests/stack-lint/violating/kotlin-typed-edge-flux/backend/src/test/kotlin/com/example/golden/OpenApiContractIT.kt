package com.example.golden

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

// S7: the spec is written from the running test context, which has its database.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `writes contracts openapi json`() {
        val body = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/v3/api-docs")).build(),
            HttpResponse.BodyHandlers.ofString()).body()
        Files.writeString(Path.of("../contracts", "openapi.json"), body)
    }
}
