package jo.joide.corestore;

import jo.joide.workbench.ModeTravail;

/** Choix de mode persisté (cahier des charges §4.1, ADR-002). */
public record ChoixMode(ModeTravail modeChoisi, boolean modeInstalle) {}
