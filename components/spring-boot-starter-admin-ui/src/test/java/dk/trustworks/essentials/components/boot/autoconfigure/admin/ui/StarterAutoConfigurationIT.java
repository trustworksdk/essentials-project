package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EventStoreConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.ui.admin.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class StarterAutoConfigurationIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("starter-test-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class,
                            EventStoreConfiguration.class,
                            EssentialsAdminUIAutoConfiguration.class
                                                            ))
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword()
                     ).applyTo(ctx.getEnvironment())); // needed

    @Test
    void verify_view_beans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AdminView.class);
            assertThat(ctx).hasSingleBean(EventProcessorsView.class);
            assertThat(ctx).hasSingleBean(LocksView.class);
            assertThat(ctx).hasSingleBean(PostgresqlStatisticsView.class);
            assertThat(ctx).hasSingleBean(QueuesView.class);
            assertThat(ctx).hasSingleBean(SchedulerView.class);
            assertThat(ctx).hasSingleBean(SubscriptionsView.class);
        });
    }
}
