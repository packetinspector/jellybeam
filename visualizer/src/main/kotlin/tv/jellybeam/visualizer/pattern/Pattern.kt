package tv.jellybeam.visualizer.pattern

/** docs/30 §5 (E7, E9): the ten patterns in Left/Right cycle order; [key] is the stored setting, never the enum name (R8). */
enum class Pattern(val key: String) {
    BARS("bars"),
    RADIAL("radial"),
    WATERFALL("waterfall"),
    KALEIDO("kaleido"),
    SWIRL("swirl"),
    FIRE("fire"),
    PHOSPHOR("phosphor"),
    SUPERSCOPE("superscope"),
    RIDGELINE("ridgeline"),
    RIPPLES("ripples"),
}

/** docs/30 §7 (D9, E9): Left/Right step through [Pattern] and wrap at both ends. */
object PatternCycle {
    /** E9: a fresh profile opens here. */
    val DEFAULT = Pattern.SWIRL

    fun next(p: Pattern): Pattern = Pattern.entries[(p.ordinal + 1) % Pattern.entries.size]

    fun previous(p: Pattern): Pattern = Pattern.entries[(p.ordinal + Pattern.entries.size - 1) % Pattern.entries.size]

    /** E8: retired keys open their closest successor. */
    private val retired = mapOf(
        "scope" to Pattern.PHOSPHOR,
        "xy" to Pattern.PHOSPHOR,
        "tunnel" to Pattern.SWIRL,
        "spiral" to Pattern.SWIRL,
        "pulse" to Pattern.SUPERSCOPE,
        "starfield" to Pattern.SUPERSCOPE,
        "ring" to Pattern.SUPERSCOPE,
        "ribbon" to Pattern.SUPERSCOPE,
        // Removed after UAT (docs/30 §5): both open the closest field-like pattern.
        "plasma" to Pattern.SWIRL,
        "tube" to Pattern.SWIRL,
    )

    /** The stored setting's pattern: current keys, then retired ones (E8); `null` is [DEFAULT], anything else [Pattern.BARS]. */
    fun fromKey(key: String?): Pattern {
        if (key == null) return DEFAULT
        return Pattern.entries.firstOrNull { it.key == key } ?: retired[key] ?: Pattern.BARS
    }
}
