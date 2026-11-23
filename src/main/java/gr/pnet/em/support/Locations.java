package gr.pnet.em.support;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The 201 every TEP create answers with: no body, and a Location of the new resource under the request's URL. */
public final class Locations {

    private Locations() {
    }

    public static <T> ResponseEntity<T> created(String id) {
        return ResponseEntity.created(childOfCurrentRequest(id)).build();
    }

    public static URI childOfCurrentRequest(String id) {
        return ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
    }
}
