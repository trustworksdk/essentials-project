package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
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
                            EventStoreConfiguration.class
                    ))
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword()
                    ).applyTo(ctx.getEnvironment())); // needed

    @Test
    void verify_api_beans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EventStoreApi.class);
            EventStoreApi eventStoreApi = ctx.getBean(EventStoreApi.class);
            assertThat(eventStoreApi.findAllSubscriptions("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(PostgresqlEventStoreStatisticsApi.class);
            PostgresqlEventStoreStatisticsApi postgresqlEventStoreStatisticsApi = ctx.getBean(PostgresqlEventStoreStatisticsApi.class);
            assertThat(postgresqlEventStoreStatisticsApi.fetchTableActivityStatistics("principal")).isNotNull();
        });
    }

    @Test
    void verify_essentials_properties() {
        contextRunner
                .withPropertyValues("essentials.event-store.use-event-stream-gap-handler=true")
                .run(ctx -> {
                    EssentialsEventStoreProperties props = ctx.getBean(EssentialsEventStoreProperties.class);
                    assertThat(props.isUseEventStreamGapHandler()).isTrue();
                });
    }
}
