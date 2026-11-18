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

package dk.trustworks.essentials.examples.trading._customer_ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The customer UI's server side (phase 7 design §8): the pages, who may see them, the CSRF rule both ways (§5), and the
 * instruments as a customer reads them (§6). Over real HTTP with a logged-in session, the way the browser and the load
 * driver reach it, rather than a mocked security context. The screens' script is checked by hand in a browser.
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.queue-load.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext // close while this class's container is still up; a cached context outlives it and stalls the next context switch
class CustomerUiIT {
    private static final Pattern FORM_CSRF   = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern META_CSRF   = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
    private static final Pattern META_HEADER = Pattern.compile("<meta name=\"_csrf_header\" content=\"([^\"]+)\"");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("trading-demo-customer-ui-it-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void a_customer_logs_in_lands_on_the_ui_and_sees_every_page_with_a_csrf_token() throws Exception {
        var alice = Browser.loggedIn(base(), "alice@demo.local", "alice-demo-password");

        var root = alice.get("/");
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location").orElseThrow()).endsWith("/app");

        var home = alice.get("/app");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("data-user-id=\"USR-ALICE\"").containsPattern(META_CSRF).containsPattern(META_HEADER)
                               .contains("/customer_ui/home.js");

        for (var page : List.of("portfolio", "trade", "funding")) {
            var answer = alice.get("/app/accounts/ACC-ANY/" + page);
            assertThat(answer.statusCode()).as(page).isEqualTo(200);
            assertThat(answer.body()).as(page).contains("data-account-id=\"ACC-ANY\"").containsPattern(META_CSRF).contains("/customer_ui/" + page + ".js");
        }
        assertThat(alice.get("/app/accounts/ACC-ANY/settings").statusCode()).isEqualTo(404);
        assertThat(alice.get("/app/accounts/%3Cscript%3E/portfolio").statusCode()).isIn(400, 404);

        assertThat(alice.get("/customer_ui/api.js").statusCode()).isEqualTo(200);
        assertThat(alice.get("/customer_ui/customer.css").statusCode()).isEqualTo(200);
    }

    @Test
    void without_a_session_a_page_redirects_to_the_login_and_the_api_answers_401() throws Exception {
        var anonymous = new Browser(base());
        var page = anonymous.get("/app");
        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(page.headers().firstValue("Location").orElseThrow()).endsWith("/login");
        assertThat(anonymous.get("/api/accounts").statusCode()).isEqualTo(401);
    }

    @Test
    void a_customer_is_refused_the_operator_pages_and_api() throws Exception {
        var alice = Browser.loggedIn(base(), "alice@demo.local", "alice-demo-password");
        assertThat(alice.get("/essentials/admin").statusCode()).isEqualTo(403);
        assertThat(alice.get("/api/admin/users").statusCode()).isEqualTo(403);
    }

    @Test
    void a_session_write_to_the_customer_api_needs_the_csrf_token_and_a_basic_one_does_not() throws Exception {
        var bob = Browser.loggedIn(base(), "bob@demo.local", "bob-demo-password");

        var refused = bob.postJson("/api/accounts", openAccount("ACC-UI-NO-TOKEN"), false);
        assertThat(refused.statusCode()).isEqualTo(403);

        var opened = bob.postJson("/api/accounts", openAccount("ACC-UI-TOKEN"), true);
        assertThat(opened.statusCode()).isEqualTo(201);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(bob.get("/api/accounts/ACC-UI-TOKEN").statusCode()).isEqualTo(200));

        var basic = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/accounts"))
                                                               .header("Authorization", basic("bob@demo.local", "bob-demo-password"))
                                                               .header("Content-Type", "application/json")
                                                               .POST(HttpRequest.BodyPublishers.ofString(openAccount("ACC-UI-BASIC")))
                                                               .build(),
                                                    HttpResponse.BodyHandlers.ofString());
        assertThat(basic.statusCode()).isEqualTo(201);
    }

    @Test
    void a_customer_reads_the_instruments_beside_the_quotes() throws Exception {
        // Without the simulators profile, instruments come only from the operator
        var registered = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base() + "/api/admin/instruments"))
                                                                    .header("Authorization", basic("operator@demo.local", "operator-demo-password"))
                                                                    .header("Content-Type", "application/json")
                                                                    .POST(HttpRequest.BodyPublishers.ofString("{\"instrumentId\":\"UI-TEST\",\"symbol\":\"UIT\",\"displayName\":\"UI Test Corp\"}"))
                                                                    .build(),
                                                         HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).as(registered.body()).isBetween(200, 299);

        var alice = Browser.loggedIn(base(), "alice@demo.local", "alice-demo-password");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var instruments = alice.get("/api/market-data/instruments");
            assertThat(instruments.statusCode()).isEqualTo(200);
            assertThat(instruments.body()).contains("\"instrumentId\":\"UI-TEST\"").contains("\"displayName\":\"UI Test Corp\"").contains("\"suspended\":false");
        });
        assertThat(alice.get("/api/market-data/instruments/UI-TEST").statusCode()).isEqualTo(200);
        assertThat(alice.get("/api/market-data/instruments/NO-SUCH-INSTRUMENT").statusCode()).isEqualTo(404);
        // The operator's path is unchanged, and still the operator's
        assertThat(alice.get("/api/admin/instruments").statusCode()).isEqualTo(403);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String openAccount(String accountId) {
        return "{\"accountId\":\"" + accountId + "\",\"ownerId\":\"USR-BOB\",\"currency\":\"USD\"}";
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A browser's view of the application: a cookie jar, no redirects followed, and the CSRF token of the last page.
     */
    private static final class Browser {
        private final String     base;
        private final HttpClient client;
        private String           csrfHeader;
        private String           csrfToken;

        Browser(String base) {
            this.base = base;
            this.client = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        static Browser loggedIn(String base, String email, String password) throws IOException, InterruptedException {
            var browser = new Browser(base);
            var form = FORM_CSRF.matcher(browser.get("/login").body());
            assertThat(form.find()).as("the login form's CSRF field").isTrue();
            var answer = browser.client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                                        .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                                                                                                  + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                                                                                                  + "&_csrf=" + URLEncoder.encode(form.group(1), StandardCharsets.UTF_8)))
                                                        .build(),
                                             HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode()).isEqualTo(302);
            assertThat(answer.headers().firstValue("Location").orElse("")).doesNotContain("error");
            var page = browser.get("/app").body();
            var token = META_CSRF.matcher(page);
            var header = META_HEADER.matcher(page);
            assertThat(token.find() && header.find()).as("the page's CSRF meta tags").isTrue();
            browser.csrfToken = token.group(1);
            browser.csrfHeader = header.group(1);
            return browser;
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            // A browser asks for HTML; without it Spring Security answers as to an API client (401, not a redirect)
            var accept = path.startsWith("/api/") ? "application/json" : "text/html,application/xhtml+xml,*/*;q=0.8";
            return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String json, boolean withToken) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
            if (withToken) {
                request.header(csrfHeader, csrfToken);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
