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

import javax.sql.DataSource;

/**
 * Makes the database refuse every release of a lease row on one lane, the way a database error would, and counts each
 * refusal per queue and unit - for the tests of what the engine does when a release fails.
 * <p>
 * A trigger rather than a revoked grant: the test's role owns the table, and an owner keeps every privilege. The counters
 * show the injection worked - that a release was attempted and refused, not merely that nothing was released. Per queue
 * as well as per unit, because unit numbers repeat across queues, and a test with two queues on one lane would otherwise
 * count one queue's refusals as the other's. They are sequences because the refusal rolls back its transaction, and a
 * {@code nextval} is the one write a rollback does not undo. The trigger runs once per statement, after its rows, so it
 * counts every unit of a release of several, not just the first it reached.
 */
final class LeaseReleaseRefusals {

    private LeaseReleaseRefusals() {
    }

    /**
     * Refuse every release of a lease row on {@code lane}, or stop doing so.
     *
     * @param queueIds every queue registered in the test's schema, so each one's refusals can be counted
     */
    static void failLeaseReleases(DataSource dataSource, short[] queueIds, String lane, boolean fail) throws Exception {
        failLeaseReleases(dataSource, queueIds, lane, null, fail);
    }

    /**
     * Refuse every release of {@code unit}'s lease row on {@code lane}, of any queue, or stop doing so; the other units'
     * releases go through.
     *
     * @param queueIds every queue registered in the test's schema, so each one's refusals can be counted
     */
    static void failLeaseReleasesOf(DataSource dataSource, short[] queueIds, String lane, int unit, boolean fail) throws Exception {
        failLeaseReleases(dataSource, queueIds, lane, unit, fail);
    }

    private static void failLeaseReleases(DataSource dataSource, short[] queueIds, String lane, Integer unit, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute(refusalCounters(queueIds, "DROP SEQUENCE IF EXISTS lease_release_refusals_q%s_%s"));
                statement.execute(refusalCounters(queueIds, "CREATE SEQUENCE lease_release_refusals_q%s_%s"));
                statement.execute("CREATE OR REPLACE FUNCTION fail_lease_release() RETURNS trigger AS $$ "
                                          + "DECLARE released record; refused int := 0; BEGIN "
                                          + "FOR released IN SELECT n.queue_id, n.shard FROM refused_new n JOIN refused_old o USING (queue_id, lane, shard) "
                                          + "WHERE n.lane = TG_ARGV[0] AND n.owner IS NULL AND o.owner IS NOT NULL "
                                          + "AND (TG_NARGS < 2 OR n.shard = TG_ARGV[1]::int) LOOP "
                                          + "PERFORM nextval(format('lease_release_refusals_q%s_%s', released.queue_id, released.shard)::regclass); "
                                          + "refused := refused + 1; END LOOP; "
                                          + "IF refused > 0 THEN RAISE EXCEPTION 'simulated failure releasing % % unit(s)', refused, TG_ARGV[0]; END IF; "
                                          + "RETURN NULL; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_lease_release AFTER UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " REFERENCING OLD TABLE AS refused_old NEW TABLE AS refused_new"
                                          + " FOR EACH STATEMENT EXECUTE FUNCTION fail_lease_release('" + lane + "'"
                                          + (unit == null ? "" : ", '" + unit + "'") + ")");
            } else {
                // The trigger first: dropping it waits for a release still running it, so no refusal counts on a dropped sequence.
                statement.execute("DROP TRIGGER IF EXISTS fail_lease_release ON " + ShardOwnedSchema.LEASE_TABLE);
                statement.execute(refusalCounters(queueIds, "DROP SEQUENCE IF EXISTS lease_release_refusals_q%s_%s"));
            }
        }
    }

    /**
     * How many releases of {@code unit} of queue {@code queueId} {@link #failLeaseReleases} has refused since it was last
     * turned on.
     */
    static long releasesRefused(DataSource dataSource, short queueId, int unit) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM lease_release_refusals_q"
                                                            + queueId + "_" + unit)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * {@code statementPerUnit}, formatted with a queue id and a unit, run for every unit either lane can have (the ordered
     * lane's being the larger space) of every queue in {@code queueIds}.
     */
    private static String refusalCounters(short[] queueIds, String statementPerUnit) {
        var perQueue = new StringBuilder();
        for (var queueId : queueIds) {
            perQueue.append("EXECUTE format('").append(statementPerUnit).append("', ").append(queueId).append(", unit); ");
        }
        return "DO $$ BEGIN FOR unit IN 0.." + (ShardOwnedSchema.ORDERED_UNITS - 1) + " LOOP " + perQueue + "END LOOP; END $$";
    }
}
