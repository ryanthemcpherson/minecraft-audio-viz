package com.audioviz.render;

import com.audioviz.protocol.MessageQueue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict JSON-to-primitive-snapshot normalizer with no Bukkit/world access. */
public final class JsonRenderFrameDecoder {

    private static final MessageQueue.MessageGuard ALLOW_ALL = operation -> {
        operation.run();
        return true;
    };
    private static final Set<String> ROOT_FIELDS = Set.of(
        "type", "v", "zone", "entities", "particles", "bands", "amplitude",
        "is_beat", "is_kick", "beat_intensity", "bpm", "tempo_confidence",
        "beat_phase", "frame", "source_time_ns", "generated_time_ns");
    private static final Set<String> ENTITY_FIELDS = Set.of(
        "id", "x", "y", "z", "scale", "rotation", "band", "visible", "text",
        "material", "glow", "brightness", "interpolation");
    private static final Set<String> PARTICLE_FIELDS = Set.of(
        "particle", "x", "y", "z", "count");
    private static final Pattern ZONE_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern PARTICLE_NAME = Pattern.compile("[a-zA-Z0-9_]{1,64}");
    private static final int JSON_PARTICLE_COUNT_MAX = 5_000;

    private final RenderFrameHub hub;
    private final RenderProtocolLimits limits;

    public JsonRenderFrameDecoder(RenderFrameHub hub) {
        this(hub, hub == null ? null : hub.limits());
    }

    JsonRenderFrameDecoder(RenderFrameHub hub, RenderProtocolLimits limits) {
        if (hub == null || limits == null) {
            throw new NullPointerException("hub and limits are required");
        }
        this.hub = hub;
        this.limits = limits;
    }

    /** Testable allow-all entry point; production ingress uses {@link RenderFrameHub}. */
    public RenderDecodeResult decode(JsonObject message, long ingressOrdinal, long receivedNanos) {
        return decodeAndPublish(message, ALLOW_ALL, ingressOrdinal, receivedNanos);
    }

    RenderDecodeResult decodeAndPublish(
            JsonObject message,
            MessageQueue.MessageGuard guard,
            long ingressOrdinal,
            long receivedNanos
    ) {
        if (message == null) {
            return RenderDecodeResult.rejected("message is null");
        }
        if (ingressOrdinal < 0 || receivedNanos < 0) {
            return RenderDecodeResult.rejected("ingress and receive times must not be negative");
        }

        RenderFrameHub.ZoneEntry zoneEntry;
        JsonArray entities;
        try {
            rejectUnknownFields(message, ROOT_FIELDS, "batch_update");
            requireExactString(message, "type", "batch_update");
            if (message.has("v")) {
                requireString(message, "v");
            }
            String zoneName = requireString(message, "zone");
            if (!ZONE_NAME.matcher(zoneName).matches()) {
                throw invalid("zone is invalid");
            }
            zoneEntry = hub.findZone(zoneName);
            if (zoneEntry == null) {
                throw invalid("zone mailbox does not exist");
            }
            entities = requireArray(message, "entities");
            if (entities.size() > limits.maxEntitiesPerZone()) {
                throw invalid("entity count exceeds configured limit");
            }
        } catch (DecodeFailure failure) {
            return RenderDecodeResult.rejected(failure.getMessage());
        }

        ZoneRenderSnapshot snapshot = zoneEntry.tryClaim(ingressOrdinal);
        if (snapshot == null) {
            return RenderDecodeResult.rejected("no render snapshot slot is available");
        }

        try {
            if (entities.size() > snapshot.entityCapacity()) {
                throw invalid("entity count exceeds zone pool capacity");
            }
            populateSnapshot(message, entities, snapshot, receivedNanos);
            validateParticles(message);
            long eventSequence = message.has("frame")
                ? requireNonNegativeLong(message, "frame")
                : ingressOrdinal;
            return zoneEntry.commit(snapshot, message, eventSequence, guard);
        } catch (DecodeFailure | ArithmeticException | NumberFormatException failure) {
            zoneEntry.releaseAfterFailedWrite(snapshot);
            return RenderDecodeResult.rejected(failure.getMessage());
        } catch (RuntimeException failure) {
            zoneEntry.releaseAfterFailedWrite(snapshot);
            return RenderDecodeResult.rejected("malformed render frame: " + failure.getMessage());
        }
    }

    private void populateSnapshot(
            JsonObject message,
            JsonArray entities,
            ZoneRenderSnapshot snapshot,
            long receivedNanos
    ) {
        snapshot.receivedNanos(receivedNanos);
        snapshot.frameSequence(optionalNonNegativeLong(message, "frame", snapshot.ingressOrdinal()));
        snapshot.sourceTimeNanos(optionalNonNegativeLong(message, "source_time_ns", 0));
        snapshot.generatedTimeNanos(optionalNonNegativeLong(message, "generated_time_ns", 0));

        double[] bands = snapshot.bands();
        if (message.has("bands")) {
            JsonArray inputBands = requireArray(message, "bands");
            if (inputBands.size() != RenderProtocolLimits.BAND_COUNT) {
                throw invalid("bands must contain exactly five values");
            }
            for (int index = 0; index < bands.length; index++) {
                bands[index] = requireNumber(
                    inputBands.get(index),
                    "bands[" + index + "]",
                    RenderProtocolLimits.UNIT_MIN,
                    RenderProtocolLimits.UNIT_MAX);
            }
        }

        snapshot.amplitude(optionalNumber(
            message, "amplitude", 0, RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX));
        snapshot.beat(optionalBoolean(message, "is_beat", false));
        snapshot.kick(optionalBoolean(message, "is_kick", false));
        snapshot.beatIntensity(optionalNumber(
            message, "beat_intensity", 0,
            RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX));
        snapshot.bpm(optionalNumber(
            message, "bpm", 0, 0, RenderProtocolLimits.BPM_MAX));
        snapshot.tempoConfidence(optionalNumber(
            message, "tempo_confidence", 0,
            RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX));
        snapshot.beatPhase(optionalNumber(
            message, "beat_phase", 0,
            RenderProtocolLimits.UNIT_MIN, RenderProtocolLimits.UNIT_MAX));

        boolean dense = true;
        for (int index = 0; index < entities.size(); index++) {
            JsonElement element = entities.get(index);
            if (!element.isJsonObject()) {
                throw invalid("entities[" + index + "] must be an object");
            }
            JsonObject entity = element.getAsJsonObject();
            rejectUnknownFields(entity, ENTITY_FIELDS, "entities[" + index + "]");
            String entityId = requireNonEmptyString(entity, "id");
            if (entity.has("text")) {
                requireString(entity, "text");
                throw invalid("text entity updates are not supported by render snapshots");
            }
            if (entity.has("band")) {
                requireInteger(entity, "band", 0, RenderProtocolLimits.BAND_COUNT - 1);
            }

            snapshot.entityIds()[index] = entityId;
            snapshot.x()[index] = (float) optionalNumber(entity, "x", 0.5, 0, 1);
            snapshot.y()[index] = (float) optionalNumber(entity, "y", 0.5, 0, 1);
            snapshot.z()[index] = (float) optionalNumber(entity, "z", 0.5, 0, 1);
            snapshot.scale()[index] = (float) optionalNumber(
                entity, "scale", 0.5, 0, RenderProtocolLimits.ENTITY_SCALE_MAX);
            snapshot.rotation()[index] = normalizeRotation(
                optionalUnboundedNumber(entity, "rotation", 0));
            snapshot.brightness()[index] = (byte) optionalInteger(
                entity,
                "brightness",
                RenderProtocolLimits.BRIGHTNESS_MAX,
                RenderProtocolLimits.BRIGHTNESS_MIN,
                RenderProtocolLimits.BRIGHTNESS_MAX);
            snapshot.interpolationTicks()[index] = (byte) optionalInteger(
                entity,
                "interpolation",
                1,
                RenderProtocolLimits.INTERPOLATION_TICKS_MIN,
                RenderProtocolLimits.INTERPOLATION_TICKS_MAX);

            boolean visible = optionalBoolean(entity, "visible", true);
            boolean glow = optionalBoolean(entity, "glow", false);
            snapshot.entityFlags()[index] = (byte) (
                (visible ? ZoneRenderSnapshot.ENTITY_VISIBLE : 0)
                    | (glow ? ZoneRenderSnapshot.ENTITY_GLOW : 0));
            snapshot.materialNames()[index] = entity.has("material")
                ? requireNonEmptyString(entity, "material")
                : null;
            snapshot.materialIds()[index] = snapshot.defaultMaterialId();
            dense &= entityId.equals("block_" + index);
        }
        snapshot.entityCount(entities.size());
        snapshot.densePool(dense);
    }

    private void validateParticles(JsonObject message) {
        if (!message.has("particles")) {
            return;
        }
        JsonArray particles = requireArray(message, "particles");
        if (particles.size() > limits.maxParticlesPerTick()) {
            throw invalid("particle event count exceeds configured limit");
        }

        int totalCount = 0;
        for (int index = 0; index < particles.size(); index++) {
            JsonElement element = particles.get(index);
            if (!element.isJsonObject()) {
                throw invalid("particles[" + index + "] must be an object");
            }
            JsonObject particle = element.getAsJsonObject();
            rejectUnknownFields(particle, PARTICLE_FIELDS, "particles[" + index + "]");
            String particleName = requireNonEmptyString(particle, "particle")
                .toUpperCase(Locale.ROOT);
            if (!PARTICLE_NAME.matcher(particleName).matches()) {
                throw invalid("particle name is invalid");
            }
            optionalNumber(particle, "x", 0.5, 0, 1);
            optionalNumber(particle, "y", 0.5, 0, 1);
            optionalNumber(particle, "z", 0.5, 0, 1);
            int count = optionalInteger(
                particle, "count", 10, 1, JSON_PARTICLE_COUNT_MAX);
            totalCount = Math.addExact(totalCount, count);
            if (totalCount > limits.maxParticlesPerTick()) {
                throw invalid("particle work exceeds configured tick budget");
            }
        }
    }

    static void latchValidatedEvents(
            RenderEventLatch latch,
            JsonObject message,
            long eventSequence,
            MessageQueue.MessageGuard guard
    ) {
        boolean beat = optionalBoolean(message, "is_beat", false);
        boolean kick = optionalBoolean(message, "is_kick", false);
        if (beat || kick) {
            latch.latchBeat(
                eventSequence,
                beat,
                kick,
                optionalNumber(message, "beat_intensity", 0, 0, 1),
                guard);
        }

        if (!message.has("particles")) {
            return;
        }
        JsonArray particles = message.getAsJsonArray("particles");
        for (int index = 0; index < particles.size(); index++) {
            JsonObject particle = particles.get(index).getAsJsonObject();
            String particleName = particle.get("particle").getAsString()
                .toUpperCase(Locale.ROOT);
            latch.offerParticle(new RenderParticleEvent(
                particleEventId(eventSequence, index),
                jsonParticleTypeId(particleName),
                (float) optionalNumber(particle, "x", 0.5, 0, 1),
                (float) optionalNumber(particle, "y", 0.5, 0, 1),
                (float) optionalNumber(particle, "z", 0.5, 0, 1),
                optionalInteger(particle, "count", 10, 1, JSON_PARTICLE_COUNT_MAX)), guard);
        }
    }

    private static long particleEventId(long eventSequence, int index) {
        return Long.rotateLeft(eventSequence, 17) ^ Integer.toUnsignedLong(index);
    }

    private static int jsonParticleTypeId(String name) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < name.length(); index++) {
            hash ^= name.charAt(index);
            hash *= 0x01000193;
        }
        hash &= Integer.MAX_VALUE;
        return hash == 0 ? 1 : hash;
    }

    private static float normalizeRotation(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped < 0) {
            wrapped += 360.0;
        }
        return wrapped == 0.0 ? 0.0f : (float) wrapped;
    }

    private static void rejectUnknownFields(
            JsonObject object,
            Set<String> allowed,
            String location
    ) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid(location + " contains unknown field: " + field);
            }
        }
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw invalid(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw invalid(field + " must be a string");
        }
        return value.getAsString();
    }

    private static String requireNonEmptyString(JsonObject object, String field) {
        String value = requireString(object, field);
        if (value.isEmpty()) {
            throw invalid(field + " must not be empty");
        }
        return value;
    }

    private static void requireExactString(JsonObject object, String field, String expected) {
        if (!expected.equals(requireString(object, field))) {
            throw invalid(field + " must equal " + expected);
        }
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean defaultValue) {
        if (!object.has(field)) {
            return defaultValue;
        }
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static double optionalUnboundedNumber(
            JsonObject object,
            String field,
            double defaultValue
    ) {
        return object.has(field)
            ? requireNumber(object.get(field), field, -Double.MAX_VALUE, Double.MAX_VALUE)
            : defaultValue;
    }

    private static double optionalNumber(
            JsonObject object,
            String field,
            double defaultValue,
            double minimum,
            double maximum
    ) {
        return object.has(field)
            ? requireNumber(object.get(field), field, minimum, maximum)
            : defaultValue;
    }

    private static double requireNumber(
            JsonElement element,
            String field,
            double minimum,
            double maximum
    ) {
        if (element == null || !element.isJsonPrimitive()) {
            throw invalid(field + " must be a number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalid(field + " must be a number");
        }
        double value = primitive.getAsDouble();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw invalid(field + " is outside its numeric domain");
        }
        return value;
    }

    private static int optionalInteger(
            JsonObject object,
            String field,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        return object.has(field)
            ? requireInteger(object, field, minimum, maximum)
            : defaultValue;
    }

    private static int requireInteger(
            JsonObject object,
            String field,
            int minimum,
            int maximum
    ) {
        BigDecimal value = requireDecimal(object.get(field), field);
        try {
            int integer = value.intValueExact();
            if (integer < minimum || integer > maximum) {
                throw invalid(field + " is outside its integer domain");
            }
            return integer;
        } catch (ArithmeticException failure) {
            throw invalid(field + " must be an exact integer");
        }
    }

    private static long optionalNonNegativeLong(
            JsonObject object,
            String field,
            long defaultValue
    ) {
        return object.has(field) ? requireNonNegativeLong(object, field) : defaultValue;
    }

    private static long requireNonNegativeLong(JsonObject object, String field) {
        BigDecimal value = requireDecimal(object.get(field), field);
        try {
            long integer = value.longValueExact();
            if (integer < 0) {
                throw invalid(field + " must not be negative");
            }
            return integer;
        } catch (ArithmeticException failure) {
            throw invalid(field + " must be an exact 64-bit integer");
        }
    }

    private static BigDecimal requireDecimal(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(field + " must be a number");
        }
        try {
            return element.getAsBigDecimal();
        } catch (NumberFormatException failure) {
            throw invalid(field + " must be finite");
        }
    }

    private static DecodeFailure invalid(String message) {
        return new DecodeFailure(message);
    }

    private static final class DecodeFailure extends RuntimeException {
        private DecodeFailure(String message) {
            super(message);
        }
    }
}
