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
 * Makes the database refuse every acquisition of one unit's lease row by one instance, the way a database error would,
 * and counts each refusal - for the tests of what a heartbeat tick's acquire loop does when an acquire statement fails
 * after it has adopted other units. The companion of {@link LeaseReleaseRefusals}, and a trigger for the same reason: the
 * test's role owns the table, and an owner keeps every privilege. The counter is a sequence because the refusal rolls
 * back its transaction, and a {@code nextval} is the one write a rollback does not undo.
 */
final class LeaseAcquisitionRefusals {

    private LeaseAcquisitionRefusals() {
    }

    /**
     * Refuse every acquisition of {@code unit}'s lease row on {@code lane} by {@code instanceId}, or stop doing so. Every
     * other write to the row - its release, another instance acquiring it - goes through.
     */
    static void failLeaseAcquisitionsOf(DataSource dataSource, String lane, int unit, String instanceId, boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("DROP SEQUENCE IF EXISTS lease_acquisition_refusals");
                statement.execute("CREATE SEQUENCE lease_acquisition_refusals");
                statement.execute("CREATE OR REPLACE FUNCTION fail_lease_acquisition() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.lane = TG_ARGV[0] AND NEW.shard = TG_ARGV[1]::int AND NEW.owner = TG_ARGV[2] "
                                          + "AND OLD.owner IS DISTINCT FROM NEW.owner THEN "
                                          + "PERFORM nextval('lease_acquisition_refusals'); "
                                          + "RAISE EXCEPTION 'simulated failure acquiring % unit %', NEW.lane, NEW.shard; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_lease_acquisition BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_lease_acquisition('" + lane + "', '" + unit + "', '"
                                          + instanceId + "')");
            } else {
                // The trigger first: dropping it waits for an acquisition still running it.
                statement.execute("DROP TRIGGER IF EXISTS fail_lease_acquisition ON " + ShardOwnedSchema.LEASE_TABLE);
                statement.execute("DROP SEQUENCE IF EXISTS lease_acquisition_refusals");
            }
        }
    }

    /**
     * How many acquisitions {@link #failLeaseAcquisitionsOf} has refused since it was last turned on.
     */
    static long acquisitionsRefused(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM lease_acquisition_refusals")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
