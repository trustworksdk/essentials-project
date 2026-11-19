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

import java.sql.*;
import java.util.*;

/**
 * The engine's admin operations, reached through
 * {@link EventLog#admin()}. Every operation works from the database, so it can be called on any node, whether or not
 * that node runs the subscription. Each call borrows one connection from the event log's {@code DataSource} (except
 * {@link #reset(ProgressKey, long, Connection)} and {@link #importProgress}, which run on the caller's) and runs one to three statements.
 */
public interface EventLogAdmin {
    /**
     * Every progress row of every node, at most {@link EventLogSettings#adminListLimit()}, in one statement
     *
     * @param aggregateType only this type's rows, if given
     */
    List<SubscriptionRow> subscriptions(Optional<String> aggregateType) throws SQLException;

    Optional<SubscriptionRow> subscription(ProgressKey key) throws SQLException;

    /** The subscription's parked rows with {@code global_order > afterGlobalOrder}, at most {@code limit}, in order */
    ParkedPage parked(ProgressKey key, long afterGlobalOrder, int limit) throws SQLException;

    /**
     * Reset the subscription to {@code resumeFrom}: bump its reset epoch and delete its parked rows (requests
     * included) in one transaction. The lease columns are not touched. A subscription running anywhere notices at its next
     * save or page and repositions.
     *
     * @return {@code false} if there is no such row (none is created)
     */
    boolean reset(ProgressKey key, long resumeFrom) throws SQLException;

    /**
     * {@link #reset(ProgressKey, long)} on the caller's transaction, which is neither committed nor rolled back here: a
     * {@code CONTIGUOUS} rebuild truncates its view and resets in one commit, so the view matches its resume point at
     * every point. Must be a connection to this event log's database.
     */
    boolean reset(ProgressKey key, long resumeFrom, Connection connection) throws SQLException;

    /**
     * Retry the parked head at {@code head}: the request is stored on the head's row; if this node runs the
     * subscription and owns it, it serves it at once ({@link ParkedRequestResult#DONE} or {@link ParkedRequestResult#FAILED}
     * if it did so within the parked-check interval), else the node that runs it serves it at its next parked check
     * ({@link ParkedRequestResult#ACCEPTED}). A request waits until some node runs the subscription; a reset deletes it.
     */
    ParkedRequestResult retryParked(ProgressKey key, long head) throws SQLException;

    /** As {@link #retryParked}, but the head is deleted without being handled; the events held behind it are applied */
    ParkedRequestResult discardParked(ProgressKey key, long head) throws SQLException;

    /**
     * Delete the row of a retired instance and its parked rows: only a per-instance (non-exclusive) row,
     * never one this event log runs. An instance that runs on another node finds its row gone at its next save.
     *
     * @return whether a row was deleted
     */
    boolean deleteRetiredInstance(ProgressKey key) throws SQLException;

    /**
     * Write an imported resume point, for the migration
     * from the old event store: insert the progress row of {@code definition} under {@code instanceName} with the
     * definition's consistency mode, exclusive flag and filter digest, {@code model}, {@code resume_from = resumeFrom},
     * reset epoch 0 and no lease, so the first claim takes it. Runs on the caller's transaction, which is neither committed
     * nor rolled back here; must be a connection to this event log's database.
     * <p>
     * An existing row is left as it is, and its resume point is returned: the caller compares it with
     * {@code resumeFrom} to tell an idempotent rerun from a conflicting one. Because the row exists, the subscription's
     * {@link SubscriptionDefinition#startFrom()} is not consulted when it first starts.
     *
     * @param definition   the subscription the row belongs to
     * @param model        the progress model of the handler it will run with: {@link ProgressModel#PERIODIC} for a
     *                     {@link PeriodicHandler}, {@link ProgressModel#IN_HANDLER_TRANSACTION} for a {@link PageHandler}.
     *                     A wrong one is corrected, with a warning, at the first start
     * @param instanceName the instance whose row is written: {@code ""} for an exclusive definition, the non-blank name
     *                     of one instance that will run a per-instance definition (each instance needs its own call)
     * @param resumeFrom   the first global order to deliver, {@code >= 1}
     * @return the row's resume point: {@code resumeFrom} if the row was inserted, the stored one if it existed
     */
    long importProgress(SubscriptionDefinition definition, ProgressModel model, String instanceName, long resumeFrom, Connection connection) throws SQLException;
}
