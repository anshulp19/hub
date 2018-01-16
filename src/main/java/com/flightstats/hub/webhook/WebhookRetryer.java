package com.flightstats.hub.webhook;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.app.HubProvider;
import com.flightstats.hub.metrics.DataDog;
import com.flightstats.hub.model.ContentPath;
import com.flightstats.hub.rest.RestClient;
import com.flightstats.hub.util.HubUtils;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.timgroup.statsd.StatsDClient;
import lombok.Builder;
import lombok.Singular;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 *  This class is responsible for trying to deliver a payload
 *  until a set of configurable criteria is met.
 */
class WebhookRetryer {

    private final static Logger logger = LoggerFactory.getLogger(WebhookRetryer.class);
    private final static StatsDClient statsd = DataDog.statsd;
    private final static int CONNECT_TIMEOUT_SECONDS = 60;

    private List<Predicate<DeliveryAttempt>> giveUpIfs = new ArrayList<>();
    private List<Predicate<DeliveryAttempt>> tryLaterIfs = new ArrayList<>();
    private WebhookError webhookError = HubProvider.getInstance(WebhookError.class);

    private Client httpClient;

    @Builder
    WebhookRetryer(@Singular List<Predicate<DeliveryAttempt>> giveUpIfs,
                   @Singular List<Predicate<DeliveryAttempt>> tryLaterIfs,
                   int timeoutSeconds) {
        this.giveUpIfs = giveUpIfs;
        this.tryLaterIfs = tryLaterIfs;
        this.httpClient = RestClient.createClient(CONNECT_TIMEOUT_SECONDS, timeoutSeconds, true, false);
    }

    boolean send(Webhook webhook, ContentPath contentPath, ObjectNode body) {
        int attemptNumber = 0;
        boolean isGivingUpOnItem = false;
        boolean isRetrying = true;
        while (isRetrying) {

            DeliveryAttempt attempt = DeliveryAttempt.builder()
                    .number(++attemptNumber)
                    .webhook(webhook)
                    .contentPath(contentPath)
                    .payload(body.toString())
                    .build();


            boolean shouldGiveUp = shouldGiveUp(attempt);
            boolean shouldTryLater = shouldTryLater(attempt);

            if (shouldGiveUp || shouldTryLater) {
                logger.debug("{} {} stopping delivery before attempt #{}", attempt.getWebhook().getName(), attempt.getContentPath().toUrl(), attempt.getNumber());
                isRetrying = false;
                isGivingUpOnItem = shouldGiveUp;
                continue;
            }

            String payload = body.toString();
            logger.debug("{} {} delivery attempt #{} {} {}", attempt.getWebhook().getName(), attempt.getContentPath().toUrl(), attempt.getNumber(), webhook.getCallbackUrl(), payload);
            ClientResponse response = null;
            try {
                response = httpClient.resource(attempt.getWebhook().getCallbackUrl())
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .post(ClientResponse.class, payload);
                attempt.setStatusCode(response.getStatus());
            } catch (ClientHandlerException e) {
                attempt.setException(e);
            } finally {
                HubUtils.close(response);
            }

            String requestResult = determineResult(attempt);
            logger.debug("{} {} to {} response {}", attempt.getWebhook().getName(), attempt.getContentPath().toUrl(), attempt.getWebhook().getCallbackUrl(), requestResult);

            if (isSuccessful(attempt)) {
                isRetrying = false;
                isGivingUpOnItem = true;
                continue;
            } else {
                webhookError.add(attempt.getWebhook().getName(), new DateTime() + " " + attempt.getContentPath() + " " + requestResult);
                statsd.incrementCounter("webhook.errors", "name:" + attempt.getWebhook().getName(), "status:" + attempt.getStatusCode());
            }

            try {
                long exponentialMultiplier = 1000;
                long maximumSleepTimeMS = TimeUnit.MINUTES.toMillis(attempt.getWebhook().getMaxWaitMinutes());
                long sleepTimeMS = calculateSleepTimeMS(attempt, exponentialMultiplier, maximumSleepTimeMS);
                logger.debug("{} {} waiting {} seconds until retrying", attempt.getWebhook().getName(), attempt.getContentPath().toUrl(), TimeUnit.MILLISECONDS.toSeconds(sleepTimeMS));
                Thread.sleep(sleepTimeMS);
            } catch (InterruptedException e) {
                String message = String.format("%s %s to %s interrupted", attempt.getWebhook().getName(), attempt.getContentPath().toUrl(), attempt.getWebhook().getCallbackUrl());
                logger.debug(message, e);
                statsd.incrementCounter("webhook.errors", "name:" + webhook.getName(), "status:500");
                Thread.currentThread().interrupt();
                isRetrying = false;
            }
        }

        return isGivingUpOnItem;
    }

    private boolean shouldGiveUp(DeliveryAttempt attempt) {
        long reasonsToStop = giveUpIfs.stream().filter(predicate -> predicate.test(attempt)).count();
        return reasonsToStop != 0;
    }

    private boolean shouldTryLater(DeliveryAttempt attempt) {
        long reasonsToStop = tryLaterIfs.stream().filter(predicate -> predicate.test(attempt)).count();
        return reasonsToStop != 0;
    }

    private String determineResult(DeliveryAttempt attempt) {
        if (attempt.getException() == null) {
            return String.format("%s %s", attempt.getStatusCode(), Response.Status.fromStatusCode(attempt.getStatusCode()));
        } else {
            return attempt.getException().getMessage();
        }
    }

    private boolean isSuccessful(DeliveryAttempt attempt) {
        boolean isSuccessResponse = attempt.getStatusCode() >= 200 && attempt.getStatusCode() < 300;
        boolean isRedirectResponse = attempt.getStatusCode() >= 300 && attempt.getStatusCode() < 400;
        return isSuccessResponse || isRedirectResponse;
    }

    private long calculateSleepTimeMS(DeliveryAttempt attempt, long multiplier, long maximumSleepTimeMS) {
        double result = Math.pow(2, attempt.getNumber());
        long exponentialSleepTimeMS = Math.round(multiplier * result);
        return exponentialSleepTimeMS < maximumSleepTimeMS ? exponentialSleepTimeMS : maximumSleepTimeMS;
    }

}
