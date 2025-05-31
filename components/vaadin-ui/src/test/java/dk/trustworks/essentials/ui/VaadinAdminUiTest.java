/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.ui;

import com.vaadin.flow.component.UI;
import dk.trustworks.essentials.ui.admin.*;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ.*;

public class VaadinAdminUiTest extends KaribuTestBase {

    @Test
    void canLoginAndSeeViews() {
        loginProgrammatically("lasse", "ROLE_essentials_admin", "ROLE_ADMIN");

        UI.getCurrent().navigate(AdminView.class);
        _assertOne(AdminView.class);

        UI.getCurrent().navigate(LocksView.class);
        _assertOne(LocksView.class);

        UI.getCurrent().navigate(QueuesView.class);
        _assertOne(QueuesView.class);

        UI.getCurrent().navigate(SubscriptionsView.class);
        _assertOne(SubscriptionsView.class);

        UI.getCurrent().navigate(EventProcessorsView.class);
        _assertOne(EventProcessorsView.class);

        UI.getCurrent().navigate(PostgresqlStatisticsView.class);
        _assertOne(PostgresqlStatisticsView.class);

        UI.getCurrent().navigate(SchedulerView.class);
        _assertOne(SchedulerView.class);
    }

}