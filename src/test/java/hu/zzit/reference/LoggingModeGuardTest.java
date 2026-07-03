package hu.zzit.reference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LoggingModeGuardTest {

    @Test
    void rejectsBothJsonModesAtOnce() {
        assertThrows(IllegalStateException.class, () -> new LoggingModeGuard(true, true).validate());
    }

    @Test
    void acceptsAtMostOneJsonMode() {
        assertDoesNotThrow(() -> new LoggingModeGuard(false, false).validate());
        assertDoesNotThrow(() -> new LoggingModeGuard(true, false).validate());
        assertDoesNotThrow(() -> new LoggingModeGuard(false, true).validate());
    }
}
