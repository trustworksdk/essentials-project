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


package dk.trustworks.essentials.components.foundation.scheduler;

/**
 * Thrown by {@link EssentialsScheduler#runJobNow(String)} for an executor job when this instance does not hold the
 * scheduler's fenced lock.
 * <p>
 * Executor jobs run only on the instance holding that lock - that is what keeps each of them running in one place.
 * Running one on demand anywhere else would break that, so the request has to reach the lock holder instead.
 */
public class ScheduledJobNotRunnableHereException extends RuntimeException {

    private final String lockHolderInstanceId;

    /**
     * @param jobName              the job that was asked to run
     * @param lockHolderInstanceId the instance holding the scheduler lock, or {@code null} if no instance holds it
     */
    public ScheduledJobNotRunnableHereException(String jobName, String lockHolderInstanceId) {
        super(lockHolderInstanceId != null
              ? "Executor job '" + jobName + "' can only be run on the instance holding the scheduler lock, which is '" + lockHolderInstanceId + "'"
              : "Executor job '" + jobName + "' can only be run on the instance holding the scheduler lock, and no instance holds it right now");
        this.lockHolderInstanceId = lockHolderInstanceId;
    }

    /**
     * @return the instance holding the scheduler lock, or {@code null} if no instance holds it
     */
    public String getLockHolderInstanceId() {
        return lockHolderInstanceId;
    }
}
