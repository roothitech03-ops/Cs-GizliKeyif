package roothitech

/**
 * Host injects this at init. Nothing hardcoded in ProviderPlugin.
 */
data class ProviderConfig(
    val baseUrl: String = "https://example.com",
    val userAgent: String = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
    val timeoutMs: Long = 20_000,
    val debug: Boolean = false
)