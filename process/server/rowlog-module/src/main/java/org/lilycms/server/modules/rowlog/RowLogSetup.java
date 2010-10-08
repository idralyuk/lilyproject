package org.lilycms.server.modules.rowlog;

import org.apache.hadoop.conf.Configuration;
import org.apache.zookeeper.KeeperException;
import org.lilycms.rowlog.api.*;
import org.lilycms.rowlog.impl.*;
import org.lilycms.util.hbase.HBaseTableUtil;
import org.lilycms.util.zookeeper.LeaderElectionSetupException;
import org.lilycms.util.zookeeper.ZooKeeperItf;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;

public class RowLogSetup {
    private final RowLogConfigurationManager confMgr;
    private final Configuration hbaseConf;
    private final ZooKeeperItf zk;
    private RowLogImpl messageQueue;
    private RowLogImpl writeAheadLog;
    private RowLogProcessorElection messageQueueProcessorLeader;

    public RowLogSetup(RowLogConfigurationManager confMgr, ZooKeeperItf zk, Configuration hbaseConf) {
        this.confMgr = confMgr;
        this.zk = zk;
        this.hbaseConf = hbaseConf;
    }

    @PostConstruct
    public void start() throws InterruptedException, KeeperException, IOException, RowLogException, LeaderElectionSetupException {
        // If the subscription already exists, this method will silently return
        if (!confMgr.subscriptionExists("WAL", "LinkIndexUpdater")) {
            confMgr.addSubscription("WAL", "LinkIndexUpdater", RowLogSubscription.Type.VM, 3, 10);
        }
        if (!confMgr.subscriptionExists("WAL", "MQFeeder")) {
            confMgr.addSubscription("WAL", "MQFeeder", RowLogSubscription.Type.VM, 3, 20);
        }

        messageQueue = new RowLogImpl("MQ", HBaseTableUtil.getRecordTable(hbaseConf), HBaseTableUtil.MQ_PAYLOAD_COLUMN_FAMILY,
                HBaseTableUtil.MQ_COLUMN_FAMILY, 10000L, true, confMgr);
        messageQueue.registerShard(new RowLogShardImpl("MQS1", hbaseConf, messageQueue, 100));

        writeAheadLog = new RowLogImpl("WAL", HBaseTableUtil.getRecordTable(hbaseConf), HBaseTableUtil.WAL_PAYLOAD_COLUMN_FAMILY,
                HBaseTableUtil.WAL_COLUMN_FAMILY, 10000L, true, confMgr);
        RowLogShard walShard = new RowLogShardImpl("WS1", hbaseConf, writeAheadLog, 100);
        writeAheadLog.registerShard(walShard);

        RowLogMessageListenerMapping listenerClassMapping = RowLogMessageListenerMapping.INSTANCE;
        listenerClassMapping.put("MQFeeder", new MessageQueueFeeder(messageQueue));

        // Start the processor
        messageQueueProcessorLeader = new RowLogProcessorElection(zk, new RowLogProcessorImpl(messageQueue, confMgr));
        messageQueueProcessorLeader.start();
    }

    @PreDestroy
    public void stop() {
        messageQueueProcessorLeader.stop();
        messageQueue.stop();
        writeAheadLog.stop();
        RowLogMessageListenerMapping listenerClassMapping = RowLogMessageListenerMapping.INSTANCE;
        listenerClassMapping.remove("MQFeeder");        
    }

    public RowLog getMessageQueue() {
        return messageQueue;
    }

    public RowLog getWriteAheadLog() {
        return writeAheadLog;
    }
}