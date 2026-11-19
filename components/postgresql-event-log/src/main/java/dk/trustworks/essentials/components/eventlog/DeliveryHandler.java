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
 * What a subscription hands its events to. The kind decides the progress model: a
 * {@link PeriodicHandler} gets one event at a time and the engine saves the resume point periodically; a
 * {@link PageHandler} gets pages inside a transaction whose last statement moves the resume point.
 */
public sealed interface DeliveryHandler permits PeriodicHandler, PageHandler {
}
