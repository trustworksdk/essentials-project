package dk.trustworks.essentials.ui;

import com.github.mvysny.fakeservlet.FakeRequest;
import com.github.mvysny.kaributesting.v10.*;
import com.github.mvysny.kaributesting.v10.spring.MockSpringServlet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.*;
import com.vaadin.flow.spring.SpringServlet;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;


@Testcontainers
@SpringBootTest(
        classes = {UiTestApplication.class, UiTestSecurityConfig.class},
        properties = {"spring.lifecycle.timeout-per-shutdown-phase=1s",
                      "spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public abstract class KaribuTestBase {

    @Autowired
    protected ApplicationContext ctx;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test-admin-ui")
            .withUsername("essentials")
            .withPassword("password");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setupVaadin() {
        // 1. discover your @Route views
        Routes routes = new Routes().autoDiscoverViews("dk.trustworks.essentials.ui");

        // 2. supply the UI factory as a Kotlin Function0
        Function0<UI> uiFactory = UI::new;

        // 3. spin up the Spring‐aware servlet
        SpringServlet servlet = new MockSpringServlet(routes, ctx, uiFactory);

        // 4. wire everything into MockVaadin
        MockVaadin.setup(uiFactory, servlet);
    }

    @AfterEach
    void tearDownVaadin() {
        MockVaadin.tearDown();
        SecurityContextHolder.clearContext();
    }

    protected void loginProgrammatically(String username, String... roles) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username,
                "N/A",  // password is not used here
                AuthorityUtils.createAuthorityList(roles)
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        VaadinServletRequest vsr = (VaadinServletRequest) VaadinService.getCurrentRequest();
        FakeRequest          fr  = (FakeRequest) vsr.getRequest();
        fr.setUserPrincipalInt(auth);
        fr.setUserInRole((principal, role) ->
                                 auth.getAuthorities().stream()
                                     .map(a -> a.getAuthority())
                                     .anyMatch(a -> a.equals(role))
                        );
    }

}
