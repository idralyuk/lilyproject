/*
 * Copyright 2012 NGDATA nv
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
package org.lilyproject.process.test;

import java.io.File;
import java.util.List;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilyproject.client.LilyClient;
import org.lilyproject.lilyservertestfw.LilyProxy;
import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.Link;
import org.lilyproject.repository.api.QName;
import org.lilyproject.repository.api.RecordType;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.repository.api.Scope;
import org.lilyproject.repository.api.TypeManager;
import org.lilyproject.util.test.TestHomeUtil;

import static org.junit.Assert.assertTrue;

/**
 * This test verifies that pre-splitted record tables work correctly (for UUID-based record id's).
 */
public class TableSplitTest {
    private static LilyProxy lilyProxy;
    private static File tmpDir;

    // TODO Re-enable links-forward and links-backward tables for this test, if possible.
    // This is dependent on either the table pre-split mechanism or the AbsoluteRecord serialization
    // working in a different way.
    private static List<String> TABLE_NAMES = Lists.newArrayList("record");//, "links-forward", "links-backward");

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        lilyProxy = new LilyProxy();

        //
        // Make multiple record table splits
        //

        if (lilyProxy.getMode() == LilyProxy.Mode.CONNECT || lilyProxy.getMode() == LilyProxy.Mode.HADOOP_CONNECT) {
            // The tables will likely already exist and not be recreated, hence we won't be able to change
            // the number of regions. Therefore, drop them.
            Configuration conf = HBaseConfiguration.create();
            conf.set("hbase.zookeeper.quorum", "localhost");
            HBaseAdmin hbaseAdmin = new HBaseAdmin(conf);
            for (String tableName : TABLE_NAMES) {
                if (hbaseAdmin.tableExists(tableName)) {
                    hbaseAdmin.disableTable(tableName);
                    hbaseAdmin.deleteTable(tableName);
                }
            }
            HConnectionManager.deleteConnection(hbaseAdmin.getConfiguration(), true);
        }

        // Temp dir where we will create conf dir
        tmpDir = TestHomeUtil.createTestHome("lily-tablesplit-test-");

        File customConfDir = setupConfDirectory(tmpDir);
        String oldCustomConfDir = setProperty("lily.conf.customdir", customConfDir.getAbsolutePath());
        String oldRestoreTemplate = setProperty("lily.lilyproxy.restoretemplatedir", "false");

        try {
            lilyProxy.start();
        } finally {
            // Make sure the properties won't be used by later-running tests
            setProperty("lily.conf.customdir", oldCustomConfDir);
            setProperty("lily.lilyproxy.restoretemplatedir", oldRestoreTemplate);
        }
    }

    private static String setProperty(String name, String value) {
        String oldValue = System.getProperty(name);
        if (value == null) {
            System.getProperties().remove(name);
        } else {
            System.setProperty(name, value);
        }
        return oldValue;
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        if (lilyProxy != null) {
            lilyProxy.stop();
        }
        TestHomeUtil.cleanupTestHome(tmpDir);

        if (lilyProxy.getMode() == LilyProxy.Mode.CONNECT || lilyProxy.getMode() == LilyProxy.Mode.HADOOP_CONNECT) {
            // We're in connect mode, drop the tables again so that the remainder of the tests
            // don't have the overhead of the extra splits
            Configuration conf = HBaseConfiguration.create();
            conf.set("hbase.zookeeper.quorum", "localhost");
            HBaseAdmin hbaseAdmin = new HBaseAdmin(conf);
            for (String tableName : TABLE_NAMES) {
                if (hbaseAdmin.tableExists(tableName)) {
                    hbaseAdmin.disableTable(tableName);
                    hbaseAdmin.deleteTable(tableName);
                }
            }
            HConnectionManager.deleteConnection(hbaseAdmin.getConfiguration(), true);
        }
    }

    private static File setupConfDirectory(File tmpDir) throws Exception {
        File confDir = new File(tmpDir, "conf");

        File generalConfDir = new File(confDir, "general");
        FileUtils.forceMkdir(generalConfDir);

        // Write configuration to activate the decorator
        String tablesXml = "<tables xmlns:conf='http://lilyproject.org/configuration' conf:inherit='shallow' " +
                "conf:inheritKey=\"string(@name)\">" +
                "<table name='record'>" +
                // 0x01 is the identifier byte for UUID records
                "  <splits><regionCount>3</regionCount><splitKeyPrefix>\\x01</splitKeyPrefix></splits>" +
                "</table>" +
                "<table name='links-forward'>" +
                "  <splits><regionCount>3</regionCount><splitKeyPrefix>\\x01</splitKeyPrefix></splits>" +
                "</table>" +
                "<table name='links-backward'>" +
                "  <splits><regionCount>3</regionCount><splitKeyPrefix>\\x01</splitKeyPrefix></splits>" +
                "</table>" +
                "</tables>";

        FileUtils.writeStringToFile(new File(generalConfDir, "tables.xml"), tablesXml, "UTF-8");

        // Write configuration to enable the linkindex
        String linkindexXml = "<linkindex xmlns:conf='http://lilyproject.org/configuration' conf:inherit='deep'>" +
                "<enabled>true</enabled></linkindex>";

        FileUtils.writeStringToFile(new File(generalConfDir, "linkindex.xml"), linkindexXml, "UTF-8");

        return confDir;
    }

    @Test
    public void testOne() throws Exception {
        LilyClient client = lilyProxy.getLilyServerProxy().getClient();

        //
        // Create some records
        //
        Repository repository = client.getRepository();
        TypeManager typeManager = repository.getTypeManager();
        IdGenerator idGenerator = repository.getIdGenerator();

        FieldType ft1 = typeManager.createFieldType("STRING", new QName("test", "field1"), Scope.NON_VERSIONED);
        FieldType ft2 = typeManager.createFieldType("LINK", new QName("test", "field2"), Scope.NON_VERSIONED);

        RecordType rt1 = typeManager.recordTypeBuilder()
                .defaultNamespace("test")
                .name("rt1")
                .fieldEntry().use(ft1).add()
                .fieldEntry().use(ft2).add()
                .create();

        for (int i = 0; i < 300; i++) {
            repository.recordBuilder()
                    .recordType(rt1.getName())
                    .field(ft1.getName(), "foo bar bar")
                    .field(ft2.getName(), new Link(idGenerator.newRecordId()))
                    .create();
        }

        Assert.assertTrue("Processing messages took too long", lilyProxy.waitSepEventsProcessed(60000L));

        //
        // Count number of records in each region
        //
        for (String tableName : TABLE_NAMES) {
            HTable table = new HTable(lilyProxy.getHBaseProxy().getConf(), tableName);
            for (HRegionInfo regionInfo : table.getRegionsInfo().keySet()) {
                Scan scan = new Scan();
                scan.setStartRow(regionInfo.getStartKey());
                scan.setStopRow(regionInfo.getEndKey());

                ResultScanner scanner = table.getScanner(scan);
                int count = 0;
                for (Result result : scanner) {
                    //System.out.println("result = " + Arrays.toString(result.getRow()));
                    count++;
                }

                assertTrue(String.format("Number of records in region '%s' is %d, expected between 60 and 140, " +
                        "start key is '%s', end key is '%s'", regionInfo.getRegionNameAsString(), count,
                        Bytes.toStringBinary(regionInfo.getStartKey()), Bytes.toStringBinary(regionInfo.getEndKey())),
                        count >= 60 && count <= 140);
            }
        }
    }

}
