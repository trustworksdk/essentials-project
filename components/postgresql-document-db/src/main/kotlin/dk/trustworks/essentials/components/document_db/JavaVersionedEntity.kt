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

package dk.trustworks.essentials.components.document_db

/**
 * Java interoperability bridge base class for [VersionedEntity].
 *
 * Java classes can extend this class and only deal with primitive long
 * version values, while Kotlin-facing APIs continue to use [Version].
 */
abstract class JavaVersionedEntity<ID, SELF_TYPE : VersionedEntity<ID, SELF_TYPE>> : VersionedEntity<ID, SELF_TYPE> {
    abstract fun getVersionValue(): Long
    abstract fun setVersionValue(version: Long)

    final override var version: Version
        get() = Version(getVersionValue())
        set(value) {
            setVersionValue(value.value)
        }
}
