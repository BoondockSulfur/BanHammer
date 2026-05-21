package dev.banhammer.plugin.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.util.Locale;

/**
 * Resolves configured sound names to {@link Sound} instances.
 *
 * <p>Accepts both modern namespaced keys (e.g. {@code "block.note_block.pling"} or
 * {@code "minecraft:block.note_block.pling"}) and legacy enum-style constants
 * (e.g. {@code "BLOCK_NOTE_BLOCK_PLING"}), so existing configs keep working while
 * users can migrate to the registry-based naming. The single remaining call to the
 * deprecated {@code Sound.valueOf(String)} is isolated here.
 *
 * @since 3.1.2
 */
public final class Sounds {

    private Sounds() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Resolves a sound name to a {@link Sound}.
     *
     * @param name the configured sound name (namespaced key or legacy constant)
     * @return the resolved sound, or {@code null} if it cannot be resolved
     */
    public static Sound resolve(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Modern namespaced key (contains a namespace separator or path dots)
        if (trimmed.indexOf(':') >= 0 || trimmed.indexOf('.') >= 0) {
            Sound fromKey = fromKey(trimmed.toLowerCase(Locale.ROOT));
            if (fromKey != null) {
                return fromKey;
            }
        }

        // Legacy enum-style constant (e.g. BLOCK_NOTE_BLOCK_PLING)
        return legacyValueOf(trimmed.toUpperCase(Locale.ROOT));
    }

    /**
     * @return {@code true} if the given name resolves to a valid sound
     */
    public static boolean isValid(String name) {
        return resolve(name) != null;
    }

    private static Sound fromKey(String key) {
        try {
            NamespacedKey namespacedKey = key.indexOf(':') >= 0
                    ? NamespacedKey.fromString(key)
                    : NamespacedKey.minecraft(key);
            if (namespacedKey == null) {
                return null;
            }
            return Registry.SOUNDS.get(namespacedKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Sound legacyValueOf(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
