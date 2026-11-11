package com.nemesis.enginesound.synth;

/** Something that can fill a stereo PCM buffer from rpm and pedal. */
public interface EngineVoice {
    String id();

    String label();

    /**
     * Interleaved stereo PCM. {@code rpm} is engine speed, {@code pedal} is
     * 0..1, {@code volume} is 0..1 track gain.
     */
    void render(short[] dst, int frames, float rpm, float pedal, float volume, boolean limiter);
}
