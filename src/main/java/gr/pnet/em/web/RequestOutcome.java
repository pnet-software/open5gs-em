package gr.pnet.em.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The exception a request was answered with a problem for, beyond its HTTP status: recorded by the error handler and
 * read by the access log once the request is done.
 */
public final class RequestOutcome {

    static final String TYPE = RequestOutcome.class.getName() + ".type";

    private RequestOutcome() {
    }

    /** Records the exception behind the problem the request this thread is serving is answered with, if any. */
    public static void error(Throwable cause) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && cause != null) {
            attributes.setAttribute(TYPE, cause.getClass().getSimpleName(), RequestAttributes.SCOPE_REQUEST);
        }
    }

    public static String type(HttpServletRequest request) {
        return (String) request.getAttribute(TYPE);
    }
}
