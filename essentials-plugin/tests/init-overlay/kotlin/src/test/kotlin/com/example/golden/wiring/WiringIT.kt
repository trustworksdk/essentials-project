package com.example.golden.wiring

import com.example.golden.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** CI-only wiring checks that compile cleanly and fail silently when the scaffold is wrong. */
class WiringIT : IntegrationTestBase() {

    @Test
    fun `a value-class id binds as a path variable and is written as a json string`() {
        val response = get("/api/wiring/probe-1")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).`as`("the web mapper lacks KotlinModule (S3.4)").isEqualTo(EXPECTED)
    }

    @Test
    fun `the persistence serializer writes a value-class id as a json string`() {
        val response = get("/api/wiring/probe-1/persisted")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body())
            .`as`("{\"value\":…} means the persistence mapper lacks KotlinModule (S3.2, S3.4)")
            .isEqualTo(EXPECTED)
    }

    @Test
    fun `the command bus is the starter's durable one`() {
        assertThat(get("/api/wiring/command-bus").body()).isEqualTo("DurableLocalCommandBus")
    }

    private companion object {
        const val EXPECTED = "{\"id\":\"probe-1\",\"related\":[\"probe-1\"]}"
    }

    private fun get(path: String): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            client.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).build(), HttpResponse.BodyHandlers.ofString())
        }
}
