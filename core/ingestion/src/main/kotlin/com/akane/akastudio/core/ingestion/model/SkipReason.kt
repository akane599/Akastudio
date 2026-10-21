package com.akane.akastudio.core.ingestion.model

enum class SkipReason { DIRECTORY, JUNK_FILE, HIDDEN_FILE, UNSUPPORTED_TYPE, UNREADABLE, UNDECODABLE, DUPLICATE }
