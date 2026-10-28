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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code essentials.schema.emit.exit=true} path (RN-5): every other schema-mode IT turns the exit off, because
 * it calls {@link System#exit(int)}. Here the application runs in a forked JVM, with no {@code emit.*} property set -
 * so the defaults are what is tested: the script is written to {@code essentials-schema.sql} in the working directory,
 * and the JVM exits with code 0.
 */
@Testcontainers
class EssentialsSchemaEmitExitIT {
    /**
     * What the forked JVM exits with when {@link SpringApplication#run} returns - i.e. when the emit mode did not stop it
     */
    static final int EXIT_CODE_WHEN_NOT_STOPPED = 42;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("schema-emit-exit-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @TempDir
    Path workingDirectory;

    @Test
    void emit_writes_the_script_to_the_working_directory_and_exits_with_code_0() throws Exception {
        var output = workingDirectory.resolve("forked-jvm.log");
        var process = new ProcessBuilder(javaExecutable(),
                                         "-cp", System.getProperty("java.class.path"),
                                         EmitApplication.class.getName(),
                                         "--spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                         "--spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                         "--spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                         "--essentials.schema.mode=emit")
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();

        var finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        var log = Files.exists(output) ? Files.readString(output) : "";
        assertThat(finished).as("the forked JVM exits by itself - its output:\n%s", log).isTrue();
        assertThat(process.exitValue()).as("exit code - its output:\n%s", log).isZero();

        var scriptFile = workingDirectory.resolve("essentials-schema.sql");
        assertThat(scriptFile).as("the default script file, in the working directory").exists();
        assertThat(Files.readString(scriptFile)).startsWith("-- Essentials database schema")
                                                .contains("-- Contents:")
                                                .contains("-- Module postgresql-queue")
                                                .contains("-- Module postgresql-fenced-lock");
        assertThat(log).contains("Schema mode 'emit': the script is written to '" + scriptFile.toRealPath());
        assertThat(exists("durable_queues")).as("emit executes nothing").isFalse();
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                            .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static boolean exists(String relation) throws SQLException {
        try (var connection = DriverManager.getConnection(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
             var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    /**
     * The application the forked JVM runs: the starter's schema harness, nothing else
     */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class,
                              DataSourceTransactionManagerAutoConfiguration.class,
                              EssentialsComponentsConfiguration.class,
                              EssentialsSchemaConfiguration.class})
    public static class EmitApplication {
        @Bean
        EssentialsSecurityProvider essentialsSecurityProvider() {
            return new EssentialsSecurityProvider.AllAccessSecurityProvider();
        }

        public static void main(String[] args) {
            SpringApplication.run(EmitApplication.class, args);
            System.exit(EXIT_CODE_WHEN_NOT_STOPPED);
        }
    }
}
