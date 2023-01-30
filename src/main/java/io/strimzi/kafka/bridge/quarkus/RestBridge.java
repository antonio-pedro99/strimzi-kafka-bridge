/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.quarkus;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.strimzi.kafka.bridge.BridgeContentType;
import io.strimzi.kafka.bridge.EmbeddedFormat;
import io.strimzi.kafka.bridge.http.model.HttpBridgeError;
import io.vertx.core.http.HttpConnection;
import io.vertx.ext.web.RoutingContext;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.concurrent.CompletionStage;

@Path("/")
public class RestBridge {

    @Inject
    Logger log;

    @Inject
    BridgeConfigRetriever configRetriever;

    private RestBridgeContext<byte[], byte[]> httpBridgeContext;

    @PostConstruct
    public void init() {
        httpBridgeContext = new RestBridgeContext<>();
    }

    @Path("/topics/{topicname}")
    @POST
    @Consumes({BridgeContentType.KAFKA_JSON_JSON,BridgeContentType.KAFKA_JSON_BINARY})
    @Produces(BridgeContentType.KAFKA_JSON)
    public CompletionStage<Response> send(@Context RoutingContext routingContext, byte[] body, @HeaderParam("Content-Type") String contentType,
                                          @PathParam("topicname") String topicName, @QueryParam("async") boolean async) throws RestBridgeException {
        log.infof("send thread %s", Thread.currentThread());
        RestSourceBridgeEndpoint<byte[], byte[]> source = this.getRestSourceBridgeEndpoint(routingContext, contentType);
        return source.send(routingContext, body, topicName, async);
    }

    @Path("/topics/{topicname}/partitions/{partitionid}")
    @POST
    @Consumes({BridgeContentType.KAFKA_JSON_JSON,BridgeContentType.KAFKA_JSON_BINARY})
    @Produces(BridgeContentType.KAFKA_JSON)
    public CompletionStage<Response> send(@Context RoutingContext routingContext, byte[] body, @HeaderParam("Content-Type") String contentType,
                                          @PathParam("topicname") String topicName, @PathParam("partitionid") String partitionId, @QueryParam("async") boolean async) throws RestBridgeException {
        log.infof("send thread %s", Thread.currentThread());
        RestSourceBridgeEndpoint<byte[], byte[]> source = this.getRestSourceBridgeEndpoint(routingContext, contentType);
        return source.send(routingContext, body, topicName, partitionId, async);
    }

    private RestSourceBridgeEndpoint<byte[], byte[]> getRestSourceBridgeEndpoint(RoutingContext routingContext, String contentType) throws RestBridgeException {
        if (!this.configRetriever.config().getHttpConfig().isProducerEnabled()) {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                    "Producer is disabled in config. To enable producer update http.producer.enabled to true"
            );
            throw new RestBridgeException(error);
        }

        HttpConnection httpConnection = routingContext.request().connection();
        RestSourceBridgeEndpoint<byte[], byte[]> source = this.httpBridgeContext.getHttpSourceEndpoints().get(httpConnection);

        try {
            if (source == null) {
                source = new RestSourceBridgeEndpoint<>(this.configRetriever.config(), contentTypeToFormat(contentType),
                        new ByteArraySerializer(), new ByteArraySerializer());

                source.closeHandler(s -> {
                    this.httpBridgeContext.getHttpSourceEndpoints().remove(httpConnection);
                });
                source.open();
                httpConnection.closeHandler(v -> {
                    closeConnectionEndpoint(httpConnection);
                });
                this.httpBridgeContext.getHttpSourceEndpoints().put(httpConnection, source);
            }
            return source;
        } catch (Exception ex) {
            if (source != null) {
                source.close();
            }
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                    ex.getMessage()
            );
            throw new RestBridgeException(error);
        }
    }

    private EmbeddedFormat contentTypeToFormat(String contentType) {
        switch (contentType) {
            case BridgeContentType.KAFKA_JSON_BINARY:
                return EmbeddedFormat.BINARY;
            case BridgeContentType.KAFKA_JSON_JSON:
                return EmbeddedFormat.JSON;
        }
        throw new IllegalArgumentException(contentType);
    }

    /**
     * Close a connection endpoint and before that all the related sink/source endpoints
     *
     * @param connection connection for which closing related endpoint
     */
    private void closeConnectionEndpoint(HttpConnection connection) {
        // closing connection, but before closing all sink/source endpoints
        if (this.httpBridgeContext.getHttpSourceEndpoints().containsKey(connection)) {
            RestSourceBridgeEndpoint<byte[], byte[]> sourceEndpoint = this.httpBridgeContext.getHttpSourceEndpoints().get(connection);
            if (sourceEndpoint != null) {
                sourceEndpoint.close();
            }
            this.httpBridgeContext.getHttpSourceEndpoints().remove(connection);
        }
    }
}
