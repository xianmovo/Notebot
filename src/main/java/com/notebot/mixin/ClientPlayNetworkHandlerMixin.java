/*
 * Copyright (c) Notebot.
 */

package com.notebot.mixin;

import com.notebot.NotebotCommand;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onPlaySound", at = @At("TAIL"))
    private void onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        if (packet.getSound().value().id().getPath().contains("note_block")) {
            NotebotCommand.onNoteBlockSound(packet);
        }
    }
}
