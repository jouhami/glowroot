/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.store;

import org.glowroot.markers.UsedByJsonBinding;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByJsonBinding
public class TransactionAggregate {

    private final String transactionName;
    private final long totalMillis;
    private final long count;
    private final long storedTraceCount;

    TransactionAggregate(String transactionName, long totalMillis, long count,
            long storedTraceCount) {
        this.transactionName = transactionName;
        this.totalMillis = totalMillis;
        this.count = count;
        this.storedTraceCount = storedTraceCount;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public long getTotalMillis() {
        return totalMillis;
    }

    public long getCount() {
        return count;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }
}
