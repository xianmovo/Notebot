/*
 * Copyright (c) Notebot.
 */

package com.notebot.decoder;

import com.notebot.NotebotConfig;
import com.notebot.NotebotUtils;
import com.notebot.song.Note;
import com.notebot.song.Song;
import net.minecraft.block.enums.NoteBlockInstrument;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class SongDecoders {
    private static final Map<String, SongDecoder> decoders = new HashMap<>();

    private static Consumer<String> warningConsumer = System.err::println;

    static {
        registerDecoder("nbs", new NBSSongDecoder());
        registerDecoder("txt", new TextSongDecoder());
    }

    public static void setWarningConsumer(Consumer<String> consumer) {
        warningConsumer = consumer;
    }

    static void warn(String message) {
        warningConsumer.accept(message);
    }

    public static void registerDecoder(String extension, SongDecoder songDecoder) {
        decoders.put(extension, songDecoder);
    }

    public static SongDecoder getDecoder(File file) {
        return decoders.get(FilenameUtils.getExtension(file.getName()));
    }

    public static boolean hasDecoder(File file) {
        return decoders.containsKey(FilenameUtils.getExtension(file.getName()));
    }

    public static boolean hasDecoder(Path path) {
        return hasDecoder(path.toFile());
    }

    @NotNull
    public static Song parse(File file, NotebotConfig config) throws Exception {
        if (!hasDecoder(file)) throw new IllegalStateException("Decoder for this file does not exists!");
        SongDecoder decoder = getDecoder(file);
        Song song = decoder.parse(file);

        fixSong(song, config);

        song.finishLoading();

        return song;
    }

    private static void fixSong(Song song, NotebotConfig config) {
        var iterator = song.getNotesMap().entries().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int tick = entry.getKey();
            Note note = entry.getValue();

            int n = note.getNoteLevel();
            if (n < 0 || n > 24) {
                if (config.roundOutOfRange) {
                    note.setNoteLevel(n < 0 ? 0 : 24);
                } else {
                    warn(String.format("Note at tick %d out of range.", tick));
                    iterator.remove();
                    continue;
                }
            }

            if (config.mode == NotebotUtils.NotebotMode.ExactInstruments) {
                NoteBlockInstrument mapped = config.getMappedInstrument(note.getInstrument());
                if (mapped != null) {
                    note.setInstrument(mapped);
                }
            } else {
                note.setInstrument(null);
            }
        }
    }
}