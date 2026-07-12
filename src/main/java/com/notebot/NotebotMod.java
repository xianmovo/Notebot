/*
 * Copyright (c) Notebot.
 *
 * Main mod entry point for the Notebot Fabric mod.
 */

package com.notebot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

public class NotebotMod implements ClientModInitializer {
    private static Path folder;

    @Override
    public void onInitializeClient() {
        MinecraftClient mc = MinecraftClient.getInstance();
        folder = mc.runDirectory.toPath().resolve("notebot");

        // Load config
        NotebotConfig config = NotebotConfig.load(folder.resolve("config.json"));
        NotebotModule.getInstance().setConfig(config);

        // Register tick handler
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            NotebotModule.getInstance().onTick();
        });

        // Register command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            NotebotCommand.register(dispatcher);
        });
    }

    public static Path getFolder() {
        return folder;
    }
}