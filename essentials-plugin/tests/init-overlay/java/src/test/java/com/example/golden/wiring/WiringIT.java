package com.example.golden.wiring;

import com.example.golden.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** CI-only wiring checks that compile cleanly and fail silently when the scaffold is wrong. */
class WiringIT extends IntegrationTestBase {
    private static final String EXPECTED = "{\"id\":\"probe-1\",\"related\":[\"probe-1\"]}";

    @Test
    void a_semantic_type_binds_as_a_path_variable_and_is_written_as_a_json_string() throws Exception {
        var response = get("/api/wiring/probe-1");

        assertThat(response.statusCode()).as("500 means no Essentials*WebConfigurer is imported (S4)").isEqualTo(200);
        assertThat(response.body()).as("the web mapper lacks EssentialTypesJacksonModule (S3.3)").isEqualTo(EXPECTED);
    }

    @Test
    void the_persistence_serializer_writes_a_semantic_type_as_a_json_string() throws Exception {
        var response = get("/api/wiring/probe-1/persisted");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(EXPECTED);
    }

    @Test
    void the_command_bus_is_the_starters_durable_one() throws Exception {
        assertThat(get("/api/wiring/command-bus").body()).isEqualTo("DurableLocalCommandBus");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
