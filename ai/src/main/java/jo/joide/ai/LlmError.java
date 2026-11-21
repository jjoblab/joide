package jo.joide.ai;

/**
 * Erreur LLM typée (Lot 1) : chaque cause a son message et son action
 * suggérée — fini le texte de la 401 pour toute erreur. Ne contient
 * JAMAIS la clé : les détails sont rédigés par {@link Secrets#redact}
 * avant d'entrer dans le message.
 */
public final class LlmError extends Exception {

    /** Causes connues (une action chacune, côté UI). */
    public enum Kind {
        /** Clé absente, refusée (401/403) — vérifier la clé dans les réglages. */
        AUTH("Clé refusée ou absente",
                "Vérifiez la clé du fournisseur dans Réglages IA → Fournisseurs."),
        /** Limite de débit (429) — réessayer après le délai indiqué. */
        RATE_LIMIT("Limite de débit atteinte",
                "Réessayez dans le délai indiqué, ou changez de modèle."),
        /** Crédits insuffisants (402) — recharger ou modèle gratuit. */
        INSUFFICIENT_CREDITS("Crédits insuffisants",
                "Rechargez le compte du fournisseur, ou choisissez un modèle gratuit."),
        /** Fenêtre de contexte dépassée (413, message d'erreur explicite). */
        CONTEXT_TOO_LONG("Contexte trop long",
                "Compactez la conversation ou démarrez une nouvelle conversation."),
        /** Modèle introuvable/indisponible (404, liste de modèles du fournisseur). */
        MODEL_UNAVAILABLE("Modèle introuvable",
                "Choisissez un autre modèle dans la liste du fournisseur."),
        /** Requête malformée (400 hors cas connus). */
        BAD_REQUEST("Requête refusée",
                "Le fournisseur a refusé la requête ; vérifiez le modèle et les paramètres."),
        /** Réseau absent/interrompu. */
        NETWORK("Réseau indisponible",
                "Vérifiez la connexion, puis réessayez."),
        /** Délai dépassé sans réponse. */
        TIMEOUT("Délai dépassé",
                "Le fournisseur n'a pas répondu ; réessayez."),
        /** Erreur du fournisseur (5xx). */
        SERVER("Fournisseur indisponible",
                "Erreur du côté du fournisseur ; réessayez dans un instant."),
        /** Annulé par l'utilisateur. */
        CANCELLED("Génération annulée", null),
        /** Erreur envoyée par le fournisseur au milieu du flux SSE. */
        MID_STREAM("Erreur pendant la génération",
                "Le fournisseur a interrompu la génération ; réessayez.");

        private final String title;
        private final String action;

        Kind(String title, String action) {
            this.title = title;
            this.action = action;
        }

        /** Titre lisible (carte d'erreur). */
        public String title() {
            return title;
        }

        /** Action suggérée (null pour l'annulation : rien à faire). */
        public String action() {
            return action;
        }
    }

    private final Kind kind;
    private final long retryAfterSeconds;
    private final String detail;

    private LlmError(Kind kind, String detail, long retryAfterSeconds) {
        super(kind.title() + (detail == null || detail.isEmpty()
                ? "" : " — " + detail));
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Erreur avec détail rédigé (jamais de secret dans le détail). */
    public static LlmError of(Kind kind, String detail) {
        return new LlmError(kind, detail, 0);
    }

    /** Erreur de limite de débit avec délai de reprise (Retry-After, secondes). */
    public static LlmError rateLimited(String detail, long retryAfterSeconds) {
        return new LlmError(Kind.RATE_LIMIT, detail, Math.max(0, retryAfterSeconds));
    }

    /**
     * Classe un statut HTTP + corps en erreur typée. Le corps est borné et
     * rédigé avant d'entrer dans le message.
     */
    public static LlmError fromStatus(int status, String body, String retryAfterHeader) {
        String detail = Secrets.redact(truncate(body));
        switch (status) {
            case 401, 403 -> {
                return of(Kind.AUTH, detail);
            }
            case 402 -> {
                return of(Kind.INSUFFICIENT_CREDITS, detail);
            }
            case 404 -> {
                return of(Kind.MODEL_UNAVAILABLE, detail);
            }
            case 408 -> {
                return of(Kind.TIMEOUT, detail);
            }
            case 413 -> {
                return of(Kind.CONTEXT_TOO_LONG, detail);
            }
            case 429 -> {
                return rateLimited(detail, parseRetryAfter(retryAfterHeader));
            }
            default -> {
                if (status >= 400 && status < 500) {
                    if (body != null && (body.contains("context")
                            && (body.contains("length") || body.contains("token")))) {
                        return of(Kind.CONTEXT_TOO_LONG, detail);
                    }
                    return of(Kind.BAD_REQUEST, detail);
                }
                return of(Kind.SERVER, detail);
            }
        }
    }

    /** Délai de reprise indiqué par le fournisseur (Retry-After), 0 si inconnu. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Détail brut rédigé (déjà sans secret). */
    public String detail() {
        return detail;
    }

    /** Cause typée. */
    public Kind kind() {
        return kind;
    }

    /** Retry-After : secondes, ou date HTTP (rare) convertie, ou 0. */
    private static long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            try {
                long date = java.time.Instant.parse(header.trim().replace(" ", "T")).getEpochSecond();
                return Math.max(0, date - java.time.Instant.now().getEpochSecond());
            } catch (Exception parseFailure) {
                return 0;
            }
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String compact = body.replace('\n', ' ').trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "…" : compact;
    }
}
