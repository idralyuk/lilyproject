package org.lilycms.rowlog.impl.test;


import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilycms.rowlog.api.ListenersObserver;
import org.lilycms.rowlog.api.RowLogSubscription;
import org.lilycms.rowlog.api.RowLogSubscription.Type;
import org.lilycms.rowlog.api.SubscriptionsObserver;
import org.lilycms.rowlog.impl.RowLogConfigurationManagerImpl;
import org.lilycms.testfw.HBaseProxy;
import org.lilycms.testfw.TestHelper;
import org.lilycms.util.io.Closer;
import org.lilycms.util.zookeeper.StateWatchingZooKeeper;
import org.lilycms.util.zookeeper.ZooKeeperItf;

import static org.junit.Assert.assertEquals;

public class RowLogConfigurationManagerTest {
    protected final static HBaseProxy HBASE_PROXY = new HBaseProxy();
    private static Configuration configuration;
    private static ZooKeeperItf zooKeeper;
    
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        TestHelper.setupLogging();
        HBASE_PROXY.start();
        configuration = HBASE_PROXY.getConf();
        zooKeeper = new StateWatchingZooKeeper(HBASE_PROXY.getZkConnectString(), 10000);
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        Closer.close(zooKeeper);
        HBASE_PROXY.stop();
    }

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testSubscription() throws Exception {
        String rowLogId = "testSubscriptionRowLogId";
        String subscriptionId1 = "testSubscriptionSubScriptionId1";
        String subscriptionId2 = "testSubscriptionSubScriptionId2";
        // Initialize
        RowLogConfigurationManagerImpl rowLogConfigurationManager = new RowLogConfigurationManagerImpl(zooKeeper);
        SubscriptionsCallBack callBack = new SubscriptionsCallBack();
        Assert.assertTrue(callBack.subscriptions.isEmpty());
        callBack.expect(Collections.<RowLogSubscription>emptyList());
        rowLogConfigurationManager.addSubscriptionsObserver(rowLogId, callBack);

        // After adding the observer we will receive an initial report of the subscriptions
        callBack.validate();

        // Add subscription
        RowLogSubscription expectedSubscriptionContext = new RowLogSubscription(rowLogId, subscriptionId1, Type.VM, 3, 1);
        callBack.expect(Arrays.asList(expectedSubscriptionContext));
        rowLogConfigurationManager.addSubscription(rowLogId, subscriptionId1, Type.VM, 3, 1);
        callBack.validate();

        RowLogSubscription expectedSubscriptionContext2 = new RowLogSubscription(rowLogId, subscriptionId2, Type.Netty, 5, 2);
        callBack.expect(Arrays.asList(expectedSubscriptionContext, expectedSubscriptionContext2));
        rowLogConfigurationManager.addSubscription(rowLogId, subscriptionId2, Type.Netty, 5, 2);
        callBack.validate();

        // Remove subscription
        callBack.expect(Arrays.asList(expectedSubscriptionContext2));
        rowLogConfigurationManager.removeSubscription(rowLogId, subscriptionId1);
        callBack.validate();
        
        callBack.expect(Collections.<RowLogSubscription>emptyList());
        rowLogConfigurationManager.removeSubscription(rowLogId, subscriptionId2);
        callBack.validate();

        rowLogConfigurationManager.shutdown();
    }
    
    private class SubscriptionsCallBack implements SubscriptionsObserver {
        public List<RowLogSubscription> subscriptions = new ArrayList<RowLogSubscription>();
        private List<RowLogSubscription> expectedSubscriptions;
        private Semaphore semaphore = new Semaphore(0);
        
        public void subscriptionsChanged(List<RowLogSubscription> subscriptions) {
            this.subscriptions = subscriptions;
            semaphore.release();
        }

        public void expect(List<RowLogSubscription> asList) {
            this.expectedSubscriptions = asList;
        }
        
        public void validate() throws Exception{
            semaphore.tryAcquire(10, TimeUnit.SECONDS);
            for (RowLogSubscription subscriptionContext : subscriptions) {
                Assert.assertTrue(expectedSubscriptions.contains(subscriptionContext));
            }
            for (RowLogSubscription subscriptionContext : expectedSubscriptions) {
                Assert.assertTrue(subscriptions.contains(subscriptionContext));
            }
        }
    }
    
    @Test
    public void testListener() throws Exception {
        String rowLogId = "testListenerRowLogId";
        String subscriptionId1 = "testListenerSubScriptionId1";
        // Initialize
        RowLogConfigurationManagerImpl rowLogConfigurationManager = new RowLogConfigurationManagerImpl(zooKeeper);

        ListenersCallBack callBack = new ListenersCallBack();
        Assert.assertTrue(callBack.listeners.isEmpty());
        callBack.expect(Collections.<String>emptyList());
        rowLogConfigurationManager.addListenersObserver(rowLogId, subscriptionId1, callBack);

        // After adding the observer we will receive an initial report of the listeners
        callBack.validate();

        // Add subscription
        rowLogConfigurationManager.addSubscription(rowLogId, subscriptionId1, Type.VM, 3, 1);
        callBack.expect(Collections.EMPTY_LIST);
        callBack.validate();

        // Add listener
        callBack.expect(Arrays.asList(new String[]{"Listener1"}));
        rowLogConfigurationManager.addListener(rowLogId, subscriptionId1, "Listener1");
        callBack.validate();

        callBack.expect(Arrays.asList(new String[]{"Listener1", "Listener2"}));
        rowLogConfigurationManager.addListener(rowLogId, subscriptionId1, "Listener2");
        callBack.validate();

        // Remove subscription
        callBack.expect(Arrays.asList(new String[]{"Listener2"}));
        rowLogConfigurationManager.removeListener(rowLogId, subscriptionId1, "Listener1");
        callBack.validate();
        
        callBack.expect(Collections.EMPTY_LIST);
        rowLogConfigurationManager.removeListener(rowLogId, subscriptionId1, "Listener2");
        callBack.validate();

        rowLogConfigurationManager.shutdown();
    }
    
    private class ListenersCallBack implements ListenersObserver {
        public List<String> listeners = new ArrayList<String>();
        private List<String> expectedListeners;
        
        private Semaphore semaphore = new Semaphore(0);
        
        public void listenersChanged(List<String> listeners) {
            this.listeners = listeners;
            semaphore.release();
        }

        public void expect(List<String> expectedListeners) {
            semaphore.drainPermits();
            this.expectedListeners = expectedListeners;
        }
        
        private void validate() throws Exception {
            semaphore.tryAcquire(10, TimeUnit.SECONDS);
            for (String listener: listeners) {
                Assert.assertTrue(expectedListeners.contains(listener));
            }
            for (String listener : expectedListeners) {
                Assert.assertTrue(listeners.contains(listener));
            }
        }

    }
}