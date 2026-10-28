/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The driver's HTTP side: one shared client, customers in logged-in sessions (Q-1) - the login form once, its
 * {@code JSESSIONID} after, so a session pays the password hash once, as a browser would - and the operator over HTTP
 * Basic for the few setup and report calls.
 */
final class BrokerageHttp {
    record Response(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
                                                .version(HttpClient.Version.HTTP_1_1)
                                                .followRedirects(HttpClient.Redirect.NEVER)
                                                .connectTimeout(Duration.ofSeconds(5))
                                                .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final StepStats  stats;
    private final String     operatorAuthorization;

    BrokerageHttp(StepStats stats, String operatorUser, String operatorPassword) {
        this.stats = stats;
        this.operatorAuthorization = "Basic " + Base64.getEncoder().encodeToString((operatorUser + ":" + operatorPassword).getBytes(StandardCharsets.UTF_8));
    }

    JsonNode json(Response response) {
        return json.readTree(response.body());
    }

    String write(Object body) {
        return json.writeValueAsString(body);
    }

    /**
     * A customer's session against one instance; logs in on first use and again when the session is gone.
     */
    final class Session {
        private final String    baseUrl;
        private final String    email;
        private final String    password;
        private volatile String cookie;

        Session(String baseUrl, String email, String password) {
            this.baseUrl = baseUrl;
            this.email = email;
            this.password = password;
        }

        Response get(String step, String path) {
            return withSession(step, () -> HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
        }

        Response post(String step, String path, Object body) {
            return withSession(step, () -> HttpRequest.newBuilder(URI.create(baseUrl + path))
                                                      .header("Content-Type", "application/json")
                                                      .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(write(body))));
        }

        Response put(String step, String path, Object body) {
            return withSession(step, () -> HttpRequest.newBuilder(URI.create(baseUrl + path))
                                                      .header("Content-Type", "application/json")
                                                      .PUT(HttpRequest.BodyPublishers.ofString(write(body))));
        }

        private Response withSession(String step, java.util.function.Supplier<HttpRequest.Builder> request) {
            if (cookie == null && !login()) {
                return new Response(401, "login failed");
            }
            var response = send(step, request.get().header("Cookie", cookie));
            if (response.status() == 401 && login()) {
                response = send(step, request.get().header("Cookie", cookie));
            }
            return response;
        }

        synchronized boolean login() {
            var started = System.nanoTime();
            try {
                var page = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
                var csrf = CSRF.matcher(page.body());
                var anonymous = sessionCookie(page).orElse(null);
                if (!csrf.find() || anonymous == null) {
                    stats.record("LOGIN", System.nanoTime() - started, "NO_FORM");
                    return false;
                }
                var form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                           + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                           + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
                var answer = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                                    .header("Cookie", anonymous)
                                                    .POST(HttpRequest.BodyPublishers.ofString(form))
                                                    .build(),
                                         HttpResponse.BodyHandlers.ofString());
                var location = answer.headers().firstValue("Location").orElse("");
                if (answer.statusCode() != 302 || location.contains("error")) {
                    stats.record("LOGIN", System.nanoTime() - started, "REFUSED");
                    return false;
                }
                cookie = sessionCookie(answer).orElse(anonymous);
                stats.record("LOGIN", System.nanoTime() - started, "302");
                return true;
            } catch (IOException e) {
                stats.record("LOGIN", System.nanoTime() - started, "IO");
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    Response operatorGet(String step, String url) {
        return send(step, HttpRequest.newBuilder(URI.create(url)).header("Authorization", operatorAuthorization).GET());
    }

    Response operatorPost(String step, String url, Object body) {
        return send(step, HttpRequest.newBuilder(URI.create(url))
                                     .header("Authorization", operatorAuthorization)
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    Response plainGet(String step, String url) {
        return send(step, HttpRequest.newBuilder(URI.create(url)).GET());
    }

    Response plainPut(String step, String url, Object body) {
        return send(step, HttpRequest.newBuilder(URI.create(url))
                                     .header("Content-Type", "application/json")
                                     .PUT(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    Response plainDelete(String step, String url) {
        return send(step, HttpRequest.newBuilder(URI.create(url)).DELETE());
    }

    /**
     * A GET whose body is read as a stream, for answers too large to hold as a tree (a run's webhook attempts).
     */
    <T> T plainGetStreaming(String url, java.util.function.Function<tools.jackson.core.JsonParser, T> reader) {
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                                       HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body(); var parser = json.createParser(body)) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("GET " + url + ": " + response.statusCode());
                }
                return reader.apply(parser);
            }
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GET " + url + ": interrupted");
        }
    }

    private Response send(String step, HttpRequest.Builder request) {
        var started = System.nanoTime();
        try {
            var response = client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            stats.record(step, System.nanoTime() - started, Integer.toString(response.statusCode()));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            stats.record(step, System.nanoTime() - started, "IO");
            return new Response(-1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, "interrupted");
        }
    }

    private static Optional<String> sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                       .filter(value -> value.startsWith("JSESSIONID="))
                       .map(value -> value.substring(0, value.contains(";") ? value.indexOf(';') : value.length()))
                       .findFirst();
    }
}
