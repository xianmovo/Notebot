/*
 * Copyright (c) Notebot.
 */

package com.notebot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.notebot.decoder.SongDecoders;
import com.notebot.song.Note;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class NotebotCommand {
    private static boolean recording = false;
    private static int recordTicks = -1;
    private static final Map<Integer, List<Note>> recordSong = new HashMap<>();

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = ClientCommandManager.literal("notebot");

        root.then(ClientCommandManager.literal("help").executes(ctx -> {
            ctx.getSource().sendFeedback(Text.literal("§6=== Notebot Help ===\n" +
                "§e/notebot play <song> §7- Play a song from the notebot folder\n" +
                "§e/notebot preview <song> §7- Preview a song (no note blocks needed)\n" +
                "§e/notebot randomsong §7- Play a random song\n" +
                "§e/notebot stop §7- Stop playback\n" +
                "§e/notebot pause §7- Pause/resume playback\n" +
                "§e/notebot status §7- Show current status\n" +
                "§e/notebot record start §7- Start recording from server sounds\n" +
                "§e/notebot record save <name> §7- Save recording\n" +
                "§e/notebot record cancel §7- Cancel recording\n" +
                "§e/notebot folder §7- Open the songs folder"));
            return 1;
        }));

        root.then(ClientCommandManager.literal("status").executes(ctx -> {
            NotebotModule module = NotebotModule.getInstance();
            ctx.getSource().sendFeedback(Text.literal("§aStatus: §e" + module.getStatus()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("pause").executes(ctx -> {
            NotebotModule.getInstance().pause();
            return 1;
        }));

        root.then(ClientCommandManager.literal("stop").executes(ctx -> {
            NotebotModule.getInstance().stop();
            return 1;
        }));

        root.then(ClientCommandManager.literal("randomsong").executes(ctx -> {
            NotebotModule.getInstance().playRandomSong();
            return 1;
        }));

        root.then(ClientCommandManager.literal("folder").executes(ctx -> {
            try {
                Path folder = NotebotMod.getFolder().resolve("notebot");
                Files.createDirectories(folder);
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"explorer", folder.toAbsolutePath().toString()});
                } else {
                    ctx.getSource().sendFeedback(Text.literal("§aSongs folder: §e" + folder.toAbsolutePath()));
                }
            } catch (Exception e) {
                ctx.getSource().sendError(Text.literal("§cFailed to open folder: " + e.getMessage()));
            }
            return 1;
        }));

        root.then(ClientCommandManager.literal("play").then(
            ClientCommandManager.argument("song", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggestSongs(builder))
                .executes(ctx -> playSong(ctx, false))
        ));

        root.then(ClientCommandManager.literal("preview").then(
            ClientCommandManager.argument("song", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggestSongs(builder))
                .executes(ctx -> playSong(ctx, true))
        ));

        root.then(ClientCommandManager.literal("record").then(
            ClientCommandManager.literal("start").executes(ctx -> {
                recording = true;
                recordTicks = -1;
                recordSong.clear();
                ctx.getSource().sendFeedback(Text.literal("§aRecording started. Play some note block sounds!"));
                return 1;
            })
        ));

        root.then(ClientCommandManager.literal("record").then(
            ClientCommandManager.literal("cancel").executes(ctx -> {
                recording = false;
                recordSong.clear();
                ctx.getSource().sendFeedback(Text.literal("§aRecording cancelled."));
                return 1;
            })
        ));

        root.then(ClientCommandManager.literal("record").then(
            ClientCommandManager.literal("save").then(
                ClientCommandManager.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> saveRecording(ctx))
            )
        ));

        dispatcher.register(root);

        // Register tick for recording
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (recording && recordTicks >= 0) {
                recordTicks++;
            }
        });
    }

    private static CompletableFuture<Suggestions> suggestSongs(SuggestionsBuilder builder) {
        try {
            Path folder = NotebotMod.getFolder().resolve("notebot");
            if (!Files.exists(folder)) Files.createDirectories(folder);
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(SongDecoders::hasDecoder)
                    .map(p -> p.getFileName().toString())
                    .forEach(name -> builder.suggest(name));
            }
        } catch (IOException ignored) {
        }
        return builder.buildFuture();
    }

    private static int playSong(CommandContext<FabricClientCommandSource> ctx, boolean preview) {
        String songName = StringArgumentType.getString(ctx, "song");
        Path folder = NotebotMod.getFolder().resolve("notebot");
        Path songPath = folder.resolve(songName);

        if (!Files.exists(songPath) || !SongDecoders.hasDecoder(songPath)) {
            ctx.getSource().sendError(Text.literal("§cSong not found or unsupported format: " + songName));
            return 0;
        }

        if (preview) {
            NotebotModule.getInstance().previewSong(songPath.toFile());
        } else {
            NotebotModule.getInstance().loadSong(songPath.toFile());
        }
        return 1;
    }

    private static int saveRecording(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Path folder = NotebotMod.getFolder().resolve("notebot");
        Path path = folder.resolve(name + ".txt");

        if (recordSong.isEmpty()) {
            recording = false;
            ctx.getSource().sendFeedback(Text.literal("§cNo sounds recorded."));
            return 0;
        }

        recording = false;
        try {
            Files.createDirectories(folder);
            FileWriter writer = new FileWriter(path.toFile());
            for (var entry : recordSong.entrySet()) {
                int tick = entry.getKey();
                for (var note : entry.getValue()) {
                    NoteBlockInstrument instrument = note.getInstrument();
                    int noteLevel = note.getNoteLevel();
                    writer.write(String.format("%d:%d:%d\n", tick, noteLevel, instrument.ordinal()));
                }
            }
            writer.close();
            recordSong.clear();
            ctx.getSource().sendFeedback(Text.literal("§aSong saved to: §e" + path.getFileName()));
        } catch (IOException e) {
            ctx.getSource().sendError(Text.literal("§cCould not save the file: " + e.getMessage()));
        }
        return 1;
    }

    // Called from the mod's packet mixin
    public static void onNoteBlockSound(PlaySoundS2CPacket soundPacket) {
        if (!recording) return;
        if (recordTicks == -1) recordTicks = 0;

        Note note = getNote(soundPacket);
        if (note != null) {
            recordSong.computeIfAbsent(recordTicks, tick -> new ArrayList<>()).add(note);
        }
    }

    private static Note getNote(PlaySoundS2CPacket soundPacket) {
        float pitch = soundPacket.getPitch();

        int noteLevel = -1;
        for (int n = 0; n < 25; n++) {
            if ((float) Math.pow(2.0D, (n - 12) / 12.0D) - 0.01 < pitch &&
                (float) Math.pow(2.0D, (n - 12) / 12.0D) + 0.01 > pitch) {
                noteLevel = n;
                break;
            }
        }

        if (noteLevel == -1) return null;

        NoteBlockInstrument instrument = getInstrumentFromSound(soundPacket.getSound().value());
        if (instrument == null) return null;

        return new Note(instrument, noteLevel);
    }

    private static NoteBlockInstrument getInstrumentFromSound(SoundEvent sound) {
        Identifier id = Registries.SOUND_EVENT.getId(sound);
        if (id == null) return null;
        String path = id.getPath();
        if (path.contains("harp")) return NoteBlockInstrument.HARP;
        if (path.contains("basedrum")) return NoteBlockInstrument.BASEDRUM;
        if (path.contains("snare")) return NoteBlockInstrument.SNARE;
        if (path.contains("hat")) return NoteBlockInstrument.HAT;
        if (path.contains("bass")) return NoteBlockInstrument.BASS;
        if (path.contains("flute")) return NoteBlockInstrument.FLUTE;
        if (path.contains("bell")) return NoteBlockInstrument.BELL;
        if (path.contains("guitar")) return NoteBlockInstrument.GUITAR;
        if (path.contains("chime")) return NoteBlockInstrument.CHIME;
        if (path.contains("xylophone")) return NoteBlockInstrument.XYLOPHONE;
        if (path.contains("iron_xylophone")) return NoteBlockInstrument.IRON_XYLOPHONE;
        if (path.contains("cow_bell")) return NoteBlockInstrument.COW_BELL;
        if (path.contains("didgeridoo")) return NoteBlockInstrument.DIDGERIDOO;
        if (path.contains("bit")) return NoteBlockInstrument.BIT;
        if (path.contains("banjo")) return NoteBlockInstrument.BANJO;
        if (path.contains("pling")) return NoteBlockInstrument.PLING;
        return null;
    }
}