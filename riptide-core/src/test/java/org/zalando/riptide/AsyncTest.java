package org.zalando.riptide;

import com.google.common.collect.ImmutableMultimap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.zalando.fauxpas.ThrowingConsumer;
import org.zalando.fauxpas.ThrowingRunnable;

import java.net.URI;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.Series.CLIENT_ERROR;
import static org.springframework.http.HttpStatus.Series.SUCCESSFUL;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.zalando.riptide.Bindings.on;
import static org.zalando.riptide.Navigators.series;
import static org.zalando.riptide.Navigators.status;
import static org.zalando.riptide.PassRoute.pass;

final class AsyncTest {

    private final URI url = URI.create("http://localhost");

    private final Http unit;
    private final MockRestServiceServer server;

    AsyncTest() {
        final MockSetup setup = new MockSetup();
        this.unit = setup.getHttp();
        this.server = setup.getServer();
    }

    @Test
    void shouldCall() throws Exception {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingConsumer<ClientHttpResponse, Exception> verifier = mock(
                ThrowingConsumer.class);

        unit.get(url)
                .dispatch(series(),
                        on(SUCCESSFUL).call(verifier))
                .join();

        verify(verifier).tryAccept(any());
    }

    @Test
    void shouldExpand() throws Exception {
        server.expect(requestTo(URI.create("http://localhost/123"))).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingConsumer<ClientHttpResponse, Exception> verifier = mock(
                ThrowingConsumer.class);

        unit.get("http://localhost/{id}", 123)
                .dispatch(series(),
                        on(SUCCESSFUL).call(verifier))
                .join();

        verify(verifier).tryAccept(any());
    }

    @Test
    void shouldCallWithoutParameters() throws Exception {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingRunnable<Exception> verifier = mock(ThrowingRunnable.class);

        unit.get(url)
                .dispatch(series(),
                        on(SUCCESSFUL).call(verifier))
                .join();

        verify(verifier).tryRun();
    }


    @Test
    void shouldCallWithHeaders() throws Exception {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingConsumer<ClientHttpResponse, Exception> verifier = mock(
                ThrowingConsumer.class);

        unit.get(url).headers(ImmutableMultimap.of()).dispatch(series(),
                on(SUCCESSFUL).call(verifier)).join();

        verify(verifier).tryAccept(any());
    }

    @Test
    void shouldCallWithBody() throws Exception {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingConsumer<ClientHttpResponse, Exception> verifier = mock(
                ThrowingConsumer.class);

        unit.get(url).body("test")
                .dispatch(series(),
                        on(SUCCESSFUL).call(verifier))
                .join();

        verify(verifier).tryAccept(any());
    }

    @Test
    void shouldCallWithHeadersAndBody() throws Exception {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final ThrowingConsumer<ClientHttpResponse, Exception> verifier = mock(
                ThrowingConsumer.class);

        unit.get(url)
                .headers(ImmutableMultimap.of())
                .body("test")
                .dispatch(series(),
                        on(SUCCESSFUL).call(verifier))
                .join();

        verify(verifier).tryAccept(any());
    }

    @Test
    void shouldCapture() throws InterruptedException, ExecutionException, TimeoutException {
        server.expect(requestTo(url)).andRespond(
                withSuccess()
                        .body(new ClassPathResource("account.json"))
                        .contentType(APPLICATION_JSON));

        unit.get(url)
                .dispatch(status(),
                        on(OK).call(response -> {
                            assertThat(response.getStatusCode(), is(OK));
                            assertThat(response.getHeaders().getContentType(), is(APPLICATION_JSON));
                        }))
                .get(100, TimeUnit.MILLISECONDS);
    }

    @Test
    void shouldHandleExceptionWithGet() {
        server.expect(requestTo(url)).andRespond(withSuccess());

        final CompletionException exception = assertThrows(CompletionException.class, () ->
                unit.get(url).dispatch(series(),
                        on(CLIENT_ERROR).call(pass()))
                        .join());

        assertThat(exception.getCause(), is(instanceOf(UnexpectedResponseException.class)));
    }

    @Test
    void shouldHandleNoRouteExceptionWithCallback() {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final BiFunction<ClientHttpResponse, Throwable, ClientHttpResponse> callback =
                mock(BiFunction.class);

        unit.get(url)
                .dispatch(series(),
                        on(CLIENT_ERROR).call(pass()))
                .handle(callback)
                .join();

        final ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(callback).apply(eq(null), captor.capture());
        final Exception exception = captor.getValue();

        assertThat(exception, is(instanceOf(CompletionException.class)));
        assertThat(exception.getCause(), is(instanceOf(UnexpectedResponseException.class)));
    }

    @Test
    void shouldIgnoreSuccessWhenHandlingExceptionWithCallback() {
        server.expect(requestTo(url)).andRespond(withSuccess());

        @SuppressWarnings("unchecked") final Consumer<Throwable> callback = mock(Consumer.class);

        unit.get(url)
                .dispatch(series(),
                        on(SUCCESSFUL).call(pass()))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        callback.accept(throwable);
                    }
                }).join();

        verify(callback, never()).accept(any());
    }

}
