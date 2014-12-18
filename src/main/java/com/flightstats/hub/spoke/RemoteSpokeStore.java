package com.flightstats.hub.spoke;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.flightstats.hub.metrics.HostedGraphiteSender;
import com.flightstats.hub.model.Content;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.model.Trace;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"Convert2streamapi", "Convert2Lambda"})
public class RemoteSpokeStore {

    private final static Logger logger = LoggerFactory.getLogger(RemoteSpokeStore.class);

    private final static Client client = create();

    private final SpokeCluster cluster;
    private final HostedGraphiteSender sender;
    private final ExecutorService executorService;

    @Inject
    public RemoteSpokeStore(SpokeCluster cluster, HostedGraphiteSender sender) {
        this.cluster = cluster;
        this.sender = sender;
        executorService = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("RemoteSpokeStore-%d").build());
    }

    private static Client create() {
        Client client = Client.create();
        client.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(5));
        client.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
        return client;
    }

    public boolean write(String path, byte[] payload, Content content) throws InterruptedException {
        List<String> servers = cluster.getServers();
        int quorum = getQuorum(servers);
        CountDownLatch countDownLatch = new CountDownLatch(quorum);
        AtomicBoolean reported = new AtomicBoolean();
        for (final String server : servers) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    content.getTraces().add(new Trace(server));
                    try {
                        ClientResponse response = client.resource("http://" + server + "/spoke/payload/" + path)
                                .put(ClientResponse.class, payload);
                        long complete = System.currentTimeMillis();
                        content.getTraces().add(new Trace(server, response.getEntity(String.class)));
                        if (response.getStatus() == 201) {
                            if (reported.compareAndSet(false, true)) {
                                sender.send("heisenberg", complete - content.getContentKey().get().getMillis());
                            }
                            countDownLatch.countDown();
                            logger.trace("server {} path {} response {}", server, path, response);
                        } else {
                            logger.info("write failed: server {} path {} response {}", server, path, response);
                        }
                        response.close();
                    } catch (Exception e) {
                        content.getTraces().add(new Trace(server, e.getMessage()));
                        logger.warn("write failed: " + server + " " + path, e);
                    }

                }
            });
        }
        //todo - gfm - 11/13/14 - this could be smarter with waiting.  should we return success if one succeeds?
        boolean awaited = countDownLatch.await(30, TimeUnit.SECONDS);
        sender.send("consistent", System.currentTimeMillis() - content.getContentKey().get().getMillis());
        return awaited;
    }

    private int getQuorum(List<String> servers) {
        return Math.max(1, servers.size() - 1);
    }

    public com.flightstats.hub.model.Content read(String path, ContentKey key) {
        List<String> servers = cluster.getRandomServers();
        for (String server : servers) {
            try {
                ClientResponse response = client.resource("http://" + server + "/spoke/payload/" + path)
                        .get(ClientResponse.class);
                logger.trace("server {} path {} response {}", server, path, response);
                if (response.getStatus() == 200) {
                    byte[] entity = response.getEntity(byte[].class);
                    return SpokeMarshaller.toContent(entity, key);
                }
            } catch (JsonMappingException e) {
                logger.info("JsonMappingException for " + path);
            } catch (Exception e) {
                logger.warn("unable to get content " + path, e);
            }
        }
        return null;
    }

    public Collection<ContentKey> readTimeBucket(String channel, String timePath) throws InterruptedException {
        List<String> servers = cluster.getServers();
        CountDownLatch countDownLatch = new CountDownLatch(servers.size());
        String path = channel + "/" + timePath;
        Set<ContentKey> results = Sets.newConcurrentHashSet();
        for (final String server : servers) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClientResponse response = client.resource("http://" + server + "/spoke/time/" + path)
                                .get(ClientResponse.class);
                        logger.trace("server {} path {} response {}", server, path, response);
                        if (response.getStatus() == 200) {
                            String keysString = response.getEntity(String.class);
                            if (StringUtils.isNotEmpty(keysString)) {
                                logger.trace("entity '{}'", keysString);
                                String[] keys = keysString.split(",");
                                for (String key : keys) {
                                    results.add(ContentKey.fromUrl(StringUtils.substringAfter(key, "/")).get());
                                }
                            }
                        }
                    } catch (ClientHandlerException e) {
                        logger.warn("ClientHandlerException " + e.getMessage());
                    } catch (Exception e) {
                        logger.warn("unable to handle " + server + " " + path, e);
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            });
        }
        countDownLatch.await(30, TimeUnit.SECONDS);
        return results;
    }

    public boolean delete(String path) throws Exception {
        //todo - gfm - 11/19/14 - do we actually care about deleting in the short term cache?
        //todo - gfm - 11/13/14 - this could be merged with some of the write code
        List<String> servers = cluster.getServers();
        int quorum = getQuorum(servers);
        CountDownLatch countDownLatch = new CountDownLatch(quorum);
        for (final String server : servers) {
            //todo - gfm - 11/13/14 - we need to upgrade to Jersey 2.x for lambdas
            //noinspection Convert2Lambda
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    ClientResponse response = client.resource("http://" + server + "/spoke/payload/" + path)
                            .delete(ClientResponse.class);
                    if (response.getStatus() < 400) {
                        countDownLatch.countDown();
                    }
                    logger.trace("server {} path {} response {}", server, path, response);
                }
            });
        }

        //todo - gfm - 11/13/14 - this should be smarter with waiting.  should we return success if one succeeds?
        return countDownLatch.await(60, TimeUnit.SECONDS);
    }
}
