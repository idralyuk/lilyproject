/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.util.hbase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.List;

/**
 * This is a threadsafe solution for the non-threadsafe HTable.
 *
 * <p>The problem with HTable this tries to solve is that HTable is not threadsafe, and
 * on the other hand it is best not to instantiate a new HTable for each use for
 * performance reasons.
 *
 * <p>HTable is, unlike e.g. a file handle or a JDBC connection, not a scarce
 * resource which needs to be closed. The actual connection handling (which
 * consists of connections to a variety of region servers) it handled at other
 * places. We only need to avoid the cost of creating new copies of HTable all the time.
 *
 * <p>Therefore, an ideal solution is to cache HTable instances in threadlocal variables.
 *
 * <p>This solution is fine for the following situations:
 *
 * <ul>
 *   <li>Multiple threads access the same instance of LocalHTable, e.g. because it
 *       is used in a singleton service class or declared as a static variable.
 *   <li>The threads which make use of the HTable are long-running or pooled threads.
 * </ul>
 *
 * <p>Be careful/considerate when using autoflush.
 *
 * <p>The current implementation will still cause multiple HTable's to be instantiated
 * for the same {conf, table} pair on the same thread if you use multiple LocalHTable's.
 *
 * <p>An alternative solution is the HTablePool provided by HBase.
 */
public class LocalHTable extends ThreadLocal<HTable> implements HTableInterface {
    private Configuration conf;
    private byte[] tableName;
    private Log log = LogFactory.getLog(getClass());

    public LocalHTable(Configuration conf, byte[] tableName) {
        this.conf = conf;
        this.tableName = tableName;
    }

    public LocalHTable(Configuration conf, String tableName) throws IOException {
        this.conf = conf;
        this.tableName = Bytes.toBytes(tableName);
        // Create an instance for the current thread now, so that this would fail immediately if
        // e.g. the table does not exist or the connection cannot be made.
        set(new HTable(conf, tableName));
    }

    private HTable getTableSilent() {
        try {
            return getTable();
        } catch (IOException e) {
            throw new RuntimeException("Error getting HTable.", e);
        }
    }

    private HTable getTable() throws IOException {
        // Note that since this is about thread locals, we don't any synchronization
        HTable table = get();
        if (table == null) {
            table = new HTable(conf, tableName);
            set(table);

            if (log.isDebugEnabled()) {
                log.debug("Created a new htable instance for " + Bytes.toString(tableName) + " on thread " +
                        Thread.currentThread().getName());
            }
        }
        return table;
    }

    @Override
    public byte[] getTableName() {
        return getTableSilent().getTableName();
    }

    @Override
    public Configuration getConfiguration() {
        return getTableSilent().getConfiguration();
    }

    @Override
    public HTableDescriptor getTableDescriptor() throws IOException {
        return getTable().getTableDescriptor();
    }

    @Override
    public boolean exists(Get get) throws IOException {
        return getTable().exists(get);
    }

    @Override
    public Result get(Get get) throws IOException {
        return getTable().get(get);
    }

    @Override
    public Result getRowOrBefore(byte[] row, byte[] family) throws IOException {
        return getTable().getRowOrBefore(row, family);
    }

    @Override
    public ResultScanner getScanner(Scan scan) throws IOException {
        return getTable().getScanner(scan);
    }

    @Override
    public ResultScanner getScanner(byte[] family) throws IOException {
        return getTable().getScanner(family);
    }

    @Override
    public ResultScanner getScanner(byte[] family, byte[] qualifier) throws IOException {
        return getTable().getScanner(family, qualifier);
    }

    @Override
    public void put(Put put) throws IOException {
        HTable table = getTable();
        try {
            table.put(put);
        } finally {
            // if an exception occurs, we do not expect our put to be left
            // behind in the write buffer
            table.getWriteBuffer().clear();
        }
    }

    @Override
    public void put(List<Put> puts) throws IOException {
        getTable().put(puts);
    }

    @Override
    public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier, byte[] value, Put put) throws IOException {
        return getTable().checkAndPut(row, family, qualifier, value, put);
    }

    @Override
    public void delete(Delete delete) throws IOException {
        getTable().delete(delete);
    }

    @Override
    public void delete(List<Delete> deletes) throws IOException {
        getTable().delete(deletes);
    }

    @Override
    public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier, byte[] value, Delete delete) throws IOException {
        return getTable().checkAndDelete(row, family, qualifier, value, delete);
    }

    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount) throws IOException {
        return getTable().incrementColumnValue(row, family, qualifier, amount);
    }

    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL) throws IOException {
        return getTable().incrementColumnValue(row, family, qualifier, amount, writeToWAL);
    }

    @Override
    public boolean isAutoFlush() {
        return getTableSilent().isAutoFlush();
    }

    @Override
    public void flushCommits() throws IOException {
        getTable().flushCommits();
    }

    @Override
    public void close() throws IOException {
        getTable().close();
    }

    @Override
    public RowLock lockRow(byte[] row) throws IOException {
        return getTable().lockRow(row);
    }

    @Override
    public void unlockRow(RowLock rl) throws IOException {
        getTable().unlockRow(rl);
    }

    @Override
    public void batch(List<Row> actions, Object[] results) throws IOException, InterruptedException {
        getTable().batch(actions, results);
    }

    @Override
    public Object[] batch(List<Row> actions) throws IOException, InterruptedException {
        return getTable().batch(actions);
    }

    @Override
    public Result[] get(List<Get> gets) throws IOException {
        return getTable().get(gets);
    }

    @Override
    public Result increment(Increment increment) throws IOException {
        return getTable().increment(increment);
    }
}
