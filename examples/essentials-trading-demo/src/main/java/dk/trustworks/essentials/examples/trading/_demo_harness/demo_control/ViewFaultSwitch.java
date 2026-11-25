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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import dk.trustworks.essentials.components.eventlog.ParkEventException;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The operator page's view fault (demo harness only): makes the orders view's event-log handler fail on every
 * {@code OrderPlaced}, so the operator can show how the event-log engine parks an order and gets it going again.
 * <ul>
 *     <li>{@link Mode#RETRYING} throws an ordinary exception, as a database or downstream outage would: the engine parks
 *     the order and retries it by itself (1 s, doubling, at most 5 min apart) - it heals on its own once the fault is
 *     switched off.</li>
 *     <li>{@link Mode#TERMINAL} throws {@link ParkEventException}, a failure no retry can fix: the order stays parked
 *     until an operator retries or discards it in the Essentials admin console.</li>
 * </ul>
 * Only orders placed while the fault is on are affected; every other order and view keeps running. Has no effect on
 * the classic engine, whose orders view does not consult it.
 */
@Component
public class ViewFaultSwitch {
    private static final Logger log = LoggerFactory.getLogger(ViewFaultSwitch.class);

    public enum Mode {
        OFF,
        RETRYING,
        TERMINAL
    }

    public record Status(Mode mode, long failuresThrown) {
    }

    private volatile Mode mode = Mode.OFF;
    private volatile long failuresThrown;

    public Status status() {
        return new Status(mode, failuresThrown);
    }

    public Status set(Mode mode) {
        this.mode = requireNonNull(mode, "No mode provided");
        log.warn("Demo view fault set to {}", mode);
        return status();
    }

    /**
     * Called by a view's handler before it writes: throws according to the current mode
     *
     * @param view what the handler projects, for the error message
     */
    public void check(String view) {
        var current = mode;
        if (current == Mode.OFF) {
            return;
        }
        failuresThrown++;
        if (current == Mode.TERMINAL) {
            throw new ParkEventException("Demo fault (terminal) in " + view + ": switched on on the operator page");
        }
        throw new IllegalStateException("Demo fault (retrying) in " + view + ": switched on on the operator page");
    }
}
