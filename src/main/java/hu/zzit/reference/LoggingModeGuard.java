package hu.zzit.reference;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast when both console JSON modes are on at once — conflicting flags are a
 * deployment mistake that should surface loudly. Validation lives in {@link PostConstruct}, not the
 * constructor (SpotBugs {@code CT_CONSTRUCTOR_THROW}).
 */
@Component
class LoggingModeGuard {

    private final boolean jsonLogging;
    private final boolean customJsonLogging;

    LoggingModeGuard(
            @Value("${logging.json.enabled:false}") boolean jsonLogging,
            @Value("${logging.custom-json.enabled:false}") boolean customJsonLogging) {
        this.jsonLogging = jsonLogging;
        this.customJsonLogging = customJsonLogging;
    }

    @PostConstruct
    void validate() {
        if (jsonLogging && customJsonLogging) {
            throw new IllegalStateException(
                    "logging.json.enabled and logging.custom-json.enabled are mutually exclusive — enable at most one");
        }
    }
}
