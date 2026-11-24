package jo.joide.app.diag;

import android.util.Log;
import jo.joide.app.BuildConfig;
import jo.joide.diag.FlattenedException;
import jo.joide.diag.LogEntry;
import jo.joide.diag.LogLevel;
import jo.joide.diag.LogSink;

/**
 * Sink logcat du pipeline (portage CodeIDE §5.7, ADR-021) : DEBUG+ en build
 * debug, WARN+ en build release — le seuil dépend du type de build. C'est
 * l'un des deux seuls endroits de l'application autorisés à appeler
 * {@link android.util.Log} ; tout le reste journalise via la façade.
 */
public final class LogcatSink implements LogSink {

    private final LogLevel minLevel;

    public LogcatSink() {
        this(BuildConfig.DEBUG ? LogLevel.DEBUG : LogLevel.WARN);
    }

    public LogcatSink(LogLevel minLevel) {
        this.minLevel = minLevel;
    }

    @Override
    public LogLevel minLevel() {
        return minLevel;
    }

    @Override
    public void write(LogEntry entry) {
        String text = entry.exception() == null
                ? entry.message()
                : entry.message() + "\n" + formatAsText(entry.exception());
        switch (entry.level()) {
            case DEBUG -> Log.d(entry.tag(), text);
            case INFO -> Log.i(entry.tag(), text);
            case WARN -> Log.w(entry.tag(), text);
            case ERROR -> Log.e(entry.tag(), text);
        }
    }

    /** Trace aplatie lisible sous le message (convention des rapports). */
    private static String formatAsText(FlattenedException exception) {
        return exception.formatAsText();
    }
}
