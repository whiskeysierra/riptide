package org.zalando.riptide.nakadi;

/*
 * ⁣​
 * Riptide: Nakadi
 * ⁣⁣
 * Copyright (C) 2015 - 2016 Zalando SE
 * ⁣⁣
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ​⁣
 */

import static org.springframework.http.HttpStatus.Series.SUCCESSFUL;
import static org.springframework.http.MediaType.parseMediaType;
import static org.zalando.riptide.Bindings.anySeries;
import static org.zalando.riptide.Bindings.on;
import static org.zalando.riptide.Navigators.contentType;
import static org.zalando.riptide.Navigators.series;
import static org.zalando.riptide.Route.responseEntityOf;
import static org.zalando.riptide.RoutingTree.dispatch;
import static org.zalando.riptide.stream.Streams.streamOf;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus.Series;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.zalando.problem.ThrowableProblem;
import org.zalando.riptide.Binding;
import org.zalando.riptide.Bindings;
import org.zalando.riptide.Rest;
import org.zalando.riptide.ThrowingConsumer;
import org.zalando.riptide.capture.Capture;

import com.google.common.io.ByteStreams;

@Component
public class NakadiGateway {

    public static final MediaType PROBLEM = parseMediaType("application/problem+json");

    private static final Logger LOG = LoggerFactory.getLogger(NakadiGateway.class);
    private static final String SESSION_HEADER = "X-Nakadi-SessionId"; // TODO should this be X-Nakadi-StreamId?

    private static final Binding<Series> ON_FAILURE_BINDING =
            anySeries().call(dispatch(contentType(),
                    on(PROBLEM).call(ThrowableProblem.class, propagate()),
                    Bindings.anyContentType().call(invalidContent())));

    private static ThrowingConsumer<ClientHttpResponse> invalidContent() {
        return response -> {
            Charset charset = Optional.ofNullable(response.getHeaders().getContentType())
                    .map(MediaType::getCharSet)
                    .orElse((StandardCharsets.ISO_8859_1));
            String body = new String(ByteStreams.toByteArray(response.getBody()), charset);
            throw new IOException("Unsupported content: " + response.getRawStatusCode() +
                    " - " + response.getStatusText() + "\n" + response.getHeaders() + "\n" + body);
        };
    }

    private static ThrowingConsumer<ThrowableProblem> propagate() {
        return entity -> {
            throw entity;
        };
    }

    static class GatewayException extends RuntimeException {

        public GatewayException(Throwable cause) {
            super(cause);
        }
    }

    private final Rest rest;

    @Autowired
    public NakadiGateway(final Rest rest) {
        this.rest = rest;
    }

    public void stream(final String owningApplication, final List<String> eventTypes, final EventConsumer consumer)
            throws InterruptedException, ExecutionException, IOException {
        stream(subscribe(new Subscription(null, owningApplication, eventTypes)), consumer);
    }

    private void stream(final Subscription subscription, final EventConsumer consumer)
            throws InterruptedException, ExecutionException, IOException {
        rest.get("/subscriptions/{id}/events", subscription.getId())
                .dispatch(series(),
                        on(SUCCESSFUL).call(responseEntityOf(streamOf(Batch.class)),
                                process(subscription, consumer)),
                        ON_FAILURE_BINDING)
                .get();
    }

    private Subscription subscribe(final Subscription subscription)
            throws InterruptedException, ExecutionException, IOException {
        final Capture<Subscription> capture = Capture.empty();
        rest.post("/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(subscription)
                .dispatch(series(),
                        on(SUCCESSFUL).call(Subscription.class, capture),
                        ON_FAILURE_BINDING)
                .get();
        return capture.retrieve();
    }

    private ThrowingConsumer<ResponseEntity<Stream<Batch>>> process(final Subscription subscription,
            final EventConsumer consumer) {
        return entity -> {
            @SuppressWarnings("resource")
            final Stream<Batch> stream = entity.getBody();
            try {
                final HttpHeaders headers = entity.getHeaders();
                stream.map(batch -> consume(consumer, batch))
                        .filter(cursor -> cursor != null)
                        .forEach(cursor -> commit(subscription, headers, cursor));
            } finally {
                stream.close();
            }
        };
    }

    private Cursor consume(final EventConsumer consumer, final Batch batch) {
        Cursor cursor = batch.getCursor();
        if (cursor == null) {
            LOG.debug("invalid batch: [{}]", batch);
            return cursor;
        }
        List<Event> events = batch.getEvents();
        if (events == null || events.isEmpty()) {
            LOG.debug("empty batch: [{}]", batch);
            return cursor;
        }
        events.forEach(event -> consumer.consume(event));
        return cursor;
    }

    private Cursor[] commit(final Subscription subscription, final HttpHeaders headers, final Cursor... cursor) {
        final Capture<Cursor[]> capture = Capture.empty();
        try {
            rest.put("/subscriptions/{id}/cursors", subscription.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SESSION_HEADER, headers.getFirst(SESSION_HEADER))
                    .body(cursor)
                    .dispatch(series(),
                            on(SUCCESSFUL).call(Cursor[].class, capture),
                            ON_FAILURE_BINDING)
                    .get();
        } catch (ExecutionException ex) {
            throw new GatewayException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException(ex);
        } catch (IOException ex) {
            throw new GatewayException(ex);
        }
        return capture.retrieve();
    }
}