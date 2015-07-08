package com.flightstats.hub.spoke;


import com.flightstats.hub.model.Trace;
import com.flightstats.hub.util.TimeUtil;
import com.google.inject.Inject;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

/**
 * //todo - gfm - 7/8/15 - This is the same as SpokeInternalResource.
 * After SpokeInternalResource is rolled out to all Hubs, we can update the url in RemoteSpokeStore and remove SpokeResource
 */
@Path("/spoke")
public class SpokeResource {

    private final static Logger logger = LoggerFactory.getLogger(SpokeResource.class);
    private final FileSpokeStore spokeStore;
    private final UriInfo uriInfo;

    @Inject
    public SpokeResource(FileSpokeStore spokeStore, UriInfo uriInfo) {
        this.spokeStore = spokeStore;
        this.uriInfo = uriInfo;
    }

    @Path("/payload/{path:.+}")
    @GET
    public Response getPayload(@PathParam("path") String path) {
        try {
            byte[] read = spokeStore.read(path);
            if (read == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(read).build();
        } catch (Exception e) {
            logger.warn("unable to get " + path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Path("/payload/{path:.+}")
    @PUT
    public Response putPayload(@PathParam("path") String path, byte[] data) {
        try {
            DateTime start = TimeUtil.now();
            if (spokeStore.write(path, data)) {
                return Response
                        .created(uriInfo.getRequestUri())
                        .entity(new Trace("success", start).toString())
                        .build();
            }
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new Trace("failed", start).toString())
                    .build();
        } catch (Exception e) {
            logger.warn("unable to write " + path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private Response getResponse(String path) {
        logger.trace("time {}", path);
        try {
            String read = spokeStore.readKeysInBucket(path);
            if (read == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(read).build();
        } catch (Exception e) {
            logger.warn("unable to get " + path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Path("/time/{C}/{Y}/{M}/{day}")
    @GET
    public Response getTimeBucket(@PathParam("C") String C, @PathParam("Y") String Y,
                                  @PathParam("M") String M, @PathParam("day") String day) {
        return getResponse(C + "/" + Y + "/" + M + "/" + day);
    }

    @Path("/time/{C}/{Y}/{M}/{D}/{hour}")
    @GET
    public Response getTimeBucket(@PathParam("C") String C, @PathParam("Y") String Y,
                                  @PathParam("M") String M, @PathParam("D") String D,
                                  @PathParam("hour") String hour) {
        return getResponse(C + "/" + Y + "/" + M + "/" + D + "/" + hour);
    }

    @Path("/time/{C}/{Y}/{M}/{D}/{h}/{minute}")
    @GET
    public Response getTimeBucket(@PathParam("C") String C, @PathParam("Y") String Y,
                                  @PathParam("M") String M, @PathParam("D") String D,
                                  @PathParam("h") String h, @PathParam("minute") String minute) {
        return getResponse(C + "/" + Y + "/" + M + "/" + D + "/" + h + "/" + minute);
    }

    @Path("/time/{C}/{Y}/{M}/{D}/{h}/{m}/{second}")
    @GET
    public Response getTimeBucket(@PathParam("C") String C, @PathParam("Y") String Y,
                                  @PathParam("M") String M, @PathParam("D") String D,
                                  @PathParam("h") String h, @PathParam("m") String m,
                                  @PathParam("second") String second) {
        return getResponse(C + "/" + Y + "/" + M + "/" + D + "/" + h + "/" + m + "/" + second);
    }

    @Path("/payload/{path:.+}")
    @DELETE
    public Response delete(@PathParam("path") String path) {
        try {
            spokeStore.delete(path);
            return Response.ok().build();
        } catch (Exception e) {
            logger.warn("unable to write " + path, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Path("/latest/{channel}/{path:.+}")
    @GET
    public Response getLatest(@PathParam("channel") String channel, @PathParam("path") String path) {
        try {
            String read = spokeStore.getLatest(channel, path);
            if (read == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(read).build();
        } catch (NullPointerException e) {
            logger.debug("NPE - unable to get latest " + channel + " " + path);
        } catch (Exception e) {
            logger.warn("unable to get latest " + channel + " " + path, e);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

}
