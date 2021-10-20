package no.moldesoft.utils.debug.mail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:erling.molde@moldesoft.no">Erling Molde</a>
 */
class VersionUtil {
    private static final Pattern versionPattern = Pattern.compile("[\\d.]+");

    public static String get(final String source) {
        if (source == null) {
			return null;
		}
        final String found = getAll(source);
        return found == null ? source : found.startsWith("1.") ? found.substring(2) : found;
    }

    public static String getAll(final String source) {
        if (source == null) {
			return null;
		}
        final Matcher matcher = versionPattern.matcher(source);
        return matcher.find() ? matcher.group() : null;
    }
}