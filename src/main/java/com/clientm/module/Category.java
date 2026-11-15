package com.clientm.module;

public enum Category {

    HUD("HUD"),
    VISUAL("Visual"),
    MOVEMENT("Movement"),
    PERFORMANCE("Performance");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
