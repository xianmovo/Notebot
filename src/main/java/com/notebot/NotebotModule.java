/*
 * Copyright (c) Notebot.
 *
 * Core notebot module. Plays songs using note blocks in Minecraft.
 */

package com.notebot;

import com.notebot.decoder.SongDecoders;
import com.notebot.instrumentdetect.InstrumentDetectMode;
import com.notebot.song.Note;
import com.notebot.song.Song;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class NotebotModule {
    private static NotebotModule INSTANCE;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Path songsFolder;
    private NotebotConfig config;

    // State
    private Stage stage = Stage.None;
    private PlayingMode playingMode = PlayingMode.None;
    private Song song;
    private int currentTick;
    private int ticks = 0;
    private final Map<Note, BlockPos> noteBlockPositions = new HashMap<>();
    private final Map<BlockPos, Integer> tuneHits = new LinkedHashMap<>();
    private final Set<BlockPos> clickedBlocks = new HashSet<>();
    private boolean anyNoteblockTuned;

    // Timer for loading stage
    private Timer loadingTimer;

    public static NotebotModule getInstance() {
        if (INSTANCE == null) INSTANCE = new NotebotModule();
        return INSTANCE;
    }

    private NotebotModule() {
        songsFolder = NotebotMod.getFolder().resolve("notebot");
    }

    public void setConfig(NotebotConfig config) {
        this.config = config;
    }

    public NotebotConfig getConfig() {
        return config;
    }

    public Path getSongsFolder() {
        return songsFolder;
    }

    // ---- Public API ----

    public void loadSong(File file) {
        if (!SongDecoders.hasDecoder(file)) {
            sendMessage("§cUnknown file format.");
            return;
        }

        setStage(Stage.LoadingSong);
        sendMessage(String.format("§aLoading song §6%s§a.", file.getName()));

        try {
            song = SongDecoders.parse(file, config);
        } catch (Exception e) {
            sendMessage("§cFailed to load song: " + e.getMessage());
            setStage(Stage.None);
            return;
        }

        sendMessage(String.format("§aSong loaded: §6%s §7(by %s) §aLength: §6%d §aticks",
            song.getTitle(), song.getAuthor(), song.getLastTick()));

        setupNoteblocks(song.getRequirements());
    }

    public void previewSong(File file) {
        if (!SongDecoders.hasDecoder(file)) {
            sendMessage("§cUnknown file format.");
            return;
        }

        playingMode = PlayingMode.Preview;

        setStage(Stage.LoadingSong);
        sendMessage(String.format("§aLoading preview for §6%s§a.", file.getName()));

        try {
            song = SongDecoders.parse(file, config);
        } catch (Exception e) {
            sendMessage("§cFailed to load song: " + e.getMessage());
            setStage(Stage.None);
            playingMode = PlayingMode.None;
            return;
        }

        setupPreview();
    }

    public void playRandomSong() {
        try {
            Files.createDirectories(songsFolder);
            File[] files = songsFolder.toFile().listFiles((dir, name) -> SongDecoders.hasDecoder(songsFolder.resolve(name)));

            if (files == null || files.length == 0) {
                sendMessage("§cNo song files found in the notebot folder.");
                return;
            }

            File randomFile = files[ThreadLocalRandom.current().nextInt(files.length)];
            loadSong(randomFile);
        } catch (Exception e) {
            sendMessage("§cError: " + e.getMessage());
        }
    }

    public void stop() {
        if (loadingTimer != null) {
            loadingTimer.cancel();
            loadingTimer = null;
        }
        setStage(Stage.None);
        playingMode = PlayingMode.None;
        song = null;
        currentTick = 0;
        ticks = 0;
        clickedBlocks.clear();
        tuneHits.clear();
        noteBlockPositions.clear();
        anyNoteblockTuned = false;

        sendMessage("§aStopped.");
    }

    public void pause() {
        if (stage == Stage.Playing) {
            setStage(Stage.None);
            sendMessage("§aPaused. Use /notebot resume to continue.");
        } else if (stage == Stage.None && song != null && playingMode != PlayingMode.None) {
            play();
            sendMessage("§aResumed.");
        }
    }

    public String getStatus() {
        if (stage == Stage.None) return "Idle";
        if (stage == Stage.LoadingSong) return "Loading song...";
        if (stage == Stage.SetUp) return "Setting up...";
        if (stage == Stage.Tune) return String.format("Tuning... (%.1f%%)", getTuningProgress());
        if (stage == Stage.WaitingToCheckNoteblocks) return "Waiting to check noteblocks...";
        if (stage == Stage.Playing) {
            if (playingMode == PlayingMode.Preview) return String.format("Previewing... (%d/%d)", currentTick, song != null ? song.getLastTick() : 0);
            return String.format("Playing... (%d/%d)", currentTick, song != null ? song.getLastTick() : 0);
        }
        return "Unknown";
    }

    public Stage getStage() {
        return stage;
    }

    public boolean isPlaying() {
        return song != null && playingMode != PlayingMode.None;
    }

    // ---- Tick ----

    public void onTick() {
        if (stage == Stage.LoadingSong || stage == Stage.SetUp) return;

        if (playingMode == PlayingMode.Preview) {
            onTickPreview();
        } else if (playingMode == PlayingMode.Noteblocks) {
            onTickNoteblocks();
        }
    }

    // ---- Internal ----

    private void setStage(Stage stage) {
        this.stage = stage;
    }

    private void sendMessage(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Notebot] " + msg), false);
        }
    }

    private void setupNoteblocks(Set<Note> requirements) {
        playingMode = PlayingMode.Noteblocks;
        setStage(Stage.SetUp);

        noteBlockPositions.clear();
        tuneHits.clear();
        clickedBlocks.clear();
        anyNoteblockTuned = false;

        if (requirements.isEmpty()) {
            sendMessage("§aNo note blocks needed. Starting playback.");
            play();
            return;
        }

        int radius = config.scanRadius;
        BlockPos playerPos = mc.player.getBlockPos();

        for (Note note : requirements) {
            boolean found = false;
            scanLoop:
            for (int dx = -radius; dx <= radius && !found; dx++) {
                for (int dy = -radius; dy <= radius && !found; dy++) {
                    for (int dz = -radius; dz <= radius && !found; dz++) {
                        BlockPos pos = playerPos.add(dx, dy, dz);

                        if (!noteBlockPositions.containsValue(pos) &&
                            isValidScanSpot(pos) && !clickedBlocks.contains(pos)) {

                            Note blockNote;
                            if (config.mode == NotebotUtils.NotebotMode.ExactInstruments) {
                                BlockState state = mc.world.getBlockState(pos);
                                blockNote = NotebotUtils.getNoteFromNoteBlock(state, pos,
                                    config.mode, config.instrumentDetectMode.getInstrumentDetectFunction());
                            } else {
                                int level = mc.world.getBlockState(pos).get(NoteBlock.NOTE);
                                blockNote = new Note(null, level);
                            }

                            if (blockNoteMatches(blockNote, note)) {
                                noteBlockPositions.put(note, pos);
                                clickedBlocks.add(pos);
                                found = true;

                                int hitsNeeded = calcNumberOfHits(blockNote.getNoteLevel(), note.getNoteLevel());
                                if (hitsNeeded > 0) {
                                    tuneHits.put(pos, hitsNeeded);
                                }
                                break scanLoop;
                            }
                        }
                    }
                }
            }

            if (!found) {
                sendMessage("§cCould not find a note block for: " + note);
                stop();
                return;
            }
        }

        if (tuneHits.isEmpty()) {
            sendMessage("§aAll note blocks are already tuned. Starting playback.");
            play();
        } else {
            setStage(Stage.Tune);
            sendMessage(String.format("§aTuning §6%d §anote blocks...", tuneHits.size()));
        }
    }

    private boolean blockNoteMatches(Note blockNote, Note required) {
        if (config.mode == NotebotUtils.NotebotMode.AnyInstrument) return true;
        if (required.getInstrument() == null) return blockNote.getInstrument() == null;
        return required.getInstrument() == blockNote.getInstrument();
    }

    private double getTuningProgress() {
        int tuned = anyNoteblockTuned ? clickedBlocks.size() - tuneHits.size() : 0;
        int total = noteBlockPositions.size();
        if (total == 0) return 0;
        return (double) tuned / total * 100;
    }

    private void play() {
        if (song == null) return;
        currentTick = 0;
        ticks = config.tickDelay;
        setStage(Stage.Playing);
        sendMessage(String.format("§aNow playing: §6%s §7(by %s)§a, mode: §6%s",
            song.getTitle(), song.getAuthor(), playingMode == PlayingMode.Preview ? "Preview" : "Note Blocks"));
    }

    // ---- Preview mode ----

    private void setupPreview() {
        setStage(Stage.Playing);
        sendMessage(String.format("§aPreviewing: §6%s §7(by %s) §aLength: §6%d §aticks",
            song.getTitle(), song.getAuthor(), song.getLastTick()));
    }

    private void onTickPreview() {
        if (song == null) {
            stop();
            return;
        }

        if (ticks < config.tickDelay) {
            ticks++;
            return;
        }

        ticks = 0;

        if (currentTick > song.getLastTick()) {
            sendMessage("§aPreview finished.");
            stop();
            return;
        }

        // Play preview sounds for notes in this tick
        Collection<Note> notes = song.getNotesMap().get(currentTick);
        if (!notes.isEmpty()) {
            if (config.swingArm) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }

            for (Note note : notes) {
                playPreviewSound(note);
            }
        }

        currentTick++;
    }

    private void playPreviewSound(Note note) {
        if (mc.world == null || mc.player == null) return;
        float pitch = (float) Math.pow(2.0, (note.getNoteLevel() - 12) / 12.0);
        NoteBlockInstrument inst = note.getInstrument();

        if (inst == null) {
            // AnyInstrument mode - default to harp
            mc.world.playSound(mc.player, mc.player.getBlockPos(),
                SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), SoundCategory.RECORDS, 3.0f, pitch);
        } else {
            mc.world.playSound(mc.player, mc.player.getBlockPos(),
                inst.getSound().value(), SoundCategory.RECORDS, 3.0f, pitch);
        }
    }

    // ---- Note blocks mode ----

    private void onTickNoteblocks() {
        if (stage == Stage.Tune) {
            onTickTune();
            return;
        }

        if (stage != Stage.Playing) return;

        if (ticks < config.tickDelay) {
            ticks++;
            return;
        }

        ticks = 0;

        if (currentTick > song.getLastTick()) {
            sendMessage("§aSong finished.");
            stop();
            return;
        }

        // Periodic block integrity check
        if (currentTick % 20 == 0) {
            for (var entry : noteBlockPositions.entrySet()) {
                BlockPos pos = entry.getValue();
                if (mc.world.getBlockState(pos).getBlock() != Blocks.NOTE_BLOCK) {
                    sendMessage("§cA note block was broken. Stopping.");
                    stop();
                    return;
                }
            }
        }

        onTickPlay();
        currentTick++;
    }

    private void onTickTune() {
        if (mc.world == null || mc.player == null) {
            stop();
            return;
        }

        if (ticks < config.tickDelay) {
            ticks++;
            return;
        }

        tuneBlocks();
        ticks = 0;
    }

    private void tuneBlocks() {
        if (mc.world == null || mc.player == null) {
            stop();
            return;
        }

        if (config.swingArm) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        int iterations = 0;
        var iterator = tuneHits.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();
            BlockPos pos = entry.getKey();
            int hitsNumber = entry.getValue();

            tuneNoteblock(pos);

            hitsNumber--;
            entry.setValue(hitsNumber);

            if (hitsNumber <= 0) {
                iterator.remove();
            }

            iterations++;
            if (iterations >= config.concurrentTuneBlocks) return;
        }

        if (tuneHits.isEmpty()) {
            setStage(Stage.WaitingToCheckNoteblocks);
            sendMessage("§aTuning complete. Checking note blocks...");

            loadingTimer = new Timer();
            loadingTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    checkNoteblocksAfterTuning();
                }
            }, 1000);
        }
    }

    private void checkNoteblocksAfterTuning() {
        if (mc.world == null || mc.player == null) return;

        boolean anyWrong = false;
        for (var entry : noteBlockPositions.entrySet()) {
            Note required = entry.getKey();
            BlockPos pos = entry.getValue();

            BlockState state = mc.world.getBlockState(pos);
            if (state.getBlock() != Blocks.NOTE_BLOCK) continue;

            Note currentNote;
            if (config.mode == NotebotUtils.NotebotMode.ExactInstruments) {
                currentNote = NotebotUtils.getNoteFromNoteBlock(state, pos,
                    config.mode, config.instrumentDetectMode.getInstrumentDetectFunction());
            } else {
                currentNote = new Note(null, state.get(NoteBlock.NOTE));
            }

            if (currentNote.getNoteLevel() != required.getNoteLevel()) {
                anyWrong = true;
                int hitsNeeded = calcNumberOfHits(currentNote.getNoteLevel(), required.getNoteLevel());
                if (hitsNeeded > 0) {
                    tuneHits.put(pos, hitsNeeded);
                } else {
                    // Already at correct level, just mark the difference
                    tuneHits.put(pos, 1);
                }
            }
        }

        if (anyWrong) {
            setStage(Stage.Tune);
            sendMessage(String.format("§e%d note blocks need re-tuning.", tuneHits.size()));
        } else {
            sendMessage("§aAll note blocks verified. Starting playback!");
            play();
        }
    }

    /**
     * Tune a note block by right-clicking it. Uses the standard Minecraft interaction
     * pipeline (interactBlock) which handles sequencing and server validation properly.
     */
    private void tuneNoteblock(BlockPos pos) {
        if (mc.interactionManager == null || mc.player == null) return;

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.DOWN, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        anyNoteblockTuned = true;
    }

    private void onTickPlay() {
        Collection<Note> notes = song.getNotesMap().get(currentTick);
        if (!notes.isEmpty()) {
            if (config.autoRotate) {
                Optional<Note> firstNote = notes.stream().findFirst();
                if (firstNote.isPresent()) {
                    BlockPos firstPos = noteBlockPositions.get(firstNote.get());
                    if (firstPos != null) {
                        lookAt(firstPos);
                    }
                }
            }

            if (config.swingArm) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }

            for (Note note : notes) {
                BlockPos pos = noteBlockPositions.get(note);
                if (pos == null) return;

                playNoteblock(pos);
            }
        }
    }

    /**
     * Play a note block by left-click-starting it. Uses the standard Minecraft
     * attackBlock method which properly sequences the packet.
     */
    private void playNoteblock(BlockPos pos) {
        if (mc.interactionManager == null || mc.player == null) return;
        try {
            mc.interactionManager.attackBlock(pos, Direction.DOWN);
        } catch (NullPointerException ignored) {
        }
    }

    private boolean isValidScanSpot(BlockPos pos) {
        if (mc.world.getBlockState(pos).getBlock() != Blocks.NOTE_BLOCK) return false;
        return mc.world.getBlockState(pos.up()).isAir();
    }

    private static int calcNumberOfHits(int from, int to) {
        if (from == to) return 0;
        if (from > to) {
            return (25 - from) + to;
        } else {
            return to - from;
        }
    }

    private void lookAt(BlockPos pos) {
        if (mc.player == null) return;
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d playerPos = mc.player.getEyePos();
        double dx = target.x - playerPos.x;
        double dy = target.y - playerPos.y;
        double dz = target.z - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    // ---- Enums ----

    public enum Stage {
        None,
        LoadingSong,
        SetUp,
        Tune,
        WaitingToCheckNoteblocks,
        Playing
    }

    public enum PlayingMode {
        None,
        Preview,
        Noteblocks
    }
}