package dev.starfold.ship;

/** Whether the most recent same-address write reached Sable's storage file. */
public interface SableShipStorageAccess {
    boolean starfold$writeSucceeded();
}
