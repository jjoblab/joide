package jo.joide.app.drawer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import jo.joide.app.R;

/**
 * Guides d'indentation de l'arborescence (Lot 2 du tiroir) : un trait
 * vertical par niveau de profondeur, aligné sous le chevron du parent —
 * convention reprenant l'esprit des guides des éditeurs de code, dessin
 * maison. Le guide du dossier du fichier **actif** est mis en évidence
 * (primaire, plus épais) : le regard remonte à la filiation d'un coup.
 *
 * <p>La vue mesure sa largeur au nombre de niveaux ; hauteur = rangée
 * entière. Pur dessin (aucun enfant, aucun bitmap) — bon marché même sur
 * de longues listes.</p>
 */
public final class IndentGuidesView extends View {

    private int depth;
    private int highlightedLevel = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int indentPx;
    private final float density;

    public IndentGuidesView(Context context) {
        this(context, null);
    }

    public IndentGuidesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        indentPx = Math.round(getResources().getDimension(R.dimen.tree_indent));
        paint.setStrokeWidth(Math.max(1f, density));
    }

    /** Nombre de niveaux à dessiner (0 = aucun guide). */
    public void setDepth(int value) {
        if (depth != value) {
            depth = Math.max(0, value);
            requestLayout();
        }
    }

    /**
     * Niveau mis en évidence (dossier du fichier actif), −1 pour aucun.
     * Les guides vont de 0 (racine) à {@code depth - 1} (parent direct).
     */
    public void setHighlightedLevel(int level) {
        highlightedLevel = level;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = depth * indentPx;
        setMeasuredDimension(width, MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (depth <= 0) {
            return;
        }
        // Trait du guide k : centré sous le chevron du parent de niveau k.
        for (int level = 0; level < depth; level++) {
            boolean active = level == highlightedLevel;
            paint.setColor(active
                    ? getColorWithAlpha(R.color.primaire, 0.65f)
                    : getColorWithAlpha(R.color.contour_var, 0.55f));
            paint.setStrokeWidth(active ? 2f * density : Math.max(1f, density));
            float x = level * indentPx + indentPx / 2f;
            canvas.drawLine(x, 0f, x, getHeight(), paint);
        }
    }

    private int getColorWithAlpha(int resId, float alpha) {
        int color = getResources().getColor(resId);
        return Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
