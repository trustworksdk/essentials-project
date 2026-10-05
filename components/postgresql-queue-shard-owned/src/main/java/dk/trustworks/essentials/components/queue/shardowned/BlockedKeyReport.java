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

package dk.trustworks.essentials.components.queue.shardowned;

import org.slf4j.*;

import java.util.concurrent.TimeUnit;

/**
 * Reports an ordered key that has stopped behind a dead letter.
 * <p>
 * A blocked key is a state an operator has to act on - nothing for it is delivered again until the
 * dead letter is resurrected or deleted - and it is the only log signal there is: the messages behind
 * it are moved out of the queue, so depth falls rather than rises. Each key is reported once by the
 * owner that blocked it.
 * <p>
 * Once per key is still one line per key, and the case that blocks keys is rarely one bad message. A
 * handler failing for everything - a broken dependency, or the tracing interceptor that threw for
 * every shard-owned delivery - blocks every key that has traffic, and the trading demo then logged a
 * WARN for every new key on every queue, without end. So the first block is logged in full, and after
 * that at most once a minute per consumer, carrying a count of the keys blocked since. Every key is
 * still logged at DEBUG, and the dead-letter listing and the {@code blockedKeys} depth name them all.
 * <p>
 * Logged under {@link OrderedShardOwner}'s logger, where this line has always been, so existing log
 * configuration keeps applying to it.
 */
final class BlockedKeyReport {
    private static final Logger log              = LoggerFactory.getLogger(OrderedShardOwner.class);
    private static final long   WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private BlockedKeyReport() {
    }

    static void keyBlocked(ShardOwnerMetrics metrics, short queueId, int shard, String key, long keyOrder) {
        metrics.blockedKeysSinceLastWarning.increment();
        var now  = System.nanoTime();
        var next = metrics.nextBlockedKeyWarningNanos.get();
        if (now - next >= 0 && metrics.nextBlockedKeyWarningNanos.compareAndSet(next, now + WARN_EVERY_NANOS)) {
            // Includes this key, so one less is "others".
            var others = metrics.blockedKeysSinceLastWarning.sumThenReset() - 1;
            log.warn("Queue {}, ordered shard {}: key '{}' is blocked at key_order {} — its message was dead-lettered, so "
                             + "nothing above that order will be delivered and anything arriving for it is dead-lettered "
                             + "too. Resurrect or delete the dead letter to release the key. {} other key(s) were blocked "
                             + "since the last of these warnings; this is logged at most once a minute, every key at DEBUG",
                     queueId, shard, key, keyOrder, others);
        } else {
            log.debug("Queue {}, ordered shard {}: key '{}' is blocked at key_order {} — its message was dead-lettered",
                      queueId, shard, key, keyOrder);
        }
    }
}
