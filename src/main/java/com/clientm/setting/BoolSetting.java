package com.clientm.setting;

public class BoolSetting extends Setting {

    public boolean value;

    public BoolSetting(String id, String name, boolean value) {
        super(id, name);
        this.value = value;
    }
}
