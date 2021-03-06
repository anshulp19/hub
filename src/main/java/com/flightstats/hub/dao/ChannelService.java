package com.flightstats.hub.dao;

import com.flightstats.hub.app.HubProperties;
import com.flightstats.hub.app.InFlightService;
import com.flightstats.hub.channel.ChannelValidator;
import com.flightstats.hub.cluster.LastContentPath;
import com.flightstats.hub.dao.aws.MultiPartParser;
import com.flightstats.hub.exception.*;
import com.flightstats.hub.metrics.ActiveTraces;
import com.flightstats.hub.metrics.MetricsService;
import com.flightstats.hub.metrics.MetricsService.Insert;
import com.flightstats.hub.metrics.Traces;
import com.flightstats.hub.model.*;
import com.flightstats.hub.replication.ReplicationManager;
import com.flightstats.hub.time.TimeService;
import com.flightstats.hub.util.TimeUtil;
import com.flightstats.hub.webhook.TagWebhook;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class ChannelService {
    /**
     * REPLICATED_LAST_UPDATED is set to the last second updated, inclusive of that entire second.
     */
    public static final String REPLICATED_LAST_UPDATED = "/ReplicatedLastUpdated/";
    private static final String HISTORICAL_EARLIEST = "/HistoricalEarliest/";

    private final static Logger logger = LoggerFactory.getLogger(ChannelService.class);
    private static final int DIR_COUNT_LIMIT = HubProperties.getProperty("app.directionCountLimit", 10000);
    @Inject
    private ContentService contentService;
    @Inject
    @Named("ChannelConfig")
    private Dao<ChannelConfig> channelConfigDao;
    @Inject
    private ChannelValidator channelValidator;
    @Inject
    private ReplicationManager replicationManager;
    @Inject
    private LastContentPath lastContentPath;
    @Inject
    private InFlightService inFlightService;
    @Inject
    private TimeService timeService;
    @Inject
    private MetricsService metricsService;

    public boolean channelExists(String channelName) {
        return channelConfigDao.exists(channelName);
    }

    public ChannelConfig createChannel(ChannelConfig configuration) {
        logger.info("create channel {}", configuration);
        channelValidator.validate(configuration, null, false);
        channelConfigDao.upsert(configuration);
        notify(configuration, null);
        TagWebhook.updateTagWebhooksDueToChannelConfigChange(configuration);
        return configuration;
    }

    private void notify(ChannelConfig newConfig, ChannelConfig oldConfig) {
        if (newConfig.isReplicating()) {
            replicationManager.notifyWatchers();
        } else if (oldConfig != null) {
            if (oldConfig.isReplicating()) {
                replicationManager.notifyWatchers();
            }
        }
        if (newConfig.isHistorical()) {
            if (oldConfig == null || !oldConfig.isHistorical()) {
                ContentKey lastKey = ContentKey.lastKey(newConfig.getMutableTime());
                lastContentPath.update(lastKey, newConfig.getDisplayName(), HISTORICAL_EARLIEST);
            }
        }
        contentService.notify(newConfig, oldConfig);
    }

    public ChannelConfig updateChannel(ChannelConfig configuration, ChannelConfig oldConfig, boolean isLocalHost) {
        if (!configuration.equals(oldConfig)) {
            logger.info("updating channel {} from {}", configuration, oldConfig);
            channelValidator.validate(configuration, oldConfig, isLocalHost);
            channelConfigDao.upsert(configuration);
            TagWebhook.updateTagWebhooksDueToChannelConfigChange(configuration);
            notify(configuration, oldConfig);
        } else {
            logger.info("update with no changes {}", configuration);
        }
        return configuration;
    }

    public ContentKey insert(String channelName, Content content) throws Exception {
        channelName = getDisplayName(channelName);
        if (content.isNew() && isReplicating(channelName)) {
            throw new ForbiddenRequestException(channelName + " cannot modified while replicating");
        }
        long start = System.currentTimeMillis();
        ContentKey contentKey = insertInternal(channelName, content);
        metricsService.insert(channelName, start, Insert.single, 1, content.getSize());
        return contentKey;
    }

    private ContentKey insertInternal(String channelName, Content content) throws Exception {
        ChannelConfig channelConfig = getCachedChannelConfig(channelName);
        return inFlightService.inFlight(() -> {
            Traces traces = ActiveTraces.getLocal();
            traces.add("ContentService.insert");
            try {
                content.packageStream();
                checkZeroBytes(content, channelConfig);
                traces.add("ContentService.insert marshalled");
                ContentKey key = content.keyAndStart(timeService.getNow());
                logger.trace("writing key {} to channel {}", key, channelName);
                key = contentService.insert(channelName, content);
                traces.add("ContentService.insert end", key);
                return key;
            } catch (ContentTooLargeException e) {
                logger.info("content too large for channel " + channelName);
                throw e;
            } catch (Exception e) {
                traces.add("ContentService.insert", "error", e.getMessage());
                logger.warn("insertion error " + channelName, e);
                throw e;
            }
        });
    }

    public boolean historicalInsert(String channelName, Content content) throws Exception {
        final String normalizedChannelName = getDisplayName(channelName);
        if (!isHistorical(channelName)) {
            logger.warn("historical inserts require a mutableTime on the channel. {}", normalizedChannelName);
            throw new ForbiddenRequestException("historical inserts require a mutableTime on the channel.");
        }
        long start = System.currentTimeMillis();
        ChannelConfig channelConfig = getCachedChannelConfig(normalizedChannelName);
        ContentKey contentKey = content.getContentKey().get();
        if (contentKey.getTime().isAfter(channelConfig.getMutableTime())) {
            String msg = "historical inserts must not be after mutableTime" + normalizedChannelName + " " + contentKey;
            logger.warn(msg);
            throw new InvalidRequestException(msg);
        }
        boolean insert = inFlightService.inFlight(() -> {
            content.packageStream();
            checkZeroBytes(content, channelConfig);
            return contentService.historicalInsert(normalizedChannelName, content);
        });
        lastContentPath.updateDecrease(contentKey, normalizedChannelName, HISTORICAL_EARLIEST);
        metricsService.insert(normalizedChannelName, start, Insert.historical, 1, content.getSize());
        return insert;
    }

    private void checkZeroBytes(Content content, ChannelConfig channelConfig) {
        if (!channelConfig.isAllowZeroBytes() && content.getContentLength() == 0) {
            throw new InvalidRequestException("zero byte items are not allowed in this channel");
        }
    }

    public Collection<ContentKey> insert(BulkContent content) throws Exception {
        final BulkContent bulkContent = content.withChannel(getDisplayName(content.getChannel()));
        String channel = bulkContent.getChannel();
        if (bulkContent.isNew() && isReplicating(channel)) {
            throw new ForbiddenRequestException(channel + " cannot modified while replicating");
        }
        long start = System.currentTimeMillis();
        Collection<ContentKey> contentKeys = inFlightService.inFlight(() -> {
            MultiPartParser multiPartParser = new MultiPartParser(bulkContent);
            multiPartParser.parse();
            return contentService.insert(bulkContent);
        });
        metricsService.insert(channel, start, Insert.bulk, bulkContent.getItems().size(), bulkContent.getSize());
        return contentKeys;
    }

    public boolean isReplicating(String channelName) {
        try {
            return getCachedChannelConfig(channelName).isReplicating();
        } catch (NoSuchChannelException e) {
            return false;
        }
    }

    private boolean isHistorical(String channelName) {
        try {
            return getCachedChannelConfig(channelName).isHistorical();
        } catch (NoSuchChannelException e) {
            return false;
        }
    }

    public Optional<ContentKey> getLatest(DirectionQuery query) {
        query = query.withChannelName(getDisplayName(query.getChannelName()));
        String channel = query.getChannelName();
        if (!channelExists(channel)) {
            return Optional.absent();
        }
        query = query.withStartKey(getLatestLimit(query.getChannelName(), query.isStable()));
        query = configureQuery(query);
        Optional<ContentKey> latest = contentService.getLatest(query);
        ActiveTraces.getLocal().add("before filter", channel, latest);
        if (latest.isPresent()) {
            SortedSet<ContentKey> filtered = ContentKeyUtil.filter(latest.asSet(), query);
            if (filtered.isEmpty()) {
                return Optional.absent();
            }
        }
        return latest;
    }

    public Optional<ContentKey> getLatest(String channel, boolean stable) {
        channel = getDisplayName(channel);
        DirectionQuery query = DirectionQuery.builder()
                .channelName(channel)
                .next(false)
                .stable(stable)
                .count(1)
                .build();
        return getLatest(query);
    }

    private DirectionQuery configureStable(DirectionQuery query) {
        ContentPath lastUpdated = getLatestLimit(query.getChannelName(), query.isStable());
        return query.withChannelStable(lastUpdated.getTime());
    }

    ContentKey getLatestLimit(String channelName, boolean stable) {
        ChannelConfig channel = getCachedChannelConfig(channelName);
        DateTime time = TimeUtil.now().plusMinutes(1);
        if (stable || !channel.isLive()) {
            time = getLastUpdated(channelName, new ContentKey(TimeUtil.stable())).getTime();
        }
        return ContentKey.lastKey(time);
    }

    public void deleteBefore(String channel, ContentKey limitKey) {
        channel = getDisplayName(channel);
        contentService.deleteBefore(channel, limitKey);
    }

    public Optional<Content> get(ItemRequest itemRequest) {
        itemRequest = itemRequest.withChannel(getDisplayName(itemRequest.getChannel()));
        DateTime limitTime = getChannelLimitTime(itemRequest.getChannel()).minusMinutes(15);
        if (itemRequest.getKey().getTime().isBefore(limitTime)) {
            return Optional.absent();
        }
        return contentService.get(itemRequest.getChannel(), itemRequest.getKey(), itemRequest.isRemoteOnly());
    }

    public ChannelConfig getChannelConfig(String channelName, boolean allowChannelCache) {
        if (allowChannelCache) {
            return getCachedChannelConfig(channelName);
        }
        return channelConfigDao.get(channelName);
    }

    public ChannelConfig getCachedChannelConfig(String channelName) {
        ChannelConfig channelConfig = channelConfigDao.getCached(channelName);
        if (null == channelConfig) {
            throw new NoSuchChannelException(channelName);
        }
        return channelConfig;
    }

    public Collection<ChannelConfig> getChannels() {
        return getChannels(false);
    }

    private Collection<ChannelConfig> getChannels(boolean useCache) {
        return channelConfigDao.getAll(useCache);
    }

    public Collection<ChannelConfig> getChannels(String tag, boolean useCache) {
        Collection<ChannelConfig> matchingChannels = new ArrayList<>();
        Iterable<ChannelConfig> channels = getChannels(useCache);
        for (ChannelConfig channel : channels) {
            if (channel.getTags().contains(tag)) {
                matchingChannels.add(channel);
            }
        }
        return matchingChannels;
    }

    public Iterable<String> getTags() {
        Collection<String> matchingChannels = new HashSet<>();
        Iterable<ChannelConfig> channels = getChannels();
        for (ChannelConfig channel : channels) {
            matchingChannels.addAll(channel.getTags());
        }
        return matchingChannels;
    }

    public SortedSet<ContentKey> queryByTime(TimeQuery query) {
        if (query == null) {
            return Collections.emptySortedSet();
        }
        query = query.withChannelName(getDisplayName(query.getChannelName()));
        query = query.withChannelConfig(getCachedChannelConfig(query.getChannelName()));
        ContentPath lastUpdated = getLastUpdated(query.getChannelName(), new ContentKey(TimeUtil.time(query.isStable())));
        query = query.withChannelStable(lastUpdated.getTime());
        Stream<ContentKey> stream = contentService.queryByTime(query).stream();
        stream = ContentKeyUtil.enforceLimits(query, stream);
        return stream.collect(Collectors.toCollection(TreeSet::new));
    }

    public SortedSet<ContentKey> query(DirectionQuery query) {
        if (query.getCount() <= 0) {
            return Collections.emptySortedSet();
        }
        query = query.withChannelName(getDisplayName(query.getChannelName()));
        query = configureQuery(query);
        List<ContentKey> keys = new ArrayList<>(contentService.queryDirection(query));

        SortedSet<ContentKey> contentKeys = ContentKeyUtil.filter(keys, query);
        if (query.isInclusive()) {
            if (!contentKeys.isEmpty()) {
                if (query.isNext()) {
                    contentKeys.remove(contentKeys.last());
                } else {
                    contentKeys.remove(contentKeys.first());
                }
            }
            contentKeys.add(query.getStartKey());
        }
        ActiveTraces.getLocal().add("ChannelService.query", contentKeys);
        return contentKeys;
    }

    private DirectionQuery configureQuery(DirectionQuery query) {
        ActiveTraces.getLocal().add("configureQuery.start", query);
        if (query.getCount() > DIR_COUNT_LIMIT) {
            query = query.withCount(DIR_COUNT_LIMIT);
        }
        ChannelConfig channelConfig = getCachedChannelConfig(query.getChannelName());
        query = query.withChannelConfig(channelConfig);
        DateTime ttlTime = getChannelTtl(channelConfig, query.getEpoch());
        query = query.withEarliestTime(ttlTime);

        if (query.getStartKey() == null || query.getStartKey().getTime().isBefore(ttlTime)) {
            query = query.withStartKey(new ContentKey(ttlTime, "0"));
        }
        if (query.getEpoch().equals(Epoch.MUTABLE)) {
            if (!query.isNext()) {
                DateTime mutableTime = channelConfig.getMutableTime();
                if (query.getStartKey() == null || query.getStartKey().getTime().isAfter(mutableTime)) {
                    query = query.withStartKey(new ContentKey(mutableTime.plusMillis(1), "0"));
                }
            }
        }
        query = configureStable(query);
        ActiveTraces.getLocal().add("configureQuery.end", query);
        return query;
    }

    private DateTime getChannelTtl(ChannelConfig channelConfig, Epoch epoch) {
        DateTime ttlTime = channelConfig.getTtlTime();
        if (channelConfig.isHistorical()) {
            if (epoch.equals(Epoch.IMMUTABLE)) {
                ttlTime = channelConfig.getMutableTime().plusMillis(1);
            } else {
                ContentKey lastKey = ContentKey.lastKey(channelConfig.getMutableTime());
                return lastContentPath.get(channelConfig.getDisplayName(), lastKey, HISTORICAL_EARLIEST).getTime();
            }
        }
        return ttlTime;
    }

    public void get(StreamResults streamResults) {
        streamResults = streamResults.withChannel(getDisplayName(streamResults.getChannel()));
        contentService.get(streamResults);
    }

    private DateTime getChannelLimitTime(String channelName) {
        ChannelConfig channelConfig = getCachedChannelConfig(channelName);
        if (channelConfig.isHistorical()) {
            return TimeUtil.BIG_BANG;
        }
        return channelConfig.getTtlTime();
    }

    public boolean delete(String channelName) {
        channelName = getDisplayName(channelName);
        if (!channelConfigDao.exists(channelName)) {
            return false;
        }
        ChannelConfig channelConfig = getCachedChannelConfig(channelName);
        contentService.delete(channelConfig.getDisplayName());
        channelConfigDao.delete(channelConfig.getDisplayName());
        if (channelConfig.isReplicating()) {
            replicationManager.notifyWatchers();
            lastContentPath.delete(channelName, REPLICATED_LAST_UPDATED);
        }
        lastContentPath.delete(channelName, HISTORICAL_EARLIEST);
        TagWebhook.deleteAllTagWebhooksForChannel(channelConfig);
        return true;
    }

    public boolean delete(String channelName, ContentKey contentKey) {
        channelName = getDisplayName(channelName);
        ChannelConfig channelConfig = getCachedChannelConfig(channelName);
        if (channelConfig.isHistorical()) {
            if (!contentKey.getTime().isAfter(channelConfig.getMutableTime())) {
                contentService.delete(channelName, contentKey);
                return true;
            }
        }
        String message = "item is not within the channels mutableTime";
        ActiveTraces.getLocal().add(message, channelName, contentKey);
        throw new MethodNotAllowedException(message);
    }

    public ContentPath getLastUpdated(String channelName, ContentPath defaultValue) {
        channelName = getDisplayName(channelName);
        if (isReplicating(channelName)) {
            ContentPath contentPath = lastContentPath.get(channelName, defaultValue, REPLICATED_LAST_UPDATED);
            //REPLICATED_LAST_UPDATED is inclusive, and we want to be exclusive.
            if (!contentPath.equals(defaultValue)) {
                contentPath = new SecondPath(contentPath.getTime().plusSeconds(1));
            }
            return contentPath;
        }
        return defaultValue;
    }

    private String getDisplayName(String channelName) {
        return getCachedChannelConfig(channelName).getDisplayName();
    }
}
