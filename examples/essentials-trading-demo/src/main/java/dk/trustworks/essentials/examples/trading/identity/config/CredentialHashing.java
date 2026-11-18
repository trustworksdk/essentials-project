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
package dk.trustworks.essentials.examples.trading.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Hashes a password for storage: with the {@link PasswordEncoder} (BCrypt) for every user, except - when
 * {@code trading-demo.identity.unhashed-password-email-suffix} is set - users whose email ends with it, whose password
 * is stored {@code {noop}}. A demo aid for the load driver's customers ({@code lda-<n>@load.local}, one shared demo
 * password): registering and logging in 10 000 of them cost every run minutes of BCrypt on the whole machine. Off by
 * default; never set it for real users. Logins need nothing: the delegating encoder reads the algorithm from the hash.
 */
@Component
public class CredentialHashing {
    private final PasswordEncoder passwordEncoder;
    private final String          unhashedEmailSuffix;

    public CredentialHashing(PasswordEncoder passwordEncoder,
                             @Value("${trading-demo.identity.unhashed-password-email-suffix:}") String unhashedEmailSuffix) {
        this.passwordEncoder = requireNonNull(passwordEncoder, "No passwordEncoder provided");
        this.unhashedEmailSuffix = unhashedEmailSuffix == null ? "" : unhashedEmailSuffix.trim();
    }

    public String hash(String email, String rawPassword) {
        requireNonNull(email, "No email provided");
        requireNonNull(rawPassword, "No rawPassword provided");
        if (!unhashedEmailSuffix.isEmpty() && email.endsWith(unhashedEmailSuffix)) {
            return "{noop}" + rawPassword;
        }
        return passwordEncoder.encode(rawPassword);
    }
}
