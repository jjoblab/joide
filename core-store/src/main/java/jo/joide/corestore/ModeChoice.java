package jo.joide.corestore;

import jo.joide.workbench.WorkMode;

/** Choix de mode persisté (cahier des charges §4.1, ADR-002). */
public record ModeChoice(WorkMode chosenMode, boolean modeInstalled) {}
