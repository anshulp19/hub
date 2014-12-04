package com.flightstats.hub.cluster;

import com.flightstats.hub.model.ContentKey;
import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentKeyValue {
    private final static Logger logger = LoggerFactory.getLogger(ContentKeyValue.class);

    private final CuratorFramework curator;

    @Inject
    public ContentKeyValue(CuratorFramework curator) {
        this.curator = curator;
    }

    public void initialize(String path, ContentKey defaultKey) {
        try {
            curator.create().creatingParentsIfNeeded().forPath(path, defaultKey.getBytes());
        } catch (KeeperException.NodeExistsException ignore) {
            //this will typically happen, except the first time
        } catch (Exception e) {
            logger.warn("unable to create node", e);
        }
    }

    public ContentKey get(String path, ContentKey defaultKey) {
        try {
            return get(path);
        } catch (KeeperException.NoNodeException e) {
            logger.warn("missing value for {}", path);
            initialize(path, defaultKey);
            return get(path, defaultKey);
        } catch (Exception e) {
            logger.warn("unable to get node " + e.getMessage());
            return defaultKey;
        }
    }

    private ContentKey get(String path) throws Exception {
        return ContentKey.fromBytes(curator.getData().forPath(path));
    }

    public void updateIncrease(ContentKey nextKey, String path) {
        try {
            int attempts = 0;
            while (attempts < 3) {
                LastUpdated existing = getLastUpdated(path);
                if (nextKey.compareTo(existing.key) > 0) {
                    if (setValue(path, nextKey, existing)) {
                        return;
                    }
                } else {
                    return;
                }
                attempts++;
            }
        } catch (Exception e) {
            logger.warn("unable to set " + path + " lastUpdated to " + nextKey, e);
        }
    }

    private boolean setValue(String path, ContentKey nextKey, LastUpdated existing) throws Exception {
        try {
            curator.setData().withVersion(existing.version).forPath(path, nextKey.getBytes());
            return true;
        } catch (KeeperException.BadVersionException e) {
            logger.info("bad version " + path + " " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.info("what happened? " + path, e);
            return false;
        }
    }

    public void delete(String path) {
        try {
            curator.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (Exception e) {
            logger.warn("unable to delete {} {}", path, e.getMessage());
        }
    }

    LastUpdated getLastUpdated(String path) {
        try {
            Stat stat = new Stat();
            byte[] bytes = curator.getData().storingStatIn(stat).forPath(path);
            return new LastUpdated(ContentKey.fromBytes(bytes), stat.getVersion());
        } catch (KeeperException.NoNodeException e) {
            logger.info("unable to get value " + path + " " + e.getMessage());
            throw new RuntimeException(e);
        } catch (Exception e) {
            logger.info("unable to get value " + path, e);
            throw new RuntimeException(e);
        }
    }

    class LastUpdated {
        ContentKey key;
        int version;

        private LastUpdated(ContentKey key, int version) {
            this.key = key;
            this.version = version;
        }
    }
}
