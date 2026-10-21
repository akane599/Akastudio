package com.akane.akastudio.core.ingestion.zip

import com.akane.akastudio.core.ingestion.api.ZipLimits
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.core.ingestion.model.SkipReason
import com.akane.akastudio.core.ingestion.model.stableCandidateId

/** What to do with one archive entry once its name passed [ArchiveEntryRules.nameViolation]. */
internal sealed interface EntryClassification {
    data class Skip(val reason: SkipReason) : EntryClassification
    data class Image(val format: ImageFormat, val fileName: String) : EntryClassification
}

/**
 * Stateless rules for untrusted archive entry names: validation, classification and output naming.
 *
 * Output files are always flattened into one directory under a sanitized name, so a name that slips
 * past [nameViolation] still cannot address a path outside the destination.
 */
internal object ArchiveEntryRules {
    private const val MAX_SANITIZED_LENGTH = 96
    private const val ID_PREFIX_LENGTH = 16
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")
    private val unsafeFileNameChar = Regex("[^A-Za-z0-9._-]")
    private val junkFileNames = setOf(".ds_store", "thumbs.db", "desktop.ini")

    fun normalize(rawName: String): String = rawName.replace('\\', '/')

    /** Path segments that carry meaning: empty and `.` segments are dropped. */
    fun segments(normalizedName: String): List<String> =
        normalizedName.split('/').filter { it.isNotEmpty() && it != "." }

    /**
     * Returns the error that rejects the whole archive for [rawName], or null when the name is acceptable.
     * A control character is not an escape: [classify] skips that entry instead.
     */
    fun nameViolation(rawName: String, limits: ZipLimits): IngestionError? {
        val name = normalize(rawName)
        val escapes = name.startsWith("/") ||
            windowsDrivePrefix.containsMatchIn(name) ||
            name.split('/').any { it == ".." } ||
            name.length > limits.maxNameLength
        return when {
            escapes -> IngestionError.ZipSlip(rawName)
            segments(name).size > limits.maxPathDepth ->
                IngestionError.LimitExceeded("entry path deeper than ${limits.maxPathDepth} segments")
            else -> null
        }
    }

    fun classify(rawName: String, isDirectory: Boolean): EntryClassification {
        val name = normalize(rawName)
        if (isDirectory || name.endsWith("/")) return EntryClassification.Skip(SkipReason.DIRECTORY)
        // For example the macOS Finder `Folder/Icon\r` entry: never a photo, and not an escape either.
        if (name.any { Character.isISOControl(it) }) return EntryClassification.Skip(SkipReason.UNSUPPORTED_TYPE)
        val parts = segments(name)
        val fileName = parts.lastOrNull().orEmpty()
        val junk = parts.any { it.equals("__MACOSX", ignoreCase = true) } ||
            fileName.lowercase() in junkFileNames ||
            fileName.startsWith("._")
        val format = ImageFormat.fromExtension(fileName.substringAfterLast('.', missingDelimiterValue = ""))
        return when {
            junk -> EntryClassification.Skip(SkipReason.JUNK_FILE)
            parts.any { it.startsWith(".") } -> EntryClassification.Skip(SkipReason.HIDDEN_FILE)
            format == null -> EntryClassification.Skip(SkipReason.UNSUPPORTED_TYPE)
            else -> EntryClassification.Image(format, fileName)
        }
    }

    /** Keeps `[A-Za-z0-9._-]`, replaces everything else with `_`, truncates to 96 chars keeping the extension. */
    fun sanitizeFileName(fileName: String): String {
        val safe = unsafeFileNameChar.replace(fileName, "_")
        if (safe.length <= MAX_SANITIZED_LENGTH) return safe
        val extension = safe.substringAfterLast('.', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else ".$it" }
            .take(MAX_SANITIZED_LENGTH)
        return safe.dropLast(extension.length).take(MAX_SANITIZED_LENGTH - extension.length) + extension
    }

    /** [fileName] without Unicode format characters (category Cf, such as bidi overrides), so it cannot spoof the UI. */
    fun displayName(fileName: String): String = buildString {
        fileName.codePoints()
            .filter { Character.getType(it) != Character.FORMAT.toInt() }
            .forEach { appendCodePoint(it) }
    }

    /** `<first 16 hex of stableCandidateId(archiveIdentity + NUL + entryName)>_<sanitized file name>`. */
    fun outputFileName(archiveIdentity: String, rawEntryName: String, fileName: String): String {
        val id = stableCandidateId(archiveIdentity + "\u0000" + rawEntryName).take(ID_PREFIX_LENGTH)
        return "${id}_${sanitizeFileName(fileName)}"
    }
}
