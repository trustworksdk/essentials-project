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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiProperties;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Serves the single Thymeleaf shell the admin UI runs in.
 * <p>
 * The shell is all that is rendered server-side: the layout, and the navigation gated by the caller's
 * roles. Every piece of data arrives in the browser by calling the published admin API, so this UI is a
 * consumer of the same contract any other UI would use — there is no second, server-side path to the
 * SPIs that could drift from it.
 * <p>
 * Role gating here is presentation only. It stops the UI from offering an operation that would come back
 * {@code 403}; the actual authorization decision stays with {@code EssentialsSecurityProvider} inside the
 * SPI beans, where a hand-crafted request meets it too.
 */
@Controller
public class AdminUiController {

    private final EssentialsAdminUiProperties  uiProperties;
    private final EssentialsAdminApiProperties apiProperties;
    private final EssentialsAuthenticatedUser  authenticatedUser;

    public AdminUiController(EssentialsAdminUiProperties uiProperties,
                             EssentialsAdminApiProperties apiProperties,
                             EssentialsAuthenticatedUser authenticatedUser) {
        this.uiProperties = requireNonNull(uiProperties, "No uiProperties provided");
        this.apiProperties = requireNonNull(apiProperties, "No apiProperties provided");
        this.authenticatedUser = requireNonNull(authenticatedUser, "No authenticatedUser provided");
    }

    /**
     * Both the bare base path and its trailing-slash form are mapped. Spring Boot 3 dropped implicit
     * trailing-slash matching, so {@code /essentials/admin/} — what a browser produces if the user
     * treats the UI as a directory — would otherwise 404 while {@code /essentials/admin} works.
     */
    @GetMapping({"${essentials.admin-ui.base-path:/essentials/admin}",
                 "${essentials.admin-ui.base-path:/essentials/admin}/"})
    public String index(Model model) {
        var admin = authenticatedUser.hasAdminRole();

        model.addAttribute("uiBasePath", uiProperties.getBasePath());
        // The browser needs to know where the API is mounted; it is configured independently of the UI.
        model.addAttribute("apiBasePath", apiProperties.getBasePath());
        model.addAttribute("principalName", authenticatedUser.getPrincipalName().orElse("anonymous"));
        model.addAttribute("authenticated", authenticatedUser.isAuthenticated());

        model.addAttribute("canReadLocks", admin || authenticatedUser.hasLockReaderRole());
        model.addAttribute("canWriteLocks", admin || authenticatedUser.hasLockWriterRole());
        model.addAttribute("canReadQueues", admin || authenticatedUser.hasQueueReaderRole());
        model.addAttribute("canWriteQueues", admin || authenticatedUser.hasQueueWriterRole());
        model.addAttribute("canReadPayloads", admin || authenticatedUser.hasQueuePayloadReaderRole());
        model.addAttribute("canReadScheduler", admin || authenticatedUser.hasSchedulerReaderRole());
        model.addAttribute("canReadSubscriptions", admin || authenticatedUser.hasSubscriptionReaderRole());
        model.addAttribute("canReadStatistics", admin || authenticatedUser.hasPostgresqlStatsReaderRole());

        return "essentials-admin/index";
    }
}
