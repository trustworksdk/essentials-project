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

package dk.trustworks.essentials.components.queue.shardowned.spi;

/**
 * A queue operation failed against the database.
 * <p>
 * {@link MessageQueue} throws {@link java.sql.SQLException} because its callers are usually already
 * inside a transaction and have to decide what a failure means for it. A caller reached over HTTP has
 * no such decision to make and no transaction to roll back, so the API layer converts. This exists so
 * that conversion lands on a type an exception handler can map to a status code, rather than on a bare
 * {@link RuntimeException} that is indistinguishable from a bug.
 */
public class MessageQueueException extends RuntimeException {

    public MessageQueueException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessageQueueException(String message) {
        super(message);
    }
}
