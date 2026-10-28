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

package dk.trustworks.essentials.components.foundation.postgresql;

import java.util.Arrays;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides, per channel, whether {@link MultiTableChangeListener} filters duplicate notifications on it.<br>
 * Duplicates are only ever detected among the notifications that arrive on the <b>same</b> channel in one poll, and only
 * when the listener has duplicate filtering enabled ({@link MultiTableChangeListenerBuilder#setFilterDuplicateNotifications(boolean)})
 * and at least one {@link NotificationDuplicationFilter} is registered. This policy narrows that further: a channel it
 * rejects is never de-duplicated, so every notification on it is published.
 *
 * @see MultiTableChangeListenerBuilder#setChannelDeduplicationPolicy(ChannelDeduplicationPolicy)
 */
@FunctionalInterface
public interface ChannelDeduplicationPolicy {
    /**
     * @param channelName the channel the notifications arrived on: the table name as resolved by
     *                    {@link ListenNotify#resolveTableChangeChannelName(String)}, i.e. in lower case
     * @return true if duplicate notifications on this channel are filtered
     */
    boolean isDeduplicated(String channelName);

    /**
     * The default: every channel is de-duplicated
     *
     * @return a policy that de-duplicates every channel
     */
    static ChannelDeduplicationPolicy allChannels() {
        return channelName -> true;
    }

    /**
     * De-duplicate only the given tables' channels; notifications on every other channel are always published
     *
     * @param tableNames the tables whose channels are de-duplicated (in any case - they are resolved with
     *                   {@link ListenNotify#resolveTableChangeChannelName(String)})
     * @return a policy that de-duplicates only the given tables' channels
     */
    static ChannelDeduplicationPolicy onlyTables(String... tableNames) {
        requireNonNull(tableNames, "No tableNames provided");
        var channels = Arrays.stream(tableNames)
                             .map(ListenNotify::resolveTableChangeChannelName)
                             .collect(Collectors.toUnmodifiableSet());
        return channels::contains;
    }
}
