package gr.pnet.em.tep.subscriber;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;

import gr.pnet.em.error.InvalidRequestException;

/**
 * How a subscriber is found. In the path, by its TEP id: the {@code _id} of its Open5GS document, which an in-place
 * update keeps through SIM swaps and number changes. In a request body, by its phone number (MSISDN), with or without
 * a leading '+': Open5GS stores it as entered (the WebUI without '+', CAMARA clients with it), so both forms match.
 */
public final class SubscriberIds {

    private static final Pattern PHONE_NUMBER = Pattern.compile("^\\+?(\\d{1,15})$");
    /** The JSON pointer of the phone number in every body that carries one (a TepDevice or a TepSubscriber). */
    private static final String PHONE_NUMBER_PARAM = "/phoneNumber";

    private SubscriberIds() {
    }

    /** The TEP id of a subscriber document. */
    public static String id(Object documentId) {
        return documentId instanceof ObjectId objectId ? objectId.toHexString() : String.valueOf(documentId);
    }

    /** Matches the subscriber with this TEP id. */
    public static Criteria byId(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new InvalidRequestException("Invalid subscriber id '" + id + "': expected 24 hex digits");
        }
        return Criteria.where("_id").is(new ObjectId(id));
    }

    /** Matches the subscriber whose msisdn list contains this phone number from a request body, in either form. */
    public static Criteria byPhoneNumber(String phoneNumber) {
        Matcher matcher = PHONE_NUMBER.matcher(phoneNumber == null ? "" : phoneNumber);
        if (!matcher.matches()) {
            throw new InvalidRequestException(PHONE_NUMBER_PARAM, "Invalid phone number '" + phoneNumber
                    + "': expected up to 15 digits, optionally starting with '+'");
        }
        String digits = matcher.group(1);
        return Criteria.where("msisdn").in(digits, "+" + digits);
    }
}
