package org.example.project.guidance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun task(id: String, title: String, examples: List<String>) = GuidanceTask(
    id = id,
    title = title,
    targetPackage = "com.example",
    steps = listOf(GuidanceStep("noop", listOf(Matcher.TextEquals("noop")))),
    examples = examples,
)

class VoiceTaskMatcherTest {

    private val rideTask = task("ride", "Get a ride", listOf("get a ride", "book a car", "call a cab"))
    private val alarmTask = task("alarm", "Set an alarm", listOf("wake me up", "set a timer"))
    private val tasks = listOf(rideTask, alarmTask)

    @Test
    fun `matches on a direct example phrase`() {
        assertEquals(rideTask, VoiceTaskMatcher.match("book a car please", tasks))
    }

    @Test
    fun `matches on the title even without saying an example`() {
        assertEquals(alarmTask, VoiceTaskMatcher.match("I need to set an alarm for tomorrow", tasks))
    }

    @Test
    fun `matches via partial word overlap`() {
        assertEquals(rideTask, VoiceTaskMatcher.match("can you call a taxi for me", tasks))
    }

    @Test
    fun `returns null for unrelated speech`() {
        assertNull(VoiceTaskMatcher.match("what's the weather like today", tasks))
    }

    @Test
    fun `returns null for blank transcript`() {
        assertNull(VoiceTaskMatcher.match("   ", tasks))
    }

    @Test
    fun `stopwords alone never produce a false match`() {
        assertNull(VoiceTaskMatcher.match("can you help me please", tasks))
    }
}
