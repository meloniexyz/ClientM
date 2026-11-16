package com.clientm.module.impl;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.render.ModelBatch;

/** Draws each player, zombie or armour piece in one draw. The work is in {@link ModelBatch}. */
public class ModelBatchModule extends Module {

    private static ModelBatchModule instance;

    public ModelBatchModule() {
        super("modelbatch", "Batched Models", Category.PERFORMANCE, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Players and armour drawn in one go, not limb by limb";
    }

    public static boolean active() {
        ModelBatchModule m = instance;
        return m != null && m.enabled;
    }
}
