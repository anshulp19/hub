package com.flightstats.hub.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.metrics.EventTimed;
import com.flightstats.hub.model.ChannelConfig;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.util.HubUtils;
import com.google.common.base.Optional;
import com.google.inject.Inject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

@Path("/channel/{channel: .*}/status")
public class ChannelStatusResource {

    @Inject
    private UriInfo uriInfo;
    @Inject
    private ChannelService channelService;
    @Inject
    private HubUtils hubUtils;

    private static final ObjectMapper mapper = new ObjectMapper();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @EventTimed(name = "channel.ALL.status.get")
    public Response getLatest(@PathParam("channel") String channel,
                              @QueryParam("stable") @DefaultValue("true") boolean stable,
                              @QueryParam("trace") @DefaultValue("false") boolean trace) {
        ChannelConfig channelConfig = channelService.getChannelConfig(channel);
        if (null == channelConfig) {
            return Response.status(404).build();
        }
        ObjectNode root = mapper.createObjectNode();
        ObjectNode links = root.putObject("_links");
        ObjectNode self = links.putObject("self");
        String baseUri = uriInfo.getRequestUri().toString();
        self.put("href", baseUri);

        Optional<ContentKey> latest = channelService.getLatest(channel, stable, trace);
        ObjectNode latestNode = links.putObject("latest");
        if (latest.isPresent()) {
            latestNode.put("href", uriInfo.getBaseUri() + "channel/" + channel + "/" + latest.get().toUrl());
        } else {
            latestNode.put("href", uriInfo.getBaseUri() + "channel/" + channel + "/latest");
            latestNode.put("message", "channel is empty");
        }
        if (channelService.isReplicating(channel)) {
            ChannelConfig config = channelService.getCachedChannelConfig(channel);
            ObjectNode replicationSourceLatest = links.putObject("replicationSourceLatest");
            Optional<String> sourceLatest = hubUtils.getLatest(config.getReplicationSource());
            if (sourceLatest.isPresent()) {
                replicationSourceLatest.put("href", sourceLatest.get());
            } else {
                replicationSourceLatest.put("href", config.getReplicationSource() + "/latest");
                replicationSourceLatest.put("message", "channel is empty");
            }
        }

        return Response.ok(root).build();
    }

}
