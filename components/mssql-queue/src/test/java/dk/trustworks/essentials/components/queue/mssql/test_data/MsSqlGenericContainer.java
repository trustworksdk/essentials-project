/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.queue.postgresql.test_data;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

public class MsSqlGenericContainer extends GenericContainer<MsSqlGenericContainer> {
    private static final int SQLSERVER_PORT = 1433;
    private final String password;

    public MsSqlGenericContainer() {
        this("mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04", "Str0ng!Passw0rd");
    }

    public MsSqlGenericContainer(String image, String saPassword) {
        super(image);
        this.password = saPassword;

        withEnv("ACCEPT_EULA", "Y");
        withEnv("MSSQL_SA_PASSWORD", saPassword);
        withEnv("MSSQL_PID", "Developer");
        withExposedPorts(SQLSERVER_PORT);

        waitingFor(
                Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(5))
                  );
        withStartupTimeout(Duration.ofMinutes(5));
    }

    public String getUsername() { return "sa"; }
    public String getPassword() { return password; }

    public String getJdbcUrl() {
        return String.format(
                "jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true",
                getHost(), getMappedPort(SQLSERVER_PORT)
                            );
    }
}
