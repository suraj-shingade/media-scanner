package com.mediascanner.engine;

import com.sun.jna.Library;
import com.sun.jna.Native;

public interface CLibrary extends Library {
    CLibrary INSTANCE = Native.load("c", CLibrary.class);
    int setpriority(int which, int who, int prio);
}
