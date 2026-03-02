package com.audioviz.sequence;

import java.util.Map;

/**
 * A single step in a sequence — assigns patterns to zones for a duration.
 */
public record SequenceStep(
    Map<String, String> zonePatterns,
    int durationTicks,
    String transitionId,
    int transitionDuration
) {}
