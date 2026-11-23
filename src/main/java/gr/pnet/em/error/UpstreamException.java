package gr.pnet.em.error;

/** An Open5GS network function (PCF, AMF or SMF metrics) failed or gave an unusable answer. */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
