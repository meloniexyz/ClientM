package com.clientm.setting;

public class IntSetting extends Setting {

    public final int min;
    public final int max;
    public int value;

    public IntSetting(String id, String name, int value, int min, int max) {
        super(id, name);
        this.min = min;
        this.max = max;
        this.value = value;
    }

    public void set(int v) {
        value = Math.max(min, Math.min(max, v));
    }
}
