package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/05 external, docs/17 §2: what a PlaybackActivity does with an intent that reaches it alive. */
class NewIntentActionTest {
    private val dashed = "0123abcd-4567-89ef-0123-456789abcdef"

    private fun action(
        current: String? = "item-1",
        new: String = "item-1",
        fromStart: Boolean = false,
        startMs: Long? = null,
        group: Boolean = false,
    ) = newIntentAction(current, new, fromStart, startMs, group)

    @Test
    fun `the same item with no start extra expands only`() {
        assertEquals(NewIntentAction.Expand, action())
    }

    @Test
    fun `the same item with a start extra replaces at that position`() {
        assertEquals(NewIntentAction.Replace, action(fromStart = true))
        assertEquals(NewIntentAction.Replace, action(startMs = 0L))
        assertEquals(NewIntentAction.Replace, action(startMs = 90_000L))
    }

    @Test
    fun `another item replaces whatever the start extras say`() {
        assertEquals(NewIntentAction.Replace, action(new = "item-2"))
        assertEquals(NewIntentAction.Replace, action(new = "item-2", fromStart = true))
        assertEquals(NewIntentAction.Replace, action(new = "item-2", startMs = 5L))
    }

    @Test
    fun `no live session replaces, same id or not`() {
        assertEquals(NewIntentAction.Replace, action(current = null))
        assertEquals(NewIntentAction.Replace, action(current = null, new = "item-2"))
    }

    @Test
    fun `a group launch is always the group's, even for the same item or with start extras`() {
        assertEquals(NewIntentAction.Group, action(group = true))
        assertEquals(NewIntentAction.Group, action(new = "item-2", group = true))
        assertEquals(NewIntentAction.Group, action(current = null, group = true))
        assertEquals(NewIntentAction.Group, action(startMs = 5L, group = true))
    }

    @Test
    fun `ids compare in canonical form so a stale spelling still matches`() {
        assertEquals(NewIntentAction.Expand, action(current = "0123ABCD456789EF" + "0123456789ABCDEF", new = dashed))
        assertEquals(NewIntentAction.Expand, action(current = dashed.uppercase(), new = dashed))
        assertEquals(NewIntentAction.Replace, action(current = "0123abcd-4567-89ef-0123-456789abcdee", new = dashed))
        assertEquals(NewIntentAction.Replace, action(current = "ITEM-1", new = "item-1"))
    }
}
