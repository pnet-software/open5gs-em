package gr.pnet.em.error;

/** 501: an operation the spec defines but Open5GS can't support. */
public class NotImplementedException extends RuntimeException {

    public NotImplementedException(String message) {
        super(message);
    }
}
