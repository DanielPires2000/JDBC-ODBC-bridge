package pt.daniel.odbc

import java.text.MessageFormat
import java.util.ResourceBundle

/**
 * Centralized access to localized driver messages.
 *
 * Messages are resolved via [ResourceBundle], which automatically selects
 * the correct language based on the system's [java.util.Locale].
 *
 * Supported locales:
 * - English (default)
 * - Portuguese (pt)
 */
object Messages {
    private val bundle: ResourceBundle = ResourceBundle.getBundle(
        "pt.daniel.odbc.messages"
    )

    /** Returns the message for the given key. */
    fun get(key: String): String = bundle.getString(key)

    /** Returns the message for the given key, formatted with [args]. */
    fun get(key: String, vararg args: Any?): String =
        MessageFormat.format(bundle.getString(key), *args)
}
