package gr.pnet.em.nef.qossession;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

interface QosSessionRepository extends MongoRepository<QosSessionDocument, String> {

    List<QosSessionDocument> findAllByScsAsId(String scsAsId);

    Optional<QosSessionDocument> findByScsAsIdAndId(String scsAsId, String id);

    /** The sessions of every SCS/AS on this UE address, and so on its PDU session. */
    List<QosSessionDocument> findAllBySubscription_UeIpv4Addr(String ueIpv4Addr);
}
