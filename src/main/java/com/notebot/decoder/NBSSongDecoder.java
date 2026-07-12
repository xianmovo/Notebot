/*
 * Copyright (c) Notebot.
 * Based on NBS format: https://opennbs.org/nbs
 */

package com.notebot.decoder;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.notebot.song.Note;
import com.notebot.song.Song;
import net.minecraft.block.enums.NoteBlockInstrument;
import org.jetbrains.annotations.NotNull;

import java.io.*;

public class NBSSongDecoder extends SongDecoder {

    public static final int NOTE_OFFSET = 33;

    @Override
    @NotNull
    public Song parse(File songFile) throws Exception {
        return parse(new FileInputStream(songFile));
    }

    @NotNull
    private Song parse(InputStream inputStream) throws Exception {
        Multimap<Integer, Note> notesMap = MultimapBuilder.linkedHashKeys().arrayListValues().build();

        DataInputStream dataInputStream = new DataInputStream(inputStream);
        short length = readShort(dataInputStream);
        int nbsversion = 0;
        if (length == 0) {
            nbsversion = dataInputStream.readByte();
            dataInputStream.readByte();
            if (nbsversion >= 3) {
                length = readShort(dataInputStream);
            }
        }
        readShort(dataInputStream); // Song Height
        String title = readString(dataInputStream);
        String author = readString(dataInputStream);
        readString(dataInputStream); // original author
        readString(dataInputStream); // description
        float speed = readShort(dataInputStream) / 100f;
        dataInputStream.readBoolean(); // auto-save
        dataInputStream.readByte(); // auto-save duration
        dataInputStream.readByte(); // x/4ths, time signature
        readInt(dataInputStream); // minutes spent on project
        readInt(dataInputStream); // left clicks
        readInt(dataInputStream); // right clicks
        readInt(dataInputStream); // blocks added
        readInt(dataInputStream); // blocks removed
        readString(dataInputStream); // .mid/.schematic file name
        if (nbsversion >= 4) {
            dataInputStream.readByte(); // loop on/off
            dataInputStream.readByte(); // max loop count
            readShort(dataInputStream); // loop start tick
        }

        double tick = -1;
        while (true) {
            short jumpTicks = readShort(dataInputStream);
            if (jumpTicks == 0) {
                break;
            }
            tick += jumpTicks * (20f / speed);
            while (true) {
                short jumpLayers = readShort(dataInputStream);
                if (jumpLayers == 0) {
                    break;
                }
                byte instrument = dataInputStream.readByte();

                byte key = dataInputStream.readByte();
                if (nbsversion >= 4) {
                    dataInputStream.readUnsignedByte(); // note block velocity
                    dataInputStream.readUnsignedByte(); // note panning
                    readShort(dataInputStream); // note block pitch
                }

                NoteBlockInstrument inst = fromNBSInstrument(instrument);

                if (inst == null) continue;

                Note note = new Note(inst, key - NOTE_OFFSET);
                setNote((int) Math.round(tick), note, notesMap);
            }
        }

        return new Song(notesMap, title, author);
    }

    private static void setNote(int ticks, Note note, Multimap<Integer, Note> notesMap) {
        notesMap.put(ticks, note);
    }

    private static short readShort(DataInputStream dataInputStream) throws IOException {
        int byte1 = dataInputStream.readUnsignedByte();
        int byte2 = dataInputStream.readUnsignedByte();
        return (short) (byte1 + (byte2 << 8));
    }

    private static int readInt(DataInputStream dataInputStream) throws IOException {
        int byte1 = dataInputStream.readUnsignedByte();
        int byte2 = dataInputStream.readUnsignedByte();
        int byte3 = dataInputStream.readUnsignedByte();
        int byte4 = dataInputStream.readUnsignedByte();
        return (byte1 + (byte2 << 8) + (byte3 << 16) + (byte4 << 24));
    }

    private static String readString(DataInputStream dataInputStream) throws IOException {
        int length = readInt(dataInputStream);
        if (length < 0) {
            throw new EOFException("Length can't be negative! Length: " + length);
        }
        if (length > dataInputStream.available()) {
            throw new EOFException("Can't read string that is larger than a buffer! Length: " + length + " Readable Bytes Length: " + dataInputStream.available());
        }

        StringBuilder builder = new StringBuilder(length);
        for (; length > 0; --length) {
            char c = (char) dataInputStream.readByte();
            if (c == (char) 0x0D) {
                c = ' ';
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private static NoteBlockInstrument fromNBSInstrument(int instrument) {
        return switch (instrument) {
            case 0 -> NoteBlockInstrument.HARP;
            case 1 -> NoteBlockInstrument.BASS;
            case 2 -> NoteBlockInstrument.BASEDRUM;
            case 3 -> NoteBlockInstrument.SNARE;
            case 4 -> NoteBlockInstrument.HAT;
            case 5 -> NoteBlockInstrument.GUITAR;
            case 6 -> NoteBlockInstrument.FLUTE;
            case 7 -> NoteBlockInstrument.BELL;
            case 8 -> NoteBlockInstrument.CHIME;
            case 9 -> NoteBlockInstrument.XYLOPHONE;
            case 10 -> NoteBlockInstrument.IRON_XYLOPHONE;
            case 11 -> NoteBlockInstrument.COW_BELL;
            case 12 -> NoteBlockInstrument.DIDGERIDOO;
            case 13 -> NoteBlockInstrument.BIT;
            case 14 -> NoteBlockInstrument.BANJO;
            case 15 -> NoteBlockInstrument.PLING;
            default -> null;
        };
    }
}
