package com.flightstats.datahub.dao;

import com.flightstats.datahub.model.*;
import com.flightstats.datahub.util.TimeProvider;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentServiceImpl implements ContentService {

    private final static Logger logger = LoggerFactory.getLogger(ContentServiceImpl.class);

    private final ContentDao contentDao;
    private final TimeProvider timeProvider;
    private final KeyCoordination keyCoordination;

    @Inject
    public ContentServiceImpl(
            ContentDao contentDao,
            TimeProvider timeProvider,
            KeyCoordination keyCoordination) {
        this.contentDao = contentDao;
        this.timeProvider = timeProvider;
        this.keyCoordination = keyCoordination;
    }

    @Override
    public void createChannel(ChannelConfiguration configuration) {
        logger.info("Creating channel " + configuration);
        contentDao.initializeChannel(configuration);
    }

    @Override
    public void updateChannel(ChannelConfiguration configuration) {
        logger.info("Updating channel " + configuration);
        contentDao.updateChannel(configuration);
    }

    @Override
    public ValueInsertionResult insert(ChannelConfiguration configuration, Optional<String> contentType, Optional<String> contentLanguage, byte[] data) {
        String channelName = configuration.getName();
        logger.debug("inserting {} bytes into channel {} ", data.length, channelName);
        Content value = new Content(contentType, contentLanguage, data, timeProvider.getMillis());
        Optional<Integer> ttlSeconds = getTtlSeconds(configuration);
        ValueInsertionResult result = contentDao.write(channelName, value, ttlSeconds);
        keyCoordination.insert(channelName, result.getKey());
        return result;
    }

    private Optional<Integer> getTtlSeconds(ChannelConfiguration channelConfiguration) {
        if (null == channelConfiguration) {
            return Optional.absent();
        }
        Long ttlMillis = channelConfiguration.getTtlMillis();
        return ttlMillis == null ? Optional.<Integer>absent() : Optional.of((int) (ttlMillis / 1000));
    }

    @Override
    public Optional<LinkedContent> getValue(String channelName, String id) {
        Optional<ContentKey> keyOptional = contentDao.getKey(id);
        if (!keyOptional.isPresent()) {
            return Optional.absent();
        }
        ContentKey key = keyOptional.get();
        logger.debug("fetching {} from channel {} ", key.toString(), channelName);
        Content value = contentDao.read(channelName, key);
        if (value == null) {
            return Optional.absent();
        }
        Optional<ContentKey> previous = key.getPrevious();
        Optional<ContentKey> next = key.getNext();
        if (next.isPresent()) {
            Optional<ContentKey> lastUpdatedKey = findLastUpdatedKey(channelName);
            if (lastUpdatedKey.isPresent()) {
                if (lastUpdatedKey.get().equals(key)) {
                    next = Optional.absent();
                }
            }
        }

        return Optional.of(new LinkedContent(value, previous, next));
    }

    @Override
    public Optional<ContentKey> findLastUpdatedKey(String channelName) {
        return Optional.fromNullable(keyCoordination.getLastUpdated(channelName));
    }

    @Override
    public Iterable<ContentKey> getKeys(ChannelConfiguration configuration, DateTime dateTime) {
        return contentDao.getKeys(configuration.getName(), dateTime);
    }

    @Override
    public void delete(String channelName) {
        logger.info("deleting channel " + channelName);
        contentDao.delete(channelName);
    }


}
