package com.clientm.setting;

/** A button in a module's settings list. It holds no value and is not saved. */
public class ActionSetting extends Setting {

    public final String button;
    public final Runnable action;

    public ActionSetting(String id, String name, String button, Runnable action) {
        super(id, name);
        this.button = button;
        this.action = action;
    }
}
