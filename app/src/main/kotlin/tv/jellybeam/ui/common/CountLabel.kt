package tv.jellybeam.ui.common

/** `"1 EPISODE"` / `"3 EPISODES"`: the count and whichever noun agrees with it. */
fun countLabel(count: ULong, one: String, other: String): String = "$count ${if (count == 1uL) one else other}"

fun countLabel(count: Int, one: String, other: String): String = countLabel(count.toULong(), one, other)
