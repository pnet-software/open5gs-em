package gr.pnet.em.tep.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import gr.pnet.em.error.InvalidRequestException;

class SubscriberIdsTest {

    private static final String ID = "6731f0c2a4b5c6d7e8f90123";

    @Test
    void theIdIsTheDocumentsObjectIdInHex() {
        assertThat(SubscriberIds.id(new ObjectId(ID))).isEqualTo(ID);
        assertThat(SubscriberIds.byId(ID).getCriteriaObject().get("_id")).isEqualTo(new ObjectId(ID));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "306900000001", "+306900000001", "6731f0c2a4b5c6d7e8f9012", "6731f0c2a4b5c6d7e8f9012g" })
    void rejectsAnIdThatIsNotAnObjectId(String id) {
        assertThatThrownBy(() -> SubscriberIds.byId(id))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getParam()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = { "306900000001", "+306900000001" })
    void matchesThePhoneNumberWithAndWithoutPlus(String phoneNumber) {
        assertThat(SubscriberIds.byPhoneNumber(phoneNumber).getCriteriaObject().toJson())
                .contains("\"306900000001\"", "\"+306900000001\"");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "abc", "++306900000001", "3069 0000001", "1234567890123456" })
    void rejectsAPhoneNumberThatIsNotUpTo15DigitsAndNamesItsBodyField(String phoneNumber) {
        assertThatThrownBy(() -> SubscriberIds.byPhoneNumber(phoneNumber))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getParam()).isEqualTo("/phoneNumber"));
    }
}
