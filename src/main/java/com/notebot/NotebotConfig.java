/*
 * Copyright (c) Notebot.
 */

package com.notebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.notebot.instrumentdetect.InstrumentDetectMode;
import net.minecraft.block.enums.NoteBlockInstrument;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class NotebotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Expose public int tickDelay = 0;
    @Expose public int concurrentTuneBlocks = 1;
    @Expose public NotebotUtils.NotebotMode mode = NotebotUtils.NotebotMode.ExactInstruments;
    @Expose public InstrumentDetectMode instrumentDetectMode = InstrumentDetectMode.BlockState;
    @Expose public boolean polyphonic = true;
    @Expose public boolean autoRotate = true;
    @Expose public boolean swingArm = true;
    @Expose public boolean roundOutOfRange = false;
    @Expose public int scanRadius = 10;
    @Expose public boolean render = true;
    @Expose public Map<String, NotebotUtils.OptionalInstrument> instrumentMap = new LinkedHashMap<>();

    public NotebotConfig() {
        // Initialize instrument map with defaults
        for (NoteBlockInstrument inst : NoteBlockInstrument.values()) {
            instrumentMap.put(inst.name(), NotebotUtils.OptionalInstrument.fromMinecraftInstrument(inst));
        }
    }

    @Nullable
    public NoteBlockInstrument getMappedInstrument(NoteBlockInstrument inst) {
        if (inst == null) return null;
        NotebotUtils.OptionalInstrument opt = instrumentMap.get(inst.name());
        if (opt != null) {
            return opt.toMinecraftInstrument();
        }
        return inst;
    }

    public static NotebotConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                NotebotConfig config = GSON.fromJson(reader, NotebotConfig.class);
                if (config != null) {
                    // Ensure all instrument map entries exist
                    if (config.instrumentMap == null) config.instrumentMap = new LinkedHashMap<>();
                    for (NoteBlockInstrument inst : NoteBlockInstrument.values()) {
                        config.instrumentMap.putIfAbsent(inst.name(),
                            NotebotUtils.OptionalInstrument.fromMinecraftInstrument(inst));
                    }
                    return config;
                }
            } catch (IOException e) {
                System.err.println("Failed to load Notebot config: " + e.getMessage());
            }
        }
        return new NotebotConfig();
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save Notebot config: " + e.getMessage());
        }
    }
}
