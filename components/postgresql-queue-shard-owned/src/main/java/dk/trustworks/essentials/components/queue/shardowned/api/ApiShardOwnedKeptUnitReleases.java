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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The answer of {@link ShardOwnedQueuesApi#releaseKeptOrderedUnit}: which process answered, and what it did - see
 * {@link KeptUnitReleases}.
 *
 * @param answeredBy the instance ids the queue has in the process that answered - the queue's own, then each of its
 *                   subscriptions' that differs from it - so an answer with no releases still names the process that
 *                   gave it; empty when no queue is registered under that name, or the queue's implementation keeps no
 *                   units
 * @param releases   what was done, one entry per subscription of the queue in that process running under the requested
 *                   instance id that had kept the unit; empty when none had
 */
public record ApiShardOwnedKeptUnitReleases(List<String> answeredBy,
                                            List<ApiShardOwnedKeptUnitRelease> releases) {

    /**
     * The answer when no queue is registered under the name asked for, and the answer of an implementation that keeps no
     * units (the {@link ShardOwnedQueuesApi} default): no instance id, nothing released.
     */
    public static final ApiShardOwnedKeptUnitReleases NONE = new ApiShardOwnedKeptUnitReleases(List.of(), List.of());

    /**
     * Keeps unmodifiable copies of both lists, so neither the lists given here nor the ones the accessors return can
     * change this record; {@code null} becomes an empty list.
     */
    public ApiShardOwnedKeptUnitReleases {
        answeredBy = answeredBy == null ? List.of() : List.copyOf(answeredBy);
        releases = releases == null ? List.of() : List.copyOf(releases);
    }

    public static ApiShardOwnedKeptUnitReleases from(QueueName queueName, KeptUnitReleases answer) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(answer, "No answer provided");
        return new ApiShardOwnedKeptUnitReleases(answer.answeredBy(),
                                                 answer.releases().stream()
                                                       .map(release -> ApiShardOwnedKeptUnitRelease.from(queueName, release))
                                                       .toList());
    }
}
