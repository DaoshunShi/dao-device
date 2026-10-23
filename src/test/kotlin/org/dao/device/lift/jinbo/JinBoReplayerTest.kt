package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

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
