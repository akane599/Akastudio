package com.akane.akastudio.core.ingestion.model

import java.security.MessageDigest

/** Lowercase hex SHA-256 of the UTF-8 bytes of [identity]. */
fun stableCandidateId(identity: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
