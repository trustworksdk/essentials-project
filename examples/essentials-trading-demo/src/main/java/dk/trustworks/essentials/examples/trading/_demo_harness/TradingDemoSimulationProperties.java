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

package dk.trustworks.essentials.examples.trading._demo_harness;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Small set of knobs for the headless demo runner.
 */
@ConfigurationProperties(prefix = "trading-demo.simulation")
public class TradingDemoSimulationProperties {
    private boolean enabled = true;
    private int accountCount = 3;
    private int depositsPerAccount = 2;
    private int settlementsPerAccount = 1;
    private int instrumentCount = 2;
    /**
     * Safety cap on the deposits the policy-driven account is fed while waiting for the configured
     * closing-books event threshold to be crossed. Only a backstop - the loop stops as soon as the policy
     * rolls the generation, so raising the threshold does not require changing this unless it exceeds the cap.
     */
    private int maxPolicyDrivenEvents = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAccountCount() {
        return accountCount;
    }

    public void setAccountCount(int accountCount) {
        this.accountCount = accountCount;
    }

    public int getDepositsPerAccount() {
        return depositsPerAccount;
    }

    public void setDepositsPerAccount(int depositsPerAccount) {
        this.depositsPerAccount = depositsPerAccount;
    }

    public int getSettlementsPerAccount() {
        return settlementsPerAccount;
    }

    public void setSettlementsPerAccount(int settlementsPerAccount) {
        this.settlementsPerAccount = settlementsPerAccount;
    }

    public int getInstrumentCount() {
        return instrumentCount;
    }

    public void setInstrumentCount(int instrumentCount) {
        this.instrumentCount = instrumentCount;
    }

    public int getMaxPolicyDrivenEvents() {
        return maxPolicyDrivenEvents;
    }

    public void setMaxPolicyDrivenEvents(int maxPolicyDrivenEvents) {
        this.maxPolicyDrivenEvents = maxPolicyDrivenEvents;
    }
}
