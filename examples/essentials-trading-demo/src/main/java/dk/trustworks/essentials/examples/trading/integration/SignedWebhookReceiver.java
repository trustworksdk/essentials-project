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

import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox;
import org.slf4j.*;
import org.springframework.http.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.function.Predicate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The whole receiving side of one webhook subscription, so each translation slice's endpoint is a single call. Follows
 * the simulators' receiver contract (their guide, "A correct receiver, in five rules"):
 * <ol>
 *     <li>signature verified over the raw body before parsing — {@code 401} otherwise;</li>
 *     <li>each {@code eventId} processed once, recorded with its effect — see {@link ExternalEventIntake};</li>
 *     <li>answers quickly: the event is queued, not processed, before answering;</li>
 *     <li>an event type this subscription will never process is answered {@code 204} and ignored, never retried into a
 *     dead letter;</li>
 *     <li>no ordering assumed — see {@link ExternalEventIntake}.</li>
 * </ol>
 * A damaged copy (fault injection: missing, null or invalid fields) is answered {@code 422} and recorded nowhere, so the
 * source retries with a fresh copy. Damaged means either of two things: a field the simulator's own schema requires is
 * missing or {@code null} ({@link SimulatorEventSchemas}) - provably not an event the simulator sent - or the slice's
 * validator finds a field it reads unusable. Without the first, a copy damaged in a field the slice does not read would
 * be recorded as the event and the intact copy dropped as a duplicate. If every retry is damaged the delivery is dead-lettered at the source, and
 * {@link ExternalEventLogCatchUp} reads the canonical event from the log instead.
 */
public final class SignedWebhookReceiver {
    private static final Logger log = LoggerFactory.getLogger(SignedWebhookReceiver.class);

    private final String                           consumer;
    private final Set<String>                      eventTypes;
    private final Predicate<ExternalEventEnvelope> validator;
    private final Inbox                            inbox;
    private final WebhookSignatureVerifier         verifier;
    private final SimulatorEventSchemas            schemas;
    private final ExternalEventIntake              intake;
    private final ObjectMapper                     objectMapper;

    public SignedWebhookReceiver(Settings settings, WebhookSignatureVerifier verifier, SimulatorEventSchemas schemas, ExternalEventIntake intake, ObjectMapper objectMapper) {
        requireNonNull(settings, "No settings provided");
        this.consumer = settings.consumer();
        this.eventTypes = settings.eventTypes();
        this.validator = settings.validator();
        this.inbox = settings.inbox();
        this.verifier = requireNonNull(verifier, "No verifier provided");
        this.schemas = requireNonNull(schemas, "No schemas provided");
        this.intake = requireNonNull(intake, "No intake provided");
        this.objectMapper = requireNonNull(objectMapper, "No objectMapper provided");
    }

    /**
     * @param consumer   the consuming slice's name, the deduplication scope
     * @param eventTypes the event types the subscription is declared for; anything else is acknowledged and ignored
     * @param validator  the slice's check that the payload carries what it needs, per event type
     * @param inbox      the slice's inbox
     */
    public record Settings(String consumer, Set<String> eventTypes, Predicate<ExternalEventEnvelope> validator, Inbox inbox) {
        public Settings {
            requireNonNull(consumer, "No consumer provided");
            requireNonNull(eventTypes, "No eventTypes provided");
            requireNonNull(validator, "No validator provided");
            requireNonNull(inbox, "No inbox provided");
            eventTypes = Set.copyOf(eventTypes);
        }
    }

    public ResponseEntity<Void> receive(String signatureTimestamp, String signature, byte[] rawBody) {
        if (!verifier.verify(signatureTimestamp, signature, rawBody)) {
            log.warn("[{}] Rejected a webhook delivery with an invalid or stale signature", consumer);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode              json;
        ExternalEventEnvelope envelope;
        try {
            json = objectMapper.readTree(rawBody);
            envelope = ExternalEventEnvelope.from(json).orElse(null);
        } catch (JacksonException e) {
            json = null;
            envelope = null;
        }
        if (envelope == null) {
            log.debug("[{}] Unreadable envelope, asking for a fresh copy", consumer);
            return ResponseEntity.status(HttpStatus.valueOf(422)).build();
        }
        if (!eventTypes.contains(envelope.eventType())) {
            log.debug("[{}] Ignoring event type '{}' this subscription does not process", consumer, envelope.eventType());
            return ResponseEntity.noContent().build();
        }
        var damage = schemas.damage(json);
        if (!damage.isEmpty()) {
            log.debug("[{}] '{}' event '{}' lacks what its schema requires {}, asking for a fresh copy", consumer, envelope.eventType(), envelope.eventId(), damage);
            return ResponseEntity.status(HttpStatus.valueOf(422)).build();
        }
        if (!validator.test(envelope)) {
            log.debug("[{}] Damaged '{}' payload for event '{}', asking for a fresh copy", consumer, envelope.eventType(), envelope.eventId());
            return ResponseEntity.status(HttpStatus.valueOf(422)).build();
        }
        log.debug("[{}] Received '{}' event '{}'", consumer, envelope.eventType(), envelope.eventId());
        var accepted = intake.accept(consumer, envelope, inbox);
        log.debug("[{}] {} '{}' event '{}'", consumer, accepted ? "Queued" : "Already had", envelope.eventType(), envelope.eventId());
        return ResponseEntity.noContent().build();
    }
}
