package com.clientm.setting;

/** A user-adjustable value belonging to a module. */
public abstract class Setting {

    public final String id;
    public final String name;

    protected Setting(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
