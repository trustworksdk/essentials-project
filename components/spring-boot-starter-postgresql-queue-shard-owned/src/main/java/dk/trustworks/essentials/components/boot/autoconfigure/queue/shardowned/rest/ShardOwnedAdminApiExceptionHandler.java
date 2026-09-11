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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest;

import dk.trustworks.essentials.components.adminapi.rest.AdminApiExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Applies the admin API's error mapping to this starter's controller.
 * <p>
 * {@code AdminApiExceptionHandler} is annotated
 * {@code @RestControllerAdvice(basePackageClasses = AdminApiPaths.class)}, which scopes it to the
 * admin API starter's own {@code rest} package. {@link ShardOwnedQueuesController} lives here
 * instead, so none of that mapping reached it: a message that had already been delivered produced
 * {@code AdminApiResourceNotFoundException} and the client saw <b>500</b> rather than 404, and an
 * authorization failure would have been a 500 rather than a 403.
 * <p>
 * It extends rather than reimplements, so there is exactly one definition of how an exception
 * becomes a response and this class cannot drift from it. All it changes is the scope.
 * <p>
 * The alternative is moving the controller into {@code spring-boot-starter-admin-api}, which is
 * where the convention says an admin operation's controller belongs and which publishing the engine
 * has now made possible. That is a larger change: it gives the admin API starter a hard dependency
 * on this engine, so an application that does not use the engine would carry its controller anyway.
 */
@RestControllerAdvice(basePackageClasses = ShardOwnedQueuesController.class)
public class ShardOwnedAdminApiExceptionHandler extends AdminApiExceptionHandler {
}
