package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.junit.jupiter.api.Test
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JinBoReplayerTest {

  @Test
  fun `seek clamps to recording range and returns timestamp of frame at target`() {
    withReplayFile("replayer-test.log", listOf(1_000L, 2_000L, 12_000L)) {
      val replayer = JinBoReplayer(LIFT_ID)
      assertEquals(3, replayer.load(FILE_NAME))

      replayer.seek(11_500L)
      assertEquals(11_500L, replayer.currentTs)

      replayer.seek(0L)
      assertEquals(1_000L, replayer.currentTs)

      replayer.seek(Long.MAX_VALUE)
      assertEquals(12_000L, replayer.currentTs)
    }
  }

  @Test
  fun `seek applies one and ten second offsets without losing sparse frame selection`() {
    withReplayFile("replayer-test.log", listOf(1_000L, 2_000L, 12_000L)) {
      val replayer = JinBoReplayer(LIFT_ID)
      replayer.load(FILE_NAME)
      replayer.seek(12_000L)

      val changes = mutableListOf<Int>()
      replayer.onFrameChanged = { _, index, _ -> changes.add(index) }

      replayer.seek(replayer.currentTs - 1_000L)
      assertEquals(11_000L, replayer.currentTs)
      assertEquals(1, changes.single())

      replayer.seek(replayer.currentTs - 10_000L)
      assertEquals(1_000L, replayer.currentTs)
      assertEquals(0, changes.last())

      replayer.seek(replayer.currentTs + 10_000L)
      assertEquals(11_000L, replayer.currentTs)
      assertEquals(1, changes.last())
    }
  }

  @Test
  fun `playback advances virtual clock every five hundred milliseconds`() {
    withReplayFile("replayer-test.log", listOf(1_000L, 1_500L, 2_000L)) {
      val replayer = JinBoReplayer(LIFT_ID)
      replayer.load(FILE_NAME)
      val changes = mutableListOf<Pair<Int, Long>>()
      replayer.onFrameChanged = { _, index, clockTs -> changes.add(index to clockTs) }

      SwingUtilities.invokeAndWait { replayer.play() }
      try {
        val deadline = System.currentTimeMillis() + 2_000L
        while (changes.size < 2 && System.currentTimeMillis() < deadline) {
          Thread.sleep(20)
        }
        assertTrue(changes.size >= 2, "500ms playback ticks should advance at least twice")
        assertEquals(500L, changes[0].second - 1_000L)
        assertEquals(500L, changes[1].second - changes[0].second)
        assertEquals(1, changes[0].first)
        assertEquals(2, changes[1].first)
      } finally {
        SwingUtilities.invokeAndWait { replayer.pause() }
      }
    }
  }

  private fun withReplayFile(fileName: String, timestamps: List<Long>, block: () -> Unit) {
    val dir = File("logs").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(
      timestamps.joinToString("\n") { ts ->
        JsonHelper.mapper.writeValueAsString(
          JinBoFrame(
            liftId = LIFT_ID,
            ts = ts,
            curFloor = 1,
            h = 0.0,
            liftStatus = JinBoLiftStatus.Idle,
            doorStatus = JinBoDoorStatus.CLOSE,
            emergencyStopped = false,
            reqs = emptyList(),
            logs = emptyList(),
          ),
        )
      },
    )
    try {
      block()
    } finally {
      file.delete()
    }
  }

  private companion object {
    const val FILE_NAME = "replayer-test.log"
    const val LIFT_ID = "replayer-test-lift"
  }
}
