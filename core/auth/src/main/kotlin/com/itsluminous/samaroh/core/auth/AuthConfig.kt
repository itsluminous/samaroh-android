package com.itsluminous.samaroh.core.auth

/**
 * Build-time auth configuration (from `local.properties` → `BuildConfig`, §6 security).
 * Empty values are safe defaults: the app must build and run fully offline without them
 * (spec §4.0 — no permission or configuration is ever required to proceed).
 */
data class AuthConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val googleWebClientId: String,
) {
    /** True when a Supabase project is configured, so auth calls can be attempted. */
    val isSupabaseConfigured: Boolean = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /**
     * True when Sign-in with Google can be offered. When false, the Google button must
     * degrade gracefully to a localized "not configured" state — never crash.
     */
    val isGoogleSignInConfigured: Boolean = googleWebClientId.isNotBlank()

    companion object {
        fun fromBuildConfig(): AuthConfig =
            AuthConfig(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            )
    }
}
