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
package com.palantir.atlasdb.cli;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cli.api.AtlasDbServices;
import com.palantir.atlasdb.cli.api.SingleBackendCommand;
import com.palantir.atlasdb.keyvalue.api.SweepResults;
import com.palantir.atlasdb.schema.generated.SweepPriorityTable;
import com.palantir.atlasdb.schema.generated.SweepTableFactory;
import com.palantir.atlasdb.sweep.SweepTaskRunner;
import com.palantir.atlasdb.transaction.api.Transaction;
import com.palantir.atlasdb.transaction.impl.TxTask;
import com.palantir.common.base.Throwables;

import io.airlift.airline.Command;
import io.airlift.airline.Option;

@Command(name = "sweep", description = "Sweep old table rows")
public class SweepCommand extends SingleBackendCommand {

    @Option(name = {"-n", "--namespace"},
            description = "An atlas namespace to sweep")
    String namespace;

    @Option(name = {"-t", "--table"},
            description = "An atlas table to sweep")
    String table;

    @Option(name = {"-r", "--row"},
            description = "A row to start from (hex encoded bytes)")
    String row;

    @Option(name = {"-a", "--all"},
            description = "Sweep all tables")
    boolean sweepAllTables;

    @Option(name = {"-b", "--batch"},
            description = "Sweep batch size (default: 2000)")
    int sweepBatchSize = 2000;

    @Option(name = {"-p", "--pause"},
    description = "The number of milliseconds to pause between each batch of deletes (default: 5000)")
    long sweepPauseMillis = 5000;

	@Override
	protected int execute(final AtlasDbServices services) {
        SweepTaskRunner sweepRunner = services.getSweepTaskRunner();

        if (!((namespace != null) ^ (table != null) ^ sweepAllTables)) {
            System.err.println("Specify one of --namespace, --table, or --all options.");
            return 1;
        }
        if ((namespace != null) && (row != null)) {
            System.err.println("Cannot specify a start row (" + row + ") when sweeping multiple tables (in namespace " + namespace + ")");
            return 1;
        }

        Map<String, Optional<byte[]>> tableToStartRow = Maps.newHashMap();

        if ((table != null)) {
            Optional<byte[]> startRow = Optional.of(new byte[0]);
            if (row != null) {
                startRow = Optional.of(decodeStartRow(row));
            }
            tableToStartRow.put(table, startRow);
        } else if (namespace != null) {
            Set<String> tablesInNamespace = Sets.filter(services.getKeyValueService().getAllTableNames(),
                    Predicates.containsPattern("^" + namespace + "\\."));
            for (String table : tablesInNamespace) {
                tableToStartRow.put(table, Optional.of(new byte[0]));
            }
        } else if (sweepAllTables) {
            tableToStartRow.putAll(
                    Maps.asMap(
                            Sets.difference(services.getKeyValueService().getAllTableNames(), AtlasDbConstants.hiddenTables),
                            Functions.constant(Optional.of(new byte[0]))));
        }

        for (Map.Entry<String, Optional<byte[]>> entry : tableToStartRow.entrySet()) {
            final String table = entry.getKey();
            Optional<byte[]> startRow = entry.getValue();

            final AtomicLong cellsExamined = new AtomicLong();
            final AtomicLong cellsDeleted = new AtomicLong();

            while (startRow.isPresent()) {
                Stopwatch watch = Stopwatch.createStarted();
                SweepResults results = sweepRunner.run(table, sweepBatchSize, startRow.get());
                System.out.println(String.format("Swept from %s to %s in table %s in %d ms, examined %d unique cells, deleted %d cells.",
                        encodeStartRow(startRow), encodeEndRow(results.getNextStartRow()),
                        table, watch.elapsed(TimeUnit.MILLISECONDS),
                        results.getCellsExamined(), results.getCellsDeleted()));
                startRow = results.getNextStartRow();
                cellsDeleted.addAndGet(results.getCellsDeleted());
                cellsExamined.addAndGet(results.getCellsExamined());
                try {
                    Thread.sleep(sweepPauseMillis);
                } catch (InterruptedException e) {
                    throw Throwables.rewrapAndThrowUncheckedException(e);
                }
            }

            services.getTransactionManager().runTaskWithRetry(new TxTask() {
                @Override
                public Void execute(Transaction t) {
                    SweepPriorityTable priorityTable = SweepTableFactory.of().getSweepPriorityTable(t);
                    SweepPriorityTable.SweepPriorityRow row = SweepPriorityTable.SweepPriorityRow.of(table);
                    priorityTable.putWriteCount(row, 0L);
                    priorityTable.putCellsDeleted(row, cellsDeleted.get());
                    priorityTable.putCellsExamined(row, cellsExamined.get());
                    priorityTable.putLastSweepTime(row, System.currentTimeMillis());

                    System.out.println(String.format("Finished sweeping %s, examined %d unique cells, deleted %d cells.",
                            table, cellsExamined.get(), cellsDeleted.get()));

                    if (cellsDeleted.get() > 0) {
                        Stopwatch watch = Stopwatch.createStarted();
                        services.getKeyValueService().compactInternally(table);
                        System.out.println(String.format("Finished performing compactInternally on %s in %d ms.",
                                table, watch.elapsed(TimeUnit.MILLISECONDS)));
                    }
                    return null;
                }
            });
        }
        return 0;
	}

    private String encodeStartRow(Optional<byte[]> rowBytes) {
        if (rowBytes.isPresent()) {
            return BaseEncoding.base16().encode(Arrays.copyOf(rowBytes.get(), 12));
        }
        return BaseEncoding.base16().encode(Arrays.copyOf(new byte[0], 12));
    }

    private String encodeEndRow(Optional<byte[]> rowBytes) {
        if (rowBytes.isPresent() && !rowBytes.equals(Arrays.copyOf(new byte[0], 12))) {
            return BaseEncoding.base16().encode(Arrays.copyOf(rowBytes.get(), 12));
        } else {
            byte[] buffer = new byte[12];
            Arrays.fill(buffer, (byte) 0xFF);
            return BaseEncoding.base16().encode(buffer);
        }
    }

    private byte[] decodeStartRow(String rowString) {
        return BaseEncoding.base16().decode(rowString);
    }
}
