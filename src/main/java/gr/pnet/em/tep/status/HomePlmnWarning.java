package gr.pnet.em.tep.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Says at startup when no home PLMNs are configured, since the subscriber status then never says roaming. */
@Component
class HomePlmnWarning {

    private static final Logger log = LoggerFactory.getLogger(HomePlmnWarning.class);

    private final HomePlmnProperties homePlmns;

    HomePlmnWarning(HomePlmnProperties homePlmns) {
        this.homePlmns = homePlmns;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfNone() {
        if (homePlmns.plmns().isEmpty()) {
            log.warn("No home PLMNs configured (tep.home-plmns): subscriber statuses won't say whether a UE is roaming");
        } else {
            log.info("Home PLMNs: {}", homePlmns.homePlmns());
        }
    }
}
