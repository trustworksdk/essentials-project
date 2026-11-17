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
package dk.trustworks.essentials.components.eventlog;

/**
 * Thrown by a handler to park an event at once, without retries (design {@code docs/plans/event-store-engine-phase4.md}
 * §4.4; plan §19 item 21). The parked event is recorded in {@code el_parked_events} and the subscription moves past it;
 * the later events of its aggregate are held behind it (parked as blocked by it) until it is retried or discarded (§4.5).
 * Every other aggregate continues.
 * <p>
 * Thrown from a page of several events, it must name one of them: the page's events before it then commit in a page of
 * their own and the named event is parked in the next page transaction. Thrown without an event, or naming one that is
 * not in the page, from a page of several events, it counts as an ordinary handler failure, so the page shrinks until the
 * failing event is alone, where it parks at once. A per-event handler may always throw it without an event.
 * <p>
 * Phase 8 keeps it as the way a handler parks an event at once (design phase 8 §2.3).
 */
public final class ParkEventException extends RuntimeException {
    private final transient StoredEvent event;

    /**
     * @param event  the event to park, or {@code null} for the event being handled alone
     * @param reason why, recorded as the parked row's error message
     * @param cause  what made the handler give up on it, or {@code null}
     */
    public ParkEventException(StoredEvent event, String reason, Throwable cause) {
        super(reason, cause);
        this.event = event;
    }

    /**
     * @param event  the event to park, or {@code null} for the event being handled alone
     * @param reason why, recorded as the parked row's error message
     */
    public ParkEventException(StoredEvent event, String reason) {
        this(event, reason, null);
    }

    /**
     * Park the event being handled alone
     *
     * @param reason why, recorded as the parked row's error message
     */
    public ParkEventException(String reason) {
        this(null, reason, null);
    }

    /**
     * @return the event to park, or {@code null} if the handler did not name one
     */
    public StoredEvent event() {
        return event;
    }
}
