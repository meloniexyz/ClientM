package com.clientm.setting;

/** Picks one of a few named options. Stored as the option index, so it saves and loads like an IntSetting. */
public class ChoiceSetting extends IntSetting {

    public final String[] labels;

    public ChoiceSetting(String id, String name, int value, String... labels) {
        super(id, name, value, 0, labels.length - 1);
        this.labels = labels;
    }

    public String label() {
        return labels[value];
    }

    /** Moves to the next (or previous) option, wrapping round. */
    public void step(int by) {
        value = (value + by + labels.length) % labels.length;
    }
}
