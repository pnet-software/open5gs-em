package gr.pnet.em.support;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import gr.pnet.em.error.ResourceConflictException;
import gr.pnet.em.error.ResourceNotFoundException;

/**
 * CRUD for a collection whose documents are keyed by the resource's own id (_id = cell id, profile name, …),
 * with TEP's not-found and conflict semantics. {@code label} names the resource in error details.
 */
public class MongoStore<T> {

    private final MongoOperations mongo;
    private final Class<T> type;
    private final String collection;
    private final String label;

    public MongoStore(MongoOperations mongo, Class<T> type, String label) {
        this.mongo = mongo;
        this.type = type;
        this.collection = mongo.getCollectionName(type);
        this.label = label;
    }

    public Slice<T> page(int limit, int offset) {
        return Paging.page(mongo, new Query(), type, collection, limit, offset);
    }

    public T get(String id) {
        T document = mongo.findById(id, type, collection);
        if (document == null) {
            throw notFound(id);
        }
        return document;
    }

    public void insert(String id, T document) {
        if (mongo.exists(byId(id), type, collection)) {
            throw new ResourceConflictException(label + " " + id + " already exists");
        }
        // A concurrent insert still fails on the _id index, as a DuplicateKeyException (409)
        mongo.insert(document, collection);
    }

    public void replace(String id, T document) {
        if (!mongo.exists(byId(id), type, collection)) {
            throw notFound(id);
        }
        mongo.save(document, collection);
    }

    public void delete(String id) {
        if (mongo.remove(byId(id), type, collection).getDeletedCount() == 0) {
            throw notFound(id);
        }
    }

    private ResourceNotFoundException notFound(String id) {
        return new ResourceNotFoundException(label + " " + id + " not found");
    }

    private static Query byId(String id) {
        return new Query(Criteria.where("_id").is(id));
    }
}
