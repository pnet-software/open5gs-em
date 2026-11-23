package gr.pnet.em.tep.subscriber;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.ResourceConflictException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepSubscriber;
import gr.pnet.em.generated.tep.model.TepSubscriberRef;
import gr.pnet.em.support.Paging;
import gr.pnet.em.support.Slice;

/** Subscribers, straight from the Open5GS {@code subscribers} collection. */
@Service
public class SubscriberService {

    static final String COLLECTION = "subscribers";

    private final MongoOperations mongo;

    public SubscriberService(MongoOperations mongo) {
        this.mongo = mongo;
    }

    public Slice<TepSubscriberRef> list(int limit, int offset) {
        Query query = new Query();
        query.fields().include("imsi", "msisdn");
        return Paging.page(mongo, query, Document.class, COLLECTION, limit, offset)
                .map(Open5gsSubscriberMapper::toRef);
    }

    /** The Open5GS document of the subscriber with this TEP id, as stored. */
    public Document find(String id) {
        Document subscriber = mongo.findOne(new Query(SubscriberIds.byId(id)), Document.class, COLLECTION);
        if (subscriber == null) {
            throw new ResourceNotFoundException("Subscriber " + id + " not found");
        }
        return subscriber;
    }

    /** The Open5GS document of the subscriber with this phone number (from a request body), as stored. */
    public Document findByPhoneNumber(String phoneNumber) {
        Document subscriber = mongo.findOne(new Query(SubscriberIds.byPhoneNumber(phoneNumber)), Document.class,
                COLLECTION);
        if (subscriber == null) {
            throw new ResourceNotFoundException("No subscriber has phone number " + phoneNumber);
        }
        return subscriber;
    }

    public TepSubscriber get(String id) {
        return Open5gsSubscriberMapper.toTep(find(id));
    }

    public TepSubscriber getByPhoneNumber(String phoneNumber) {
        return Open5gsSubscriberMapper.toTep(findByPhoneNumber(phoneNumber));
    }

    /** Creates the subscriber and answers its TEP id. */
    public String create(TepSubscriber subscriber) {
        if (mongo.exists(new Query(sameIdentity(subscriber)), COLLECTION)) {
            throw new ResourceConflictException("A subscriber with phone number " + subscriber.getPhoneNumber()
                    + " or IMSI " + subscriber.getImsi() + " already exists");
        }
        Document document = Open5gsSubscriberMapper.newSubscriber(subscriber);
        // Open5GS's unique index on imsi still catches a concurrent create (DuplicateKeyException, 409)
        mongo.insert(document, COLLECTION);
        return SubscriberIds.id(document.get("_id"));
    }

    /**
     * Sets only what TEP owns (IMSI, phone number, K/AMF/OPc and the slices) and leaves everything else as it is:
     * the IMEISV that device-swap detection reads, the AMBR, and the QoS and PCC rules of existing sessions. The
     * document is updated in place, so it keeps its {@code _id}, and the subscriber its TEP id.
     */
    public void update(String id, TepSubscriber subscriber) {
        Document existing = find(id);
        Object key = existing.get("_id");
        Criteria clash = new Criteria().andOperator(Criteria.where("_id").ne(key), sameIdentity(subscriber));
        if (mongo.exists(new Query(clash), COLLECTION)) {
            throw new ResourceConflictException("Another subscriber already has phone number "
                    + subscriber.getPhoneNumber() + " or IMSI " + subscriber.getImsi());
        }
        Update update = new Update()
                .set("imsi", subscriber.getImsi())
                .set("msisdn", Open5gsSubscriberMapper.replaceFirstMsisdn(existing, subscriber.getPhoneNumber()))
                .set("security.k", subscriber.getPermanentKeyValue())
                .set("security.amf", subscriber.getAuthenticationManagementField())
                .set("security.opc", subscriber.getOpcValue())
                .set("slice", Open5gsSubscriberMapper.mergeSlices(
                        Open5gsSubscriberMapper.documents(existing, "slice"), subscriber.getDataNetworks()));
        mongo.updateFirst(new Query(Criteria.where("_id").is(key)), update, COLLECTION);
    }

    public void delete(String id) {
        Document existing = find(id);
        mongo.remove(new Query(Criteria.where("_id").is(existing.get("_id"))), COLLECTION);
    }

    private static Criteria sameIdentity(TepSubscriber subscriber) {
        return new Criteria().orOperator(
                SubscriberIds.byPhoneNumber(subscriber.getPhoneNumber()),
                Criteria.where("imsi").is(subscriber.getImsi()));
    }
}
