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

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The answer of {@link MessageQueue#releaseKeptOrderedUnit}: which instance ids answered, and what each subscription
 * running under the requested one did with the unit.
 *
 * @param answeredBy the instance ids of the queue in the process that answered - the queue's own, then each of the
 *                   subscriptions it asked that runs under another - so an answer with no releases still names the
 *                   process that gave it; empty for an implementation that keeps no units
 * @param releases   what was done, one entry per subscription running under the requested instance id that had kept
 *                   the unit; empty when none had
 */
public record KeptUnitReleases(List<String> answeredBy, List<KeptUnitRelease> releases) {

    /**
     * The answer of an implementation that keeps no units: no instance id, nothing released.
     */
    public static final KeptUnitReleases NONE = new KeptUnitReleases(List.of(), List.of());

    public KeptUnitReleases {
        answeredBy = List.copyOf(requireNonNull(answeredBy, "No answeredBy provided"));
        releases = List.copyOf(requireNonNull(releases, "No releases provided"));
    }
}
