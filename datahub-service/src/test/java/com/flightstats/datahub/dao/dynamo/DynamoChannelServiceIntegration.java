package com.flightstats.datahub.dao.dynamo;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.flightstats.datahub.dao.ChannelServiceIntegration;
import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.model.ContentKey;
import com.flightstats.datahub.model.LinkedContent;
import com.flightstats.datahub.model.ValueInsertionResult;
import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DynamoChannelServiceIntegration extends ChannelServiceIntegration {

    @BeforeClass
    public static void setupClass() throws Exception {
        //todo - gfm - 12/12/13 - this requires DynamoDBLocal -
        //  http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tools.DynamoDBLocal.html
        //start with java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar
        //todo - gfm - 12/12/13 - figure out how to run from IDE
        Properties properties = new Properties();

        properties.put("backing.store", "dynamo");
        //properties.put("dynamo.endpoint", "dynamodb.us-east-1.amazonaws.com");
        properties.put("dynamo.endpoint", "localhost:8000");
        properties.put("dynamo.protocol", "HTTP");
        properties.put("dynamo.environment", "dev");
        properties.put("dynamo.table.creation.wait.minutes", "5");
        //todo - gfm - 12/13/13 - make this generic
        properties.put("dynamo.credentials", "/Users/gmoulliet/code/datahub/datahub-service/src/conf/datahub/dev/credentials.properties");
        properties.put("hazelcast.conf.xml", "");
        finalStartup(properties);
    }


    @Override
    protected void verifyStartup() {
    }

    //todo - gfm - 12/23/13 - time series isn't supported with the cassandra impl

    @Test
    public void testTimeSeriesChannelCreation() throws Exception {

        assertNull(channelService.getChannelConfiguration(channelName));
        ChannelConfiguration configuration = getChannelConfig();
        ChannelConfiguration createdChannel = channelService.createChannel(configuration);
        assertEquals(channelName, createdChannel.getName());
        assertEquals(createdChannel, channelService.getChannelConfiguration(channelName));
    }

    private ChannelConfiguration getChannelConfig() {
        return ChannelConfiguration.builder()
                .withName(channelName)
                .withTtlMillis(36000L)
                .withType(ChannelConfiguration.ChannelType.TimeSeries)
                .withPeakRequestRate(100).withRateTimeUnit(TimeUnit.MINUTES)
                .withContentKiloBytes(16)
                .build();
    }

    @Test
    public void testTimeSeriesChannelWriteReadDelete() throws Exception {
        //channelName ="testTimeSeriesChannelWriteRead2";
        ChannelConfiguration configuration = getChannelConfig();
        channelService.createChannel(configuration);

        byte[] bytes = "some data".getBytes();
        ValueInsertionResult insert1 = channelService.insert(channelName, Optional.<String>absent(), Optional.<String>absent(), bytes);
        ValueInsertionResult insert2 = channelService.insert(channelName, Optional.<String>absent(), Optional.<String>absent(), bytes);
        ValueInsertionResult insert3 = channelService.insert(channelName, Optional.<String>absent(), Optional.<String>absent(), bytes);
        HashSet<ContentKey> createdKeys = Sets.newHashSet(insert1.getKey(), insert2.getKey(), insert3.getKey());
        Optional<LinkedContent> value = channelService.getValue(channelName, insert1.getKey().keyToString());
        assertTrue(value.isPresent());
        LinkedContent compositeValue = value.get();
        assertArrayEquals(bytes, compositeValue.getData());

        assertFalse(compositeValue.getContentType().isPresent());
        assertFalse(compositeValue.getValue().getContentLanguage().isPresent());

        //todo - gfm - 12/23/13 - this fails using DynamoDBLocal
        /*Iterable<ContentKey> keys = channelService.getKeys(channelName, new DateTime(insert1.getDate()));
        HashSet<ContentKey> foundKeys = Sets.newHashSet(keys);

        assertEquals(createdKeys, foundKeys);*/

        channelService.delete(channelName);
        assertNull(channelService.getChannelConfiguration(channelName));
        channelNames.remove(channelName);

    }

    @Test
    public void testTimeSeriesChannelOptionals() throws Exception {

        ChannelConfiguration configuration = getChannelConfig();
        channelService.createChannel(configuration);
        byte[] bytes = "testChannelOptionals".getBytes();
        ValueInsertionResult insert = channelService.insert(channelName, Optional.of("content"), Optional.of("lang"), bytes);

        Optional<LinkedContent> value = channelService.getValue(channelName, insert.getKey().keyToString());
        assertTrue(value.isPresent());
        LinkedContent compositeValue = value.get();
        assertArrayEquals(bytes, compositeValue.getData());

        assertEquals("content", compositeValue.getContentType().get());
        assertEquals("lang", compositeValue.getValue().getContentLanguage().get());
    }

    @Test
    public void testChannel() throws Exception {
        AmazonDynamoDBClient dbClient = injector.getInstance(AmazonDynamoDBClient.class);
        DynamoUtils dynamoUtils = injector.getInstance(DynamoUtils.class);
        DynamoChannelMetadataDao channelMetadataDao = new DynamoChannelMetadataDao(dbClient, dynamoUtils);
        ChannelConfiguration configuration = getChannelConfig();
        ChannelConfiguration channel = channelMetadataDao.createChannel(configuration);
        assertNotNull(channel);
        ChannelConfiguration existing = channelMetadataDao.getChannelConfiguration(configuration.getName());
        assertEquals(configuration.toString(), existing.toString());
    }
}
