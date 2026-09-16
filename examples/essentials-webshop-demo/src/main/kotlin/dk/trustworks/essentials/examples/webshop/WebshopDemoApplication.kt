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

package dk.trustworks.essentials.examples.webshop

import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

/**
 * Entry point for the sales demo: the event model from the "Simplifying with Event Modeling, Event Sourcing and
 * CQRS" module, implemented as Decider/Evolver slices on `kotlin-eventsourcing`.
 *
 * The bounded contexts own their own configuration - see `sales/config`, `shipping/config` and
 * `payment/config`. What lives here is the application-level infrastructure that belongs to no context.
 */
@SpringBootApplication
class WebshopDemoApplication {

    /**
     * Demo-only security: every caller is authenticated as the same principal and authorized for everything, which
     * is what makes the admin console usable without wiring an identity provider into a sample application. The
     * admin API authenticates nobody itself - it asks these two beans - so without them every request answers 401.
     *
     * Never do this in a real application: it authorizes destructive admin operations for anonymous callers.
     */
    @Bean
    fun essentialsAuthenticatedUser(): EssentialsAuthenticatedUser =
        EssentialsAuthenticatedUser.AllAccessAuthenticatedUser()

    @Bean
    fun essentialsSecurityProvider(): EssentialsSecurityProvider =
        EssentialsSecurityProvider.AllAccessSecurityProvider()
}

fun main(args: Array<String>) {
    SpringApplication.run(WebshopDemoApplication::class.java, *args)
}
