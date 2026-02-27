package com.audioviz.effects;

/**
 * Types of audio beats/events that can trigger effects.
 */
public enum BeatType {
    /** Kick drum hit - low frequency transient */
    KICK,

    /** Snare drum hit - mid frequency transient */
    SNARE,

    /** Hi-hat hit - high frequency transient */
    HIHAT,

    /** Bass drop - sustained low frequency surge */
    BASS_DROP,

    /** Peak - overall audio peak/spike */
    PEAK,

    /** Any beat - general beat detection */
    ANY,

    /** General beat - default beat type from FFT analyzer */
    BEAT
}
