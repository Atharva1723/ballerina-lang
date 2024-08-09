/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.runtime.internal.scheduling;

import io.ballerina.runtime.api.async.StrandMetadata;
import io.ballerina.runtime.transactions.TransactionLocalContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

import static io.ballerina.runtime.api.constants.RuntimeConstants.CURRENT_TRANSACTION_CONTEXT_PROPERTY;

/**
 * Strand base class used with jvm code generation for functions.
 *
 * @since 0.955.0
 */
public class Strand {

    private final int id;
    private final String name;
    private final StrandMetadata metadata;
    private static final AtomicInteger nextStrandId = new AtomicInteger(0);
    private StrandState state = StrandState.RUNNABLE;
    private Map<String, Object> globalProps;

    public final boolean isIsolated;
    public Scheduler scheduler;
    public Strand parent;
    public TransactionLocalContext currentTrxContext;
    public Stack<TransactionLocalContext> trxContexts;
    public WorkerChannelMap workerChannelMap;

    public Strand() {
        this.id = -1;
        this.name = null;
        this.metadata = null;
        this.isIsolated = false;
    }

    public Strand(String name, StrandMetadata metadata, Scheduler scheduler, Strand parent, boolean isIsolated,
                  Map<String, Object> properties, WorkerChannelMap workerChannelMap) {
        this.id = nextStrandId.incrementAndGet();
        this.scheduler = scheduler;
        this.name = name;
        this.metadata = metadata;
        this.trxContexts = new Stack<>();
        this.parent = parent;
        this.isIsolated = isIsolated;

        if (properties != null) {
            this.globalProps = properties;
        } else if (parent != null) {
            this.globalProps = new HashMap<>(parent.globalProps);
        } else {
            this.globalProps = new HashMap<>();
        }
        this.workerChannelMap = workerChannelMap;
    }

    public Strand(String name, StrandMetadata metadata, Scheduler scheduler, Strand parent, boolean isIsolated,
                  Map<String, Object> properties, WorkerChannelMap workerChannelMap,
                  TransactionLocalContext currentTrxContext) {
        this(name, metadata, scheduler, parent, isIsolated, properties, workerChannelMap);
        if (currentTrxContext != null) {
            this.trxContexts = parent.trxContexts;
            this.trxContexts.push(currentTrxContext);
            this.currentTrxContext = currentTrxContext;
        } else {
            Object currentContext = globalProps.get(CURRENT_TRANSACTION_CONTEXT_PROPERTY);
            if (currentContext != null) {
                TransactionLocalContext branchedContext =
                        createTrxContextBranch((TransactionLocalContext) currentContext, this.id);
                setCurrentTransactionContext(branchedContext);
            }
        }
    }

    public void resume() {
        if (!isIsolated && state == StrandState.YIELDED) {
            scheduler.globalNonIsolatedLock.lock();
            this.state =  StrandState.RUNNABLE;
        }
    }

    public void yield() {
        if (!isIsolated && state == StrandState.RUNNABLE) {
            scheduler.globalNonIsolatedLock.unlock();
        }
        this.state =  StrandState.YIELDED;
    }

    private TransactionLocalContext createTrxContextBranch(TransactionLocalContext currentTrxContext,
                                                           int strandName) {
        TransactionLocalContext trxCtx = TransactionLocalContext
                .createTransactionParticipantLocalCtx(currentTrxContext.getGlobalTransactionId(),
                        currentTrxContext.getURL(), currentTrxContext.getProtocol(),
                        currentTrxContext.getInfoRecord());
        String currentTrxBlockId = currentTrxContext.getCurrentTransactionBlockId();
        if (currentTrxBlockId.contains("_")) {
            // remove the parent strand id from the transaction block id
            currentTrxBlockId = currentTrxBlockId.split("_")[0];
        }
        trxCtx.addCurrentTransactionBlockId(currentTrxBlockId + "_" + strandName);
        trxCtx.setTransactionContextStore(currentTrxContext.getTransactionContextStore());
        return trxCtx;
    }

    public Object getProperty(String key) {
        return this.globalProps.get(key);
    }

    public void setProperty(String key, Object value) {
        this.globalProps.put(key, value);
    }

    public boolean isInTransaction() {
        return this.currentTrxContext != null && this.currentTrxContext.isTransactional();
    }

    public void removeCurrentTrxContext() {
        if (!this.trxContexts.isEmpty()) {
            this.currentTrxContext = this.trxContexts.pop();
            globalProps.put(CURRENT_TRANSACTION_CONTEXT_PROPERTY, this.currentTrxContext);
            return;
        }
        globalProps.remove(CURRENT_TRANSACTION_CONTEXT_PROPERTY);
        this.currentTrxContext = null;
    }

    public void setCurrentTransactionContext(TransactionLocalContext ctx) {
        if (this.currentTrxContext != null) {
            this.trxContexts.push(this.currentTrxContext);
        }
        this.currentTrxContext = ctx;
        globalProps.putIfAbsent(CURRENT_TRANSACTION_CONTEXT_PROPERTY, this.currentTrxContext);
    }

    public int getId() {
        return id;
    }

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public StrandMetadata getMetadata() {
        return metadata;
    }
}
