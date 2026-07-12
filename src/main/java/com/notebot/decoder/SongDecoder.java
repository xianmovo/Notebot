/*
 * Copyright (c) Notebot.
 */

package com.notebot.decoder;

import com.notebot.song.Song;

import java.io.File;

public abstract class SongDecoder {

    /**
     * Parse file to a {@link Song} object
     *
     * @param file Song file
     * @return A {@link Song} object
     */
    public abstract Song parse(File file) throws Exception;
}
