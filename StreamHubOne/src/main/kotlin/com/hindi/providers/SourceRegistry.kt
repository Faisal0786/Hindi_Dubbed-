package com.hindi.providers

data class SourceProvider(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false
)

object SourceRegistry {
    val providers = mutableListOf<SourceProvider>()
}