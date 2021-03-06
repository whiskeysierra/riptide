package org.zalando.riptide.opentracing.span;

import io.opentracing.Span;
import io.opentracing.tag.Tags;
import org.zalando.riptide.RequestArguments;

/**
 * Sets the <code>http.method</code> span tag.
 *
 * @see <a href="https://opentracing.io/specification/conventions/#span-tags-table">Standard Span Tags</a>
 */
public final class HttpMethodSpanDecorator implements SpanDecorator {

    @Override
    public void onRequest(final Span span, final RequestArguments arguments) {
        span.setTag(Tags.HTTP_METHOD, arguments.getMethod().name());
    }

}
