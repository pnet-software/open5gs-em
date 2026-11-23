package gr.pnet.em.error;

/** 404: the resource, or what the operation needs to answer, doesn't exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
