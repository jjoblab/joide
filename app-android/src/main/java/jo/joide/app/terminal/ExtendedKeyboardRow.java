package jo.joide.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Rangée de touches étendue du terminal (Ctrl, Alt, Échap, Tab, flèches —
 * cahier des charges §4.3). Ré-implémentation mince interne : {@code termux-shared}
 * est GPLv3 (ses exceptions MIT ne couvrent pas {@code terminal/io/extrakeys}),
 * son import exposerait du code GPL à l'application — même décision que CodeIDE
 * (ADR-0035, consigne du propriétaire consultée pour ce lot).
 *
 * <p>Les touches directes écrivent des séquences ; Ctrl/Alt sont des bascules
 * persistantes lues par le {@code TerminalViewClient} de la coquille (mécanisme
 * conçu par Termux : {@code readControlKey}/{@code readAltKey}).</p>
 */
public final class ExtendedKeyboardRow extends LinearLayout {

    /** Bascules Ctrl/Alt (lues par le client de vue du terminal). */
    private final boolean controlState = false;
    private final boolean altState = false;

    private final Map<String, Button> toggleButtons = new LinkedHashMap<>();

    public ExtendedKeyboardRow(Context context) {
        super(context);
        init();
    }

    public ExtendedKeyboardRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        directKey("Tab", "\t");
        directKey("Échap", "\u001b");
        directKey("←", "\u001b[D");
        directKey("↓", "\u001b[B");
        directKey("↑", "\u001b[A");
        directKey("→", "\u001b[C");
        toggleKey("Ctrl");
        toggleKey("Alt");
    }

    /** Touche qui écrit une séquence immédiate dans la session. */
    private void directKey(String label, String sequence) {
        Button button = new Button(getContext(), null,
                android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setMinimumWidth(0);
        button.setMinWidth(48);
        button.setOnClickListener(v -> {
            if (writer != null) {
                writer.accept(sequence);
            }
        });
        addView(button, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Bascule Ctrl/Alt : maintenue jusqu'au prochain caractère. */
    private void toggleKey(String label) {
        Button button = new Button(getContext(), null,
                android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setMinimumWidth(0);
        button.setMinWidth(48);
        toggleButtons.put(label, button);
        button.setOnClickListener(v -> {
            heldToggle = label;
            for (Map.Entry<String, Button> entry : toggleButtons.entrySet()) {
                entry.getValue().setActivated(entry.getKey().equals(label));
            }
        });
        addView(button, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Bascule tenue (null : aucune). */
    private String heldToggle;

    /** Écrivain vers la session (câblé par le fragment du terminal). */
    private Consumer<String> writer;

    /** Câble l'écrivain (les touches écrivent dans la session active). */
    public void setWriter(Consumer<String> writer) {
        this.writer = writer;
    }

    /** Bascule tenue : "Ctrl"/"Alt"/null (lue par le client de vue). */
    public String heldToggle() {
        return heldToggle;
    }

    /** Libère la bascule (après le caractère suivant). */
    public void releaseToggle() {
        heldToggle = null;
        for (Button button : toggleButtons.values()) {
            button.setActivated(false);
        }
    }
}
