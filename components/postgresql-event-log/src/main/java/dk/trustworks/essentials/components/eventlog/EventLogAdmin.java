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
 * The engine's admin operations (design {@code docs/plans/event-store-engine-phase8.md} §4), reached through
 * {@link EventLog#admin()}. Every operation works from the database, so it can be called on any node, whether or not
 * that node runs the subscription. Each call borrows one connection from the event log's {@code DataSource} (except
 * {@link #reset(ProgressKey, long, Connection)}) and runs one to three statements.
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
     * Reset the subscription to {@code resumeFrom} (§4.2): bump its reset epoch and delete its parked rows (requests
     * included) in one transaction. The lease columns are not touched. A subscription running anywhere notices at its next
     * save or page and repositions.
     *
     * @return {@code false} if there is no such row (none is created)
     */
    boolean reset(ProgressKey key, long resumeFrom) throws SQLException;

    /**
     * {@link #reset(ProgressKey, long)} on the caller's transaction, which is neither committed nor rolled back here: a
     * {@code CONTIGUOUS} rebuild truncates its view and resets in one commit, so the view matches its resume point at
     * every point (§4.2, §10.3). Must be a connection to this event log's database.
     */
    boolean reset(ProgressKey key, long resumeFrom, Connection connection) throws SQLException;

    /**
     * Retry the parked head at {@code head} (§4.3): the request is stored on the head's row; if this node runs the
     * subscription and owns it, it serves it at once ({@link ParkedRequestResult#DONE} or {@link ParkedRequestResult#FAILED}
     * if it did so within the parked-check interval), else the node that runs it serves it at its next parked check
     * ({@link ParkedRequestResult#ACCEPTED}). A request waits until some node runs the subscription; a reset deletes it.
     */
    ParkedRequestResult retryParked(ProgressKey key, long head) throws SQLException;

    /** As {@link #retryParked}, but the head is deleted without being handled; the events held behind it are applied */
    ParkedRequestResult discardParked(ProgressKey key, long head) throws SQLException;

    /**
     * Delete the row of a retired instance and its parked rows (plan §19 item 17): only a per-instance (non-exclusive) row,
     * never one this event log runs. An instance that runs on another node finds its row gone at its next save.
     *
     * @return whether a row was deleted
     */
    boolean deleteRetiredInstance(ProgressKey key) throws SQLException;
}
