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
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> exchangeAsTheDemoRunsIt() {
        return exchange().withCopyFileToContainer(MountableFile.forHostPath("simulators/exchange.yaml"), "/etc/exchange/exchange.yaml")
                         .withEnv("EXCHANGE_CONFIG", "/etc/exchange/exchange.yaml");
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
