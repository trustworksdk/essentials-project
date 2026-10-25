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

package dk.trustworks.essentials.components.foundation.transaction;

/**
 * <b>Internal - not a consumer API.</b> Asks whether a unit of work or a Spring transaction is active on the calling
 * thread, without requiring Spring: {@code spring-tx} is an optional dependency of foundation, so this class loads no
 * Spring type unless {@code spring-tx} is on the classpath. Public only because its callers live in different
 * foundation packages.
 */
public final class TransactionProbe {
    private static final String SPRING_TRANSACTION_AWARE_UNIT_OF_WORK_FACTORY =
            "dk.trustworks.essentials.components.foundation.transaction.spring.SpringTransactionAwareUnitOfWorkFactory";

    /**
     * Whether Spring's {@code spring-tx} is on the classpath - looked up by name, without initialising it
     */
    private static final boolean SPRING_TX_PRESENT = isClassPresent("org.springframework.transaction.support.TransactionSynchronizationManager");

    private TransactionProbe() {
    }

    /**
     * Is a unit of work or transaction active that work started now through {@code unitOfWorkFactory} would join?
     * <p>
     * {@code true} when the factory has a {@link UnitOfWorkFactory#getCurrentUnitOfWork() current UnitOfWork}, or when it
     * is a Spring-aware factory ({@code SpringTransactionAwareUnitOfWorkFactory} and subclasses) and a Spring transaction is
     * active - such a factory creates the {@link UnitOfWork} for that transaction on first use, so none is bound yet
     * under a plain {@code @Transactional}/{@code TransactionTemplate}.
     *
     * @param unitOfWorkFactory the factory; {@code null} answers {@code false}
     * @return whether a unit of work or transaction is active
     */
    public static boolean isUnitOfWorkActive(UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        if (unitOfWorkFactory == null) {
            return false;
        }
        if (unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
            return true;
        }
        // Matched by class name, so no Spring type is loaded here for a factory that is not Spring-aware
        return isSpringTransactionAware(unitOfWorkFactory.getClass()) && isSpringTransactionActive();
    }

    /**
     * Is a Spring transaction active on the calling thread ({@code TransactionSynchronizationManager.isActualTransactionActive()})?
     * <p>
     * Unlike {@link #isUnitOfWorkActive(UnitOfWorkFactory)} this asks no factory whether it is Spring-aware: it is for
     * a caller that cannot tell whether its connections join a Spring transaction (a plain {@code Jdbi} built on a
     * {@code TransactionAwareDataSourceProxy} does, one built on the raw pool does not).
     *
     * @return whether a Spring transaction is active; {@code false} when {@code spring-tx} is not on the classpath
     */
    public static boolean isSpringTransactionActive() {
        // SpringTransactions is only resolved once spring-tx is known to be present
        return SPRING_TX_PRESENT && SpringTransactions.isActualTransactionActive();
    }

    // This class's loader is the one that resolves SpringTransactions' reference, so asking the context loader could
    // say 'present' when that reference fails
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, TransactionProbe.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean isSpringTransactionAware(Class<?> type) {
        for (var candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            if (SPRING_TRANSACTION_AWARE_UNIT_OF_WORK_FACTORY.equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Holder so {@code TransactionSynchronizationManager} is only resolved once {@code spring-tx} is known to be on the
     * classpath.
     */
    private static final class SpringTransactions {
        static boolean isActualTransactionActive() {
            return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        }
    }
}
