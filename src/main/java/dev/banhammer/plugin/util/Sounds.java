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
 * users can migrate to the registry-based naming. Legacy names are looked up as
 * constants, which works on every supported version.
 *
 * @since 4.0.0
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

    /**
     * Looks the constant up as a public static field. {@code Sound} is an enum up to 1.21.2
     * and an interface with constants afterwards; a direct {@code Sound.valueOf} call compiled
     * against one fails to link against the other, while both expose the same fields.
     */
    private static Sound legacyValueOf(String name) {
        try {
            return Sound.class.getField(name).get(null) instanceof Sound sound ? sound : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
