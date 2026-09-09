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

package dk.trustworks.essentials.components.queue.shardowned.spi.operations;

import dk.trustworks.essentials.components.queue.shardowned.spi.Message;

import java.sql.Connection;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A batch on its way to the queue, before anything has been written.
 * <p>
 * The message list is mutable through {@link #setMessages(List)} so an interceptor can enrich, filter
 * or replace it — adding a correlation id, stamping a tenant, dropping what a feature flag disables.
 * That is the difference from an observer, which is told what happened and cannot change it.
 *
 * @param connection the caller's connection when the enqueue is joining their transaction, otherwise
 *                   empty. An interceptor writing its own rows should use it, or its writes will not
 *                   share the caller's commit
 */
public final class EnqueueMessages {
    private List<Message>          messages;
    private final Optional<Connection> connection;

    public EnqueueMessages(List<Message> messages, Connection connection) {
        this.messages = List.copyOf(requireNonNull(messages, "No messages provided"));
        this.connection = Optional.ofNullable(connection);
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = List.copyOf(requireNonNull(messages, "No messages provided"));
    }

    public Optional<Connection> getConnection() {
        return connection;
    }

    @Override
    public String toString() {
        return "EnqueueMessages{messages=" + messages.size() + ", transactional=" + connection.isPresent() + "}";
    }
}
