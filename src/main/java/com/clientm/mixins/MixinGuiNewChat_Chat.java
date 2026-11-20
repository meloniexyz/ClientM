package com.clientm.mixins;

import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.ChatModule;
import com.llamalad7.mixinextras.injector.WrapWithCondition;

/** The Chat module: hiding the chat, its background, text shadow, timestamps and stacked repeated messages. */
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat_Chat {

    @Shadow
    public abstract void printChatMessageWithOptionalDeletion(IChatComponent message, int id);

    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), cancellable = true)
    private void clientm$rewrite(IChatComponent message, int id, CallbackInfo ci) {
        Object[] out = ChatModule.rewrite(message, id);
        if (out == null) return;
        ci.cancel();
        ChatModule.printing(true);
        try {
            printChatMessageWithOptionalDeletion((IChatComponent) out[0], (Integer) out[1]);
        } finally {
            ChatModule.printing(false);
        }
    }

    /** "Show chat on screen" off: nothing is drawn unless the chat screen is open. */
    @Inject(method = "drawChat", at = @At("HEAD"), cancellable = true)
    private void clientm$hide(int updateCounter, CallbackInfo ci) {
        if (!ChatModule.visible()) ci.cancel();
    }

    @Shadow
    public abstract boolean getChatOpen();

    /**
     * The dark box behind each chat line (the first drawRect; the other two draw the scroll bar): shown while the chat
     * is open, and while it is closed only with "Chat background" on. (Hodgepodge's own "transparent chat" is switched
     * off by the launcher, so this is the one switch.)
     */
    @WrapWithCondition(
        method = "drawChat",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V", ordinal = 0))
    private boolean clientm$background(int left, int top, int right, int bottom, int color) {
        return ChatModule.background() || getChatOpen();
    }

    @Redirect(
        method = "drawChat",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;III)I"))
    private int clientm$shadow(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        return ChatModule.shadow() ? font.drawStringWithShadow(text, x, y, color) : font.drawString(text, x, y, color);
    }

}
