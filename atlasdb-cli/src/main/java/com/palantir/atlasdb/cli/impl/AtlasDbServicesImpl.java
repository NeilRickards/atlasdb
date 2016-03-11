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
package com.palantir.atlasdb.cli.impl;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import javax.net.ssl.SSLSocketFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.cleaner.CleanupFollower;
import com.palantir.atlasdb.cleaner.Follower;
import com.palantir.atlasdb.cli.api.AtlasDbServices;
import com.palantir.atlasdb.factory.TransactionManagers;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.server.AtlasDbServerConfiguration;
import com.palantir.atlasdb.sweep.SweepTaskRunner;
import com.palantir.atlasdb.sweep.SweepTaskRunnerImpl;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManagers;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.TransactionServices;
import com.palantir.lock.RemoteLockService;
import com.palantir.timestamp.TimestampService;

import io.dropwizard.jackson.Jackson;

public class AtlasDbServicesImpl implements AtlasDbServices {

    private SerializableTransactionManager tm;
    private SweepTaskRunner sweeper;

    public static AtlasDbServices connect(File configFile, String configRoot) throws IOException {
        ObjectMapper configMapper = Jackson.newObjectMapper(new YAMLFactory());
        JsonNode node = getConfigNode(configMapper, configFile, configRoot);
        AtlasDbServerConfiguration config = configMapper.treeToValue(node, AtlasDbServerConfiguration.class);
        final SerializableTransactionManager tm = TransactionManagers.create(
                config.getConfig(),
                Optional.<SSLSocketFactory>absent(),
                ImmutableSet.<Schema>of(),
                new TransactionManagers.Environment() {
                    @Override
                    public void register(Object resource) {
                    }
                },
                true);

        KeyValueService kvs = tm.getKeyValueService();
        TransactionService transactionService = TransactionServices.createTransactionService(kvs);
        SweepStrategyManager sweepStrategyManager = SweepStrategyManagers.createDefault(kvs);
        CleanupFollower follower = CleanupFollower.create(ImmutableSet.<Schema>of());;
        SweepTaskRunner sweepRunner = new SweepTaskRunnerImpl(
                tm,
                kvs,
                new Supplier<Long>() {
                    @Override
                    public Long get() {
                        return tm.getUnreadableTimestamp();
                    }
                },
                new Supplier<Long>() {
                    @Override
                    public Long get() {
                        return tm.getImmutableTimestamp();
                    }
                },
                transactionService,
                sweepStrategyManager,
                ImmutableList.<Follower>of(follower));

        return new AtlasDbServicesImpl(tm, sweepRunner);
    }

    private static JsonNode getConfigNode(ObjectMapper configMapper, File configFile, String configRoot) throws IOException {
        JsonNode node = configMapper.readTree(configFile);
        if (Strings.isNullOrEmpty(configRoot)) {
            return node;
        } else {
            JsonNode rootNode = findRoot(node, configRoot);
            if (rootNode != null) {
                return rootNode;
            }
            throw new IllegalArgumentException("Could not find " + configRoot + " in yaml file " + configFile);
        }
    }

    private static JsonNode findRoot(JsonNode node, String configRoot) {
        if (node.has(configRoot)) {
            return node.get(configRoot);
        } else {
            Iterator<String> iter = node.fieldNames();
            while (iter.hasNext()) {
                JsonNode root = findRoot(node.get(iter.next()), configRoot);
                if (root != null) {
                    return root;
                }
            }
            return null;
        }
    }

    private AtlasDbServicesImpl(SerializableTransactionManager tm, SweepTaskRunner sweeper) {
        this.tm = tm;
        this.sweeper = sweeper;
    }

    @Override
    public TimestampService getTimestampService() {
        return tm.getTimestampService();
    }

    @Override
    public RemoteLockService getLockSerivce() {
        return tm.getLockService();
    }

    @Override
    public KeyValueService getKeyValueService() {
        return tm.getKeyValueService();
    }

    @Override
    public SerializableTransactionManager getTransactionManager() {
        return tm;
    }

    @Override
    public SweepTaskRunner getSweepTaskRunner() {
        return sweeper;
    }

}
