package gr.pnet.em.error;

/**
 * 400: a request that passed schema validation but can't be served as sent.
 * {@code param} names the offending field as a JSON pointer (e.g. {@code /qosReference}), if there is one;
 * it is reported as a 3GPP {@code invalidParams} entry.
 */
public class InvalidRequestException extends RuntimeException {

    private final String param;

    public InvalidRequestException(String message) {
        this(null, message);
    }

    public InvalidRequestException(String param, String message) {
        super(message);
        this.param = param;
    }

    public String getParam() {
        return param;
    }
}
