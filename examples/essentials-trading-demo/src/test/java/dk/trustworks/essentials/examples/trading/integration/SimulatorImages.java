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

package dk.trustworks.essentials.examples.trading.integration;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.*;

import java.time.Duration;

/**
 * The simulators as test containers. Their images are built locally from the simulators' own repository and are not
 * pulled from any registry, so a test using them is gated on {@link #exchangeAvailable()} and skips where the image has
 * not been built — CI included, until the images are published somewhere.
 * <p>
 * {@code :dev} is the tag the simulators' own guides build; it is local, so it does not float underneath the suite the
 * way a registry tag would. Pin a versioned tag here once one exists.
 */
public final class SimulatorImages {
    public static final DockerImageName EXCHANGE        = DockerImageName.parse("exchange-simulator:dev");
    public static final DockerImageName PAYMENT_GATEWAY = DockerImageName.parse("payment-gateway:dev");

    private SimulatorImages() {
    }

    /**
     * An exchange with test control on and in-memory persistence: a fresh, throwaway exchange per test class.
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> exchange() {
        return new GenericContainer<>(EXCHANGE)
                .withExposedPorts(8080)
                .withEnv("EXCHANGE__TEST_CONTROL__ENABLED", "true")
                .withEnv("EXCHANGE__DATABASE__MODE", "memory")
                .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * {@link #exchange()} with the demo's own configuration ({@code simulators/exchange.yaml}): its instruments, synthetic
     * reference prices and the liquidity provider quoting them, so orders trade.
     * <p>
     * Without the configuration's trading sessions: the tests start the clock before the open and assume an always-open
     * market, where a DAY order rests until the business day's cutoff. {@link #exchangeWithTradingSessions()} keeps them.
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> exchangeAsTheDemoRunsIt() {
        return exchangeWithConfiguration(MountableFile.forHostPath(withoutTradingSessions()));
    }

    /**
     * {@link #exchange()} with the demo's own configuration exactly as it is, trading sessions included.
     */
    public static GenericContainer<?> exchangeWithTradingSessions() {
        return exchangeWithConfiguration(MountableFile.forHostPath("simulators/exchange.yaml"));
    }

    /**
     * {@code simulators/exchange.yaml} without its top-level {@code sessions:} block, written to a temporary file.
     */
    static java.nio.file.Path withoutTradingSessions() {
        try {
            var kept      = new java.util.ArrayList<String>();
            var inSession = false;
            for (var line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("simulators/exchange.yaml"))) {
                if (line.startsWith("sessions:")) {
                    inSession = true;
                    continue;
                }
                if (inSession && (line.startsWith(" ") || line.isBlank())) {
                    continue;
                }
                inSession = false;
                kept.add(line);
            }
            var file = java.nio.file.Files.createTempFile("exchange-without-sessions", ".yaml");
            file.toFile().deleteOnExit();
            java.nio.file.Files.write(file, kept);
            // createTempFile makes it owner-only; the exchange runs as another user in its container
            file.toFile().setReadable(true, false);
            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> exchangeWithConfiguration(MountableFile configuration) {
        return exchange().withCopyFileToContainer(configuration, "/etc/exchange/exchange.yaml")
                         // The secret its webhook subscriptions sign with, where compose puts it
                         .withCopyFileToContainer(MountableFile.forHostPath("simulators/secrets/webhook-secret.txt"), "/run/secrets/webhook_secret")
                         .withEnv("EXCHANGE_CONFIG", "/etc/exchange/exchange.yaml");
    }

    /**
     * The payment gateway with the demo's own configuration ({@code simulators/gateway.yaml}), test control on and
     * in-memory persistence, on a virtual clock of its own, frozen: the demo's configuration follows the exchange's
     * clock and calendar, which a test of the gateway alone does not have. A test moves it on to make due payments happen - a forced
     * outcome (SR-22) still waits for the payment's delay.
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> paymentGatewayAsTheDemoRunsIt() {
        return new GenericContainer<>(PAYMENT_GATEWAY)
                .withExposedPorts(8081)
                .withCopyFileToContainer(MountableFile.forHostPath("simulators/gateway.yaml"), "/etc/gateway/gateway.yaml")
                .withCopyFileToContainer(MountableFile.forHostPath("simulators/secrets/webhook-secret.txt"), "/run/secrets/webhook_secret")
                .withEnv("GATEWAY_CONFIG", "/etc/gateway/gateway.yaml")
                .withEnv("GATEWAY__CLOCK__MODE", "virtual")
                .withEnv("GATEWAY__CLOCK__RUNNING", "false")
                .withEnv("GATEWAY__CALENDAR__MODE", "own")
                .withEnv("GATEWAY__TEST_CONTROL__ENABLED", "true")
                .withEnv("GATEWAY__DATABASE__MODE", "memory")
                .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * For JUnit's {@code @EnabledIf}: whether the payment gateway image exists locally.
     */
    public static boolean paymentGatewayAvailable() {
        return imageExists(PAYMENT_GATEWAY);
    }

    /**
     * For JUnit's {@code @EnabledIf}: whether the exchange image exists locally.
     */
    public static boolean exchangeAvailable() {
        return imageExists(EXCHANGE);
    }

    private static boolean imageExists(DockerImageName image) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image.asCanonicalNameString()).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
