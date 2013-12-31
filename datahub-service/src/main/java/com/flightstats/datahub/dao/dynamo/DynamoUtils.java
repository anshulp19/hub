package com.flightstats.datahub.dao.dynamo;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.*;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 *
 */
public class DynamoUtils {

    private final static Logger logger = LoggerFactory.getLogger(DynamoUtils.class);

    private final AmazonDynamoDBClient dbClient;
    private final String environment;
    private final int tableCreationWaitMinutes;

    @Inject
    public DynamoUtils(AmazonDynamoDBClient dbClient,
                       @Named("dynamo.environment") String environment,
                       @Named("dynamo.table.creation.wait.minutes") int tableCreationWaitMinutes) {
        this.dbClient = dbClient;
        this.environment = environment;
        this.tableCreationWaitMinutes = tableCreationWaitMinutes;
    }

    public String getTableName(String channelName) {
        return "deihub_" + environment + "_" + channelName;
    }

    /**
     * If a table does not already exist, create it.
     * Waits for the table to become ready for use.
     */
    public void createTable(CreateTableRequest request) {
        String tableName = request.getTableName();
        try {
            waitForTableStatus(tableName, TableStatus.ACTIVE);
        } catch (ResourceNotFoundException e) {
            dbClient.createTable(request);
            logger.info("Creating " + tableName + " ...");
            waitForTableStatus(tableName, TableStatus.ACTIVE);
        }
    }

    public void changeProvisioning(String channelName, ProvisionedThroughput provisionedThroughput) {
        //todo - gfm - 12/13/13 - this needs to consider relative percent change of provisioning as well as max changes per day
        String tableName = getTableName(channelName);
        DescribeTableResult describeTableResult = dbClient.describeTable(tableName);
        ProvisionedThroughputDescription existingThroughput = describeTableResult.getTable().getProvisionedThroughput();
        if (existingThroughput.getReadCapacityUnits().equals(provisionedThroughput.getReadCapacityUnits())
                && existingThroughput.getWriteCapacityUnits().equals(provisionedThroughput.getWriteCapacityUnits())) {
            logger.info("table " + tableName + " is already at this capacity ");
        }
        dbClient.updateTable(tableName, provisionedThroughput);
        waitForTableStatus(tableName, TableStatus.ACTIVE);
    }

    private void waitForTableStatus(String tableName, TableStatus status) {
        long endTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(tableCreationWaitMinutes);
        while (System.currentTimeMillis() < endTime) {
            try {
                DescribeTableRequest request = new DescribeTableRequest(tableName);
                TableDescription tableDescription = dbClient.describeTable(request).getTable();
                String tableStatus = tableDescription.getTableStatus();
                if (status.equals(TableStatus.fromValue(tableStatus))) {
                    logger.info("table " + tableName + " is " + status.toString());
                    return;
                }
            } catch (AmazonServiceException ase) {
                logger.info("exception creating table " + tableName + " " + ase.getMessage());
                throw ase;
            }
            sleep();
        }
        logger.warn("table never went active " + tableName);
        throw new RuntimeException("Table " + tableName + " never went active");
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
            //ignore
        }
    }
}
