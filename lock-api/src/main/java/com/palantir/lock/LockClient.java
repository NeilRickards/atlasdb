// Copyright 2015 Palantir Technologies
//
// Licensed under the BSD-3 License (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.palantir.lock;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/**
 * A client of the lock server. Clients who desire reentrancy are required to
 * identify themselves via unique strings (for example, client IDs).
 *
 * @author jtamer
 */
@Immutable public final class LockClient implements Serializable {
    private static final long serialVersionUID = 0xf5637f2c8d7c94bdl;

    /**
     * An anonymous client of the lock server. Anonymous clients cannot acquire
     * locks reentrantly, because the server has no way to know whether the
     * current client is the same one who already holds the lock.
     */
    public static final LockClient ANONYMOUS = new LockClient(null);

    private static final String INTERNAL_LOCK_GRANT_CLIENT_ID = "(internal lock grant client)";

    /**
     * This should only be used by the lock service.
     */
    public static final LockClient INTERNAL_LOCK_GRANT_CLIENT = new LockClient(
            INTERNAL_LOCK_GRANT_CLIENT_ID);

    @Nullable private final String clientId;

    /**
     * Returns a {@code LockClient} instance for the given client ID.
     *
     * @throws IllegalArgumentException if {@code clientId} is {@code null} or
     *         the empty string
     */
    public static LockClient of(String clientId) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(clientId));
        Preconditions.checkArgument(!clientId.equals(INTERNAL_LOCK_GRANT_CLIENT_ID));
        return new LockClient(clientId);
    }

    private LockClient(@Nullable String clientId) {
        this.clientId = clientId;
    }

    /** Returns {@code true} if this is an anonymous lock client. */
    public boolean isAnonymous() {
        return clientId == null;
    }

    /** Returns the client ID, or {@code null} if this is an anonymous client. */
    @Nullable public String getClientId() {
        return clientId;
    }

    @Override public String toString() {
        return MoreObjects.toStringHelper(this)
                .addValue(isAnonymous() ? "anonymous" : ("\"" + clientId + "\""))
                .toString();
    }

    @Override public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LockClient)) return false;
        return Objects.equal(clientId, ((LockClient) obj).clientId);
    }

    @Override public int hashCode() {
        return Objects.hashCode(clientId);
    }

    private void readObject(@SuppressWarnings("unused") ObjectInputStream in)
            throws InvalidObjectException {
        throw new InvalidObjectException("proxy required");
    }

    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 0x495befe620789284l;

        @Nullable private final String clientId;

        SerializationProxy(LockClient lockClient) {
            clientId = lockClient.clientId;
        }

        Object readResolve() {
            if (clientId == null) return ANONYMOUS;
            if (clientId.equals(INTERNAL_LOCK_GRANT_CLIENT_ID)) return INTERNAL_LOCK_GRANT_CLIENT;
            return of(clientId);
        }
    }
}