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

package dk.trustworks.essentials.examples.trading._customer_ui;

import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The customer UI's pages (phase 7 design, {@code docs/design/phase-7-customer-ui.md}). Each one returns a Thymeleaf
 * shell and reads nothing: the page's script fills it from the same JSON API every other client uses (§2), so the UI
 * cannot drift from that contract. The shell carries the session's CSRF token for the script to send back (§5).
 * <p>
 * The only thing a page is handed is who is logged in, which the script cannot ask the API for and needs to open an
 * account for its owner.
 */
@Controller
public class CustomerPages {
    private static final Set<String> ACCOUNT_PAGES = Set.of("portfolio", "trade", "funding");
    /**
     * What an account id from the path may look like before it is echoed into a page; the API decides whether it
     * exists and whether the caller owns it.
     */
    private static final Pattern     ACCOUNT_ID    = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @GetMapping("/")
    public String root() {
        return "redirect:/app";
    }

    @GetMapping("/app")
    public String home(@AuthenticationPrincipal AuthenticatedUser caller, Model model) {
        caller(caller, model);
        model.addAttribute("page", "home");
        return "customer_ui/home";
    }

    @GetMapping("/app/accounts/{accountId}/{page}")
    public String accountPage(@PathVariable String accountId,
                              @PathVariable String page,
                              @AuthenticationPrincipal AuthenticatedUser caller,
                              Model model) {
        if (!ACCOUNT_PAGES.contains(page) || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        caller(caller, model);
        model.addAttribute("page", page);
        model.addAttribute("accountId", accountId);
        return "customer_ui/" + page;
    }

    @GetMapping("/app/accounts/{accountId}")
    public String account(@PathVariable String accountId) {
        if (!ACCOUNT_ID.matcher(accountId).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/app/accounts/" + accountId + "/portfolio";
    }

    private static void caller(AuthenticatedUser caller, Model model) {
        model.addAttribute("userId", caller.userId().toString());
        model.addAttribute("username", caller.getUsername());
    }
}
