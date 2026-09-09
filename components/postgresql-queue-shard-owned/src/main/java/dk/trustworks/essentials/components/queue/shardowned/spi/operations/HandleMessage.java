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

/**
 * One message about to be handed to a {@code MessageHandler}.
 * <p>
 * This is the hot path — it runs once per delivered message — which is why the chain around it is
 * skipped entirely when no interceptor is registered rather than being built and walked for nothing.
 * <p>
 * An interceptor that does not call {@code proceed()} <b>skips the handler</b>, and the message is
 * then acknowledged as handled. That is a real capability (a poison-message filter, a kill switch for
 * one payload type) and a real footgun, so it is stated rather than left to be discovered: silently
 * dropping a message and reporting success is indistinguishable from having processed it.
 *
 * @param key         the ordering key for an ordered message, null otherwise
 * @param payload     the bytes as stored
 * @param payloadType the application's discriminator; opaque to the engine
 */
public record HandleMessage(String key, byte[] payload, int payloadType) {
}
