/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.fencedlock;

import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public final class LockCallbackBuilder {
    private Consumer<FencedLock> onLockAcquired;
    private Consumer<FencedLock> onLockReleased;

    public LockCallbackBuilder onLockAcquired(Consumer<FencedLock> onLockAcquired) {
        this.onLockAcquired = requireNonNull(onLockAcquired, "No lockAcquired consumer provided");
        return this;
    }

    public LockCallbackBuilder onLockReleased(Consumer<FencedLock> onLockReleased) {
        this.onLockReleased = requireNonNull(onLockReleased, "No onLockReleased consumer provided");
        return this;
    }

    public LockCallback build() {
        return LockCallback.lockCallback(onLockAcquired,
                                         onLockReleased);
    }
}
