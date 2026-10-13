package com.soubhagya.systivex.intelligence;

/**
 * Direction a stored relationship edge was traversed during impact analysis.
 *
 * <p>{@code FORWARD} follows the stored {@code source -> target} direction
 * (e.g. a service to the API it exposes: a downstream dependency).
 * {@code REVERSE} walks against the stored direction (e.g. from a table back
 * to the service that writes it: a dependent of the changed component).
 * Every reported path step records which one was taken, so downstream
 * dependencies are never silently presented as confirmed breakage.
 */
public enum ImpactDirection {
    FORWARD,
    REVERSE
}
