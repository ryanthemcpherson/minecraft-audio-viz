package com.audioviz.decorators;

import java.util.*;

/**
 * Flexible per-decorator configuration using a map-based approach.
 * Each decorator type defines its own keys; this avoids needing a POJO subclass per decorator.
 * Serialized to/from YAML in stages.yml alongside stage data.
 */
public class DecoratorConfig {

    private boolean enabled;
    private final Map<String, Object> settings;

    public DecoratorConfig() {
        this.enabled = true;
        this.settings = new LinkedHashMap<>();
    }

    public DecoratorConfig(boolean enabled, Map<String, Object> settings) {
        this.enabled = enabled;
        this.settings = settings != null ? new LinkedHashMap<>(settings) : new LinkedHashMap<>();
    }

    /**
     * Copy constructor.
     */
    public DecoratorConfig(DecoratorConfig other) {
        this.enabled = other.enabled;
        this.settings = new LinkedHashMap<>(other.settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    // ========== Typed Getters ==========

    public String getString(String key, String defaultVal) {
        Object val = settings.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    public int getInt(String key, int defaultVal) {
        Object val = settings.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultVal;
    }

    public double getDouble(String key, double defaultVal) {
        Object val = settings.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultVal;
    }

    public float getFloat(String key, float defaultVal) {
        return (float) getDouble(key, defaultVal);
    }

    public boolean getBoolean(String key, boolean defaultVal) {
        Object val = settings.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key, List<String> defaultVal) {
        Object val = settings.get(key);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return defaultVal;
    }

    // ========== Setters ==========

    public void set(String key, Object value) {
        settings.put(key, value);
    }

    public void remove(String key) {
        settings.remove(key);
    }

    @Override
    public String toString() {
        return "DecoratorConfig[enabled=" + enabled + ", settings=" + settings + "]";
    }
}
