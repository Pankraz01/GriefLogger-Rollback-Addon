package eu.pankraz01.glra;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Utility to provide consistent GLRA message styling and fallbacks.
 */
public final class Messages {
    private static final String PREFIX_KEY = "message.griefloggerrollbackaddon.prefix";
    private static final String SEPARATOR_KEY = "message.griefloggerrollbackaddon.separator";

    private Messages() {
    }

    /**
     * Returns the shared GLRA prefix with consistent coloring and English fallback.
     */
    public static MutableComponent prefix() {
        return Component.translatableWithFallback(PREFIX_KEY, "[GLRA] ")
                .copy()
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    /**
     * Returns a standard separator between label/value segments.
     */
    public static MutableComponent separator() {
        return Component.translatableWithFallback(SEPARATOR_KEY, " | ")
                .copy()
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * Builds a headline with the GLRA prefix and a colored label.
     */
    public static MutableComponent header(String key, String fallback, ChatFormatting color) {
        MutableComponent label = Component.translatableWithFallback(key, fallback)
                .withStyle(color, ChatFormatting.BOLD);
        return prefix().append(label);
    }

    /**
     * Prepends the GLRA prefix to an arbitrary message component.
     */
    public static MutableComponent prefixed(Component message) {
        return prefix().append(message.copy());
    }

    /**
     * Formats a label/value pair with consistent coloring and fallback text.
     */
    public static MutableComponent labelValue(String key, String fallbackLabel, Component value, ChatFormatting valueColor) {
        MutableComponent label = Component.translatableWithFallback(key, fallbackLabel)
                .copy()
                .withStyle(ChatFormatting.GRAY);
        return label.append(value.copy().withStyle(valueColor));
    }
}
