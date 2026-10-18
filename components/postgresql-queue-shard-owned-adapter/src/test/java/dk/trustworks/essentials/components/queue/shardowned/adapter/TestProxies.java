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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import java.lang.reflect.Proxy;

/**
 * Hand-rolled stand-ins for the shard-owned SPI interfaces ({@code MessageQueues}, {@code MessageQueue},
 * {@code Subscription}) the adapter's unit tests drive: one {@link Invocation} answers every method but
 * {@code toString}, {@code hashCode} and {@code equals}, which the proxy answers by identity.
 */
final class TestProxies {
    private TestProxies() {
    }

    /**
     * Answers one call on a {@link #proxy(Class, Invocation) proxy}: the method's name and its arguments
     * ({@code null} for a method without parameters, as {@link java.lang.reflect.InvocationHandler} passes them).
     */
    @FunctionalInterface
    interface Invocation {
        Object handle(String method, Object... args);
    }

    // type's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees its
    // interface, which the context loader need not. equals is identity: calling self.equals would re-enter this handler.
    @SuppressWarnings({"PMD.UseProperClassLoader", "PMD.CompareObjectsWithEquals"})
    static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> switch (method.getName()) {
            case "toString" -> type.getSimpleName() + " proxy";
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> invocation.handle(method.getName(), args);
        }));
    }
}
