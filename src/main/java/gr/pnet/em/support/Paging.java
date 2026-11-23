package gr.pnet.em.support;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

/** TEP's limit/offset paging over a Mongo query. limit and offset are already validated by the generated API. */
public final class Paging {

    private Paging() {
    }

    public static <T> Slice<T> page(MongoOperations mongo, Query query, Class<T> type, String collection,
            int limit, int offset) {
        long total = mongo.count(Query.of(query).skip(0).limit(0), type, collection);
        // Sorted by _id so pages don't overlap or skip documents between requests
        Query page = Query.of(query).with(Sort.by("_id")).skip(offset).limit(limit);
        return new Slice<>(mongo.find(page, type, collection), total);
    }
}
