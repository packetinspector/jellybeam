package tv.jellybeam.perf

/**
 * Pure name mapping and log-line formatting for `ProfileVerifier.CompilationStatus` result
 * codes -- [tv.jellybeam.MainActivity.onResume] calls [line] to log whether the embedded Baseline
 * Profile compiled into this install (docs/10-perf-logging.md "Profile status"). Zero
 * `androidx.profileinstaller`/`android.*` dependency: constants are copied, not referenced,
 * keeping this plain-JVM-testable without Robolectric.
 */
object ProfileStatus {
    // Copied from ProfileVerifier.CompilationStatus (profileinstaller 1.4.1, verified via
    // `javap -constants`; re-verify on version bump). Only the literal int values are needed.
    private const val RESULT_CODE_NO_PROFILE = 0
    private const val RESULT_CODE_COMPILED_WITH_PROFILE = 1
    private const val RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION = 2
    private const val RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING = 3
    private const val RESULT_CODE_ERROR_PACKAGE_NAME_DOES_NOT_EXIST = 65536
    private const val RESULT_CODE_ERROR_CACHE_FILE_EXISTS_BUT_CANNOT_BE_READ = 131072
    private const val RESULT_CODE_ERROR_CANT_WRITE_PROFILE_VERIFICATION_RESULT_CACHE_FILE = 196608
    private const val RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION = 262144
    private const val RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED = 327680

    /** Short name for a `ProfileVerifier.CompilationStatus` result code; `UNKNOWN` for anything not
     * listed above.
     */
    fun name(resultCode: Int): String = when (resultCode) {
        RESULT_CODE_NO_PROFILE -> "NO_PROFILE"
        RESULT_CODE_COMPILED_WITH_PROFILE -> "COMPILED_WITH_PROFILE"
        RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION -> "PROFILE_ENQUEUED_FOR_COMPILATION"
        RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING -> "COMPILED_WITH_PROFILE_NON_MATCHING"
        RESULT_CODE_ERROR_PACKAGE_NAME_DOES_NOT_EXIST -> "ERROR_PACKAGE_NAME_DOES_NOT_EXIST"
        RESULT_CODE_ERROR_CACHE_FILE_EXISTS_BUT_CANNOT_BE_READ -> "ERROR_CACHE_FILE_EXISTS_BUT_CANNOT_BE_READ"
        RESULT_CODE_ERROR_CANT_WRITE_PROFILE_VERIFICATION_RESULT_CACHE_FILE ->
            "ERROR_CANT_WRITE_PROFILE_VERIFICATION_RESULT_CACHE_FILE"
        RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION -> "ERROR_UNSUPPORTED_API_VERSION"
        RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED -> "ERROR_NO_PROFILE_EMBEDDED"
        else -> "UNKNOWN"
    }

    /** One `perf profile ...` log line (docs/10-perf-logging.md "Profile status"). */
    fun line(resultCode: Int, compiledWithProfile: Boolean, enqueued: Boolean): String =
        "perf profile status=${name(resultCode)} code=$resultCode compiled=$compiledWithProfile enqueued=$enqueued"
}
