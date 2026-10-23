package com.pokedaisy.app.threeds;

final class ThreeDsNative {
    static { System.loadLibrary("daisy3ds"); }
    static native String start(String path,String system,String saves);
    static native void frame(int width,int height);
    static native void buttons(int bits);
    static native void analog(int x,int y);
    static native void pointer(int x,int y,boolean pressed);
    static native void pause(boolean paused);
    static native void stop();
    static native String error();
    static native byte[] partySlots();
}
