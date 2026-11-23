package gr.pnet.em.error;

/** 409: a resource with the same identifier already exists. */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
