package eu.pankraz01.glra.rollback;

/**
 * Shared parsing helpers for rollback requests (command + web UI).
 */
public final class RollbackInputParser {
    private RollbackInputParser() {
    }

    /**
     * Parse duration strings like "30m", "12h", "90s", "500ms".
     *
     * @return duration in milliseconds, or -1 when the input is invalid.
     */
    public static long parseDurationToMillis(String value) {
        if (value == null) return -1;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return -1;

        String lower = trimmed.toLowerCase();
        if (lower.endsWith("ms") && lower.length() > 2) {
            String numberPart = trimmed.substring(0, trimmed.length() - 2);
            try {
                long amount = Long.parseLong(numberPart);
                if (amount < 0) return -1;
                return amount;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        char unit = trimmed.charAt(trimmed.length() - 1);
        String numberPart = trimmed;
        long multiplier = 1_000L;
        if (Character.isLetter(unit)) {
            numberPart = trimmed.substring(0, trimmed.length() - 1);

            if (unit == 'M') {
                multiplier = 2_592_000_000L; // 30 days as a month approximation
            } else {
                switch (Character.toLowerCase(unit)) {
                    case 's':
                        multiplier = 1_000L;
                        break;
                    case 'm':
                        multiplier = 60_000L;
                        break;
                    case 'h':
                        multiplier = 3_600_000L;
                        break;
                    case 'd':
                        multiplier = 86_400_000L;
                        break;
                    case 'y':
                        multiplier = 31_536_000_000L;
                        break;
                    default:
                        return -1;
                }
            }
        }

        if (numberPart.isBlank()) return -1;

        try {
            long amount = Long.parseLong(numberPart);
            if (amount < 0) return -1;
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /**
     * Parse radius strings like "25", "b25", or "c4" (chunks).
     */
    public static Radius parseRadius(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;

        boolean chunks = false;
        String digits = trimmed;

        char prefix = trimmed.charAt(0);
        if (prefix == 'c' || prefix == 'C') {
            chunks = true;
            digits = trimmed.substring(1);
        } else if (prefix == 'b' || prefix == 'B') {
            digits = trimmed.substring(1);
        }

        if (digits.isEmpty()) return null;

        try {
            int radius = Integer.parseInt(digits);
            if (radius <= 0) return null;
            return new Radius(radius, chunks);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String radiusLabel(Radius radius) {
        return radius == null ? "" : radius.value() + (radius.inChunks() ? "c" : "b");
    }

    public static int radiusInBlocks(Radius radius) {
        if (radius == null) return 0;
        return radius.inChunks() ? radius.value() * 16 : radius.value();
    }

    public record Radius(int value, boolean inChunks) {
    }
}
