/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.schema;

import java.util.regex.Pattern;

import org.apache.commons.lang.Validate;

import com.google.common.base.Objects;

public final class Namespace {
    public static final Namespace EMPTY_NAMESPACE = new Namespace("");
    public static final Namespace DEFAULT_NAMESPACE = new Namespace("default");

    /**
     * Unchecked name pattern (no dots).
     * <p>
     * This will not protect you from creating namespace that are incompatible with your underlying datastore.
     * <p>
     * Use {@link Namespace.LOOSELY_CHECKED_NAME} or {@link Namespace.STRICTLY_CHECKED_NAME} if possible.
     */
    public static final Pattern UNCHECKED_NAME = Pattern.compile("^[^\\.\\s]+$");

    /**
     * Less restrictive name pattern (letters, numbers, underscores, and hyphens).
     * <p>
     * Use {@link Namespace.STRICTLY_CHECKED_NAME} if possible.
     */
    public static final Pattern LOOSELY_CHECKED_NAME = Pattern.compile("^[\\w-]+$");

    /**
     * Restrictive name pattern (letters, numbers, and non-initial single underscores).
     */
    public static final Pattern STRICTLY_CHECKED_NAME = Pattern.compile("^^(?!.*__.*)[a-zA-Z0-9][\\w]*$");

    private final String name;

    public static Namespace create(String name) {
        return create(name, STRICTLY_CHECKED_NAME);
    }

    public static Namespace create(String name, Pattern p) {
        Validate.notEmpty(name, "namespace name cannot be empty (see Namespace.EMPTY_NAMESPACE instead).");
        Validate.isTrue(!name.contains("."), "namespace cannot contain dots (atlas reserved).");
        Validate.isTrue(p.matcher(name).matches(), "'" + name + "' does not match namespace pattern '" + p + "'.");
        return new Namespace(name);
    }

    private Namespace(String name) {
        this.name = name;
    }

    public boolean isEmptyNamespace() {
        return this == EMPTY_NAMESPACE;
    }

    public String getName() {
        return name;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Namespace other = (Namespace) obj;
        return Objects.equal(name, other.name);
    }

    @Override
    public String toString() {
        return "Namespace [name=" + name + "]";
    }
}
