package com.monimo.api.auth

import java.security.MessageDigest

// refresh 토큰 원문 → refresh_tokens.token_hash (SHA-256 hex 64자)
internal fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
