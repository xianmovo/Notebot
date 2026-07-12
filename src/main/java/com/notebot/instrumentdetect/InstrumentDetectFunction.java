/*
 * Copyright (c) Notebot.
 */

package com.notebot.instrumentdetect;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.util.math.BlockPos;

public interface InstrumentDetectFunction {
    NoteBlockInstrument detectInstrument(BlockState noteBlock, BlockPos blockPos);
}
