package com.clientm.module.impl;

import com.clientm.compat.PerformancePreset;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.ActionSetting;

/** Menu page with one button per video preset. ClientM applies Low by itself on its first launch. */
public class PerformanceModule extends Module {

    public PerformanceModule() {
        super("performance", "Video Presets", Category.PERFORMANCE, true);
        for (PerformancePreset p : PerformancePreset.values()) {
            add(new ActionSetting(p.name(), p.label + ": " + p.description, "Apply", p::apply));
        }
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    @Override
    public String description() {
        PerformancePreset last = PerformancePreset.last;
        return last == null ? "Slowest-looking to best-looking. Low is the first-launch default"
            : "Applied: " + last.label;
    }
}
