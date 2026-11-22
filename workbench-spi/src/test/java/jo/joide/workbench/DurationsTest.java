package jo.joide.workbench;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Durées de build : décimes sous 10 s, secondes entières sous la minute,
 * minutes+secondes, heures+minutes — jamais de valeur négative.
 */
class DurationsTest {

    @Test
    void decimalsUnderTenSeconds() {
        assertEquals("0,9 s", Durations.format(943, Locale.FRANCE));
        assertEquals("2,4 s", Durations.format(2401, Locale.FRANCE));
        assertEquals("8,8 s", Durations.format(8799, Locale.FRANCE));
    }

    @Test
    void plainSecondsUnderAMinute() {
        assertEquals("18 s", Durations.format(18_020, Locale.FRANCE));
        assertEquals("59 s", Durations.format(58_720, Locale.FRANCE));
        // 59,999 s arrondit à la minute entière.
        assertEquals("1 min", Durations.format(59_999, Locale.FRANCE));
    }

    @Test
    void minutesAndSeconds() {
        assertEquals("1 min 02 s", Durations.format(62_000, Locale.FRANCE));
        assertEquals("1 min 12 s", Durations.format(72_400, Locale.FRANCE));
        assertEquals("2 min 05 s", Durations.format(125_000, Locale.FRANCE));
    }

    @Test
    void wholeMinutesStayCompact() {
        assertEquals("3 min", Durations.format(180_000, Locale.FRANCE));
    }

    @Test
    void hoursAndMinutes() {
        assertEquals("1 h 04 min", Durations.format(3_842_000, Locale.FRANCE));
        assertEquals("2 h", Durations.format(7_200_000, Locale.FRANCE));
    }

    @Test
    void negativeOrNullDurationsNeverShowBelowZero() {
        assertEquals("0,0 s", Durations.format(0, Locale.FRANCE));
        assertEquals("0,0 s", Durations.format(-250, Locale.FRANCE));
    }

    @Test
    void englishLocaleUsesTheDot() {
        assertEquals("2.4 s", Durations.format(2401, Locale.ENGLISH));
    }
}
