package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
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
                            EssentialsComponentsConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword()
                    ).applyTo(ctx.getEnvironment())); // needed

    @Test
    void verify_api_beans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DBFencedLockApi.class);
            DBFencedLockApi dbFencedLockApi = ctx.getBean(DBFencedLockApi.class);
            assertThat(dbFencedLockApi.getAllLocks("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(DurableQueuesApi.class);
            DurableQueuesApi durableQueuesApi = ctx.getBean(DurableQueuesApi.class);
            assertThat(durableQueuesApi.getQueueNames("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(PostgresqlQueryStatisticsApi.class);
            PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi = ctx.getBean(PostgresqlQueryStatisticsApi.class);
            assertThat(postgresqlQueryStatisticsApi.getTopTenSlowestQueries("principal")).isNotNull();
        });
    }

    @Test
    void verify_essentials_properties() {
        contextRunner
                .withPropertyValues("essentials.immutable-jackson-module-enabled=true")
                .run(ctx -> {
                    EssentialsComponentsProperties props = ctx.getBean(EssentialsComponentsProperties.class);
                    assertThat(props.isImmutableJacksonModuleEnabled()).isTrue();
                });
    }
}
