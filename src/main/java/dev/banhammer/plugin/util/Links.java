package dev.banhammer.plugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.Map;

/**
 * Renders download links as clickable labels.
 *
 * <p>Chat is a poor place for a raw URL: it is long, wraps across lines and is awkward to
 * click. Links are therefore shown as a short bracketed label - {@code [Modrinth]} - that
 * opens the URL on click and reveals it on hover, which also lets several providers share
 * a single line.
 *
 * @since 4.1.0
 */
public final class Links {

    private Links() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Only http(s) links are ever offered for clicking. */
    private static boolean isSafe(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /**
     * Renders a single clickable label.
     *
     * @param label the visible text, shown in square brackets
     * @param url   the target URL
     * @return the component, or {@code null} if the URL is unusable
     */
    public static Component label(String label, String url) {
        if (!isSafe(url)) {
            return null;
        }
        String target = url.trim();

        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text(label, NamedTextColor.AQUA, TextDecoration.UNDERLINED))
                .append(Component.text("]", NamedTextColor.DARK_GRAY))
                .clickEvent(ClickEvent.openUrl(target))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Open ", NamedTextColor.GRAY)
                                .append(Component.text(target, NamedTextColor.WHITE))))
                .build();
    }

    /**
     * Renders every configured provider as a space-separated row of clickable labels.
     *
     * <p>Providers with a blank URL are skipped, so removing a link from the configuration
     * simply hides it.
     *
     * @param links provider name to URL, in the order they should appear
     * @return the row, or {@code null} if no usable link was configured
     */
    public static Component row(Map<String, String> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }

        Component result = null;
        for (Map.Entry<String, String> entry : links.entrySet()) {
            Component label = label(entry.getKey(), entry.getValue());
            if (label == null) {
                continue;
            }
            result = (result == null) ? label : result.append(Component.space()).append(label);
        }
        return result;
    }
}
