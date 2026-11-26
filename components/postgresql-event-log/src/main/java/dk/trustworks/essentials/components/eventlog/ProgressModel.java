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
package dk.trustworks.essentials.components.eventlog;

/**
 * Where a subscription's resume point is written; the values {@code el_progress} stores. It follows from the
 * handler kind: a {@link PeriodicHandler} is {@link #PERIODIC}, a {@link PageHandler} is {@link #IN_HANDLER_TRANSACTION}.
 * Public for {@link EventLogAdmin#importProgress}, which writes a progress row before any handler exists.
 */
public enum ProgressModel {
    /** An autocommit save at most every {@link EventLogSettings#progressSaveInterval()} */
    PERIODIC,
    /** In the page transaction, as its last statement */
    IN_HANDLER_TRANSACTION
}
