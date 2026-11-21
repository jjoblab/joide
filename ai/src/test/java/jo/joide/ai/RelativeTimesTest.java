package jo.joide.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 0 (séparation Assistant/Agent) : âges relatifs des conversations —
 * minutes sous une heure, heures sous un jour, jours au-delà ; jamais de
 * valeur négative (horloge ajustée entre-temps).
 */
class RelativeTimesTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    @Test
    void freshConversationIsZeroMinutes() {
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.MINUTES, 0),
                RelativeTimes.agoOf(1_000L, 1_000L));
    }

    @Test
    void minutesBelowOneHour() {
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.MINUTES, 30),
                RelativeTimes.agoOf(1_000L, 1_000L + 30 * MINUTE));
    }

    @Test
    void hoursBelowOneDay() {
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.HOURS, 2),
                RelativeTimes.agoOf(1_000L, 1_000L + 2 * HOUR + 5 * MINUTE));
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.HOURS, 23),
                RelativeTimes.agoOf(1_000L, 1_000L + 24 * HOUR - MINUTE));
    }

    @Test
    void daysBeyondOneDay() {
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.DAYS, 1),
                RelativeTimes.agoOf(1_000L, 1_000L + DAY));
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.DAYS, 3),
                RelativeTimes.agoOf(1_000L, 1_000L + 3 * DAY + 2 * HOUR));
    }

    @Test
    void neverNegativeWhenClockGoesBackwards() {
        assertEquals(new RelativeTimes.Ago(RelativeTimes.Unit.MINUTES, 0),
                RelativeTimes.agoOf(1_000L + HOUR, 1_000L));
    }
}
