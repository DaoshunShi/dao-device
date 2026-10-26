package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import org.dao.device.lift.jinbo.fe.LiftEventLogPanel
import org.dao.device.lift.jinbo.fe.LiftReplayPanel
import org.dao.device.lift.jinbo.event.LiftEvent
import org.junit.jupiter.api.Test
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assumptions.assumeFalse
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

  @Test
  fun `listReplayFiles only returns files containing the selected lift`() {
    withReplayFile(FILE_NAME, listOf(1_000L), fileNamePattern = "replay-replayer-test.log") {
      assertEquals(listOf("replay-replayer-test.log"), JinBoReplayer.listReplayFiles(LIFT_ID))
      assertTrue(!JinBoReplayer.listReplayFiles("other-lift").contains("replay-replayer-test.log"))
    }
  }

  @Test
  fun `loading an existing replay entry from logs replays its first frame`() {
    val replay = JinBoReplayer("B")
    assertTrue(replay.load("replay.log") > 0)

    val changes = mutableListOf<Pair<Int, Long>>()
    replay.onFrameChanged = { _, index, clockTs -> changes.add(index to clockTs) }
    replay.seekIndex(0)

    assertTrue(replay.currentTs > 0)
    assertEquals(0, changes.single().first)
  }

  @Test
  fun `loading another file resets playback to forward speed`() {
    withReplayFile(FILE_NAME, listOf(1_000L, 2_000L), fileNamePattern = "replay-replayer-test.log") {
      val replay = JinBoReplayer(LIFT_ID)
      replay.load("replay-replayer-test.log")
      replay.rewind()

      replay.load("replay-replayer-test.log")

      assertEquals(1_000L, replay.currentTs)
      replay.fastForward()
      val changes = mutableListOf<Long>()
      replay.onFrameChanged = { _, _, clockTs -> changes.add(clockTs) }
      SwingUtilities.invokeAndWait { replay.play() }
      try {
        val deadline = System.currentTimeMillis() + 1_000L
        while (changes.isEmpty() && System.currentTimeMillis() < deadline) {
          Thread.sleep(20)
        }
        assertEquals(2_000L, changes.first())
      } finally {
        SwingUtilities.invokeAndWait { replay.pause() }
      }
    }
  }

  @Test
  fun `first frame fires state requests and replay logs through replay service`() {
    val frame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Up,
      doorStatus = JinBoDoorStatus.OPEN,
      emergencyStopped = false,
      reqs = listOf(
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
        JinBoReq(4, JinBoReqSource.InDoor, doorCodes = listOf(1)),
        JinBoReq(5, JinBoReqSource.Tcp, doorCodes = listOf(0)),
      ),
      logs = listOf("request-start:test"),
      doors = listOf(JinBoDoor(0, JinBoDoorStatus.OPEN), JinBoDoor(1, JinBoDoorStatus.CLOSE)),
    )
    withReplayFile(FILE_NAME, fileNamePattern = "replay-replayer-test.log", frames = listOf(frame)) {
      val events = mutableListOf<LiftEvent>()
      val listener = object : org.dao.device.common.GuiEventListener {
        override fun onEvent(event: LiftEvent) {
          events.add(event)
        }
      }
      JinBoEventBus.register(LIFT_ID, listener)
      val replay = JinBoReplayService(LIFT_ID)
      val rendered = mutableListOf<Pair<Int, Long>>()
      replay.onFrameChanged = { _, index, clockTs -> rendered.add(index to clockTs) }
      try {
        assertEquals(1, replay.openFile("replay-replayer-test.log"))
        SwingUtilities.invokeAndWait { }

        assertEquals(listOf(0 to 1_000L), rendered)
        assertEquals(
          listOf("liftState", "outside", "inside", "tcp"),
          events.map { it.topic },
        )
        val state = JsonHelper.mapper.readValue(events[0].msg, Map::class.java)
        assertEquals(3.0, (state["h"] as Number).toDouble())
        assertEquals("Up", state["liftStatus"])
        assertEquals(2, (state["doors"] as List<*>).size)
      } finally {
        replay.close()
        JinBoEventBus.listener[LIFT_ID]?.remove(listener)
      }
    }
  }

  @Test
  fun `failed replay switch keeps the active replay and replay mode`() {
    withReplayFile(FILE_NAME, listOf(1_000L), fileNamePattern = "replay-replayer-test.log") {
      val emptyFile = File("logs", "replay-empty-test.log").apply { writeText("") }
      val replay = JinBoReplayService(LIFT_ID)
      try {
        assertEquals(1, replay.openFile("replay-replayer-test.log"))
        assertEquals(0, replay.openFile(emptyFile.name))

        assertTrue(replay.isReplaying)
        assertEquals(1, replay.frameCount)
        assertTrue(JinBoEventBus.isReplayMode(LIFT_ID))
      } finally {
        replay.close()
        JinBoEventBus.setReplayMode(LIFT_ID, false)
        emptyFile.delete()
      }
    }
  }

  @Test
  fun `replay mode blocks realtime state events without blocking replay frames`() {
    withReplayFile(FILE_NAME, listOf(1_000L), fileNamePattern = "replay-replayer-test.log") {
      val stateEvents = mutableListOf<LiftEvent>()
      val listener = object : org.dao.device.common.GuiEventListener {
        override fun onEvent(event: LiftEvent) {
          stateEvents.add(event)
        }
      }
      JinBoEventBus.register(LIFT_ID, listener)
      JinBoEventBus.setReplayMode(LIFT_ID, false)
      try {
        JinBoEventBus.setReplayMode(LIFT_ID, true)
        JinBoEventBus.fireRealtime(LIFT_ID, LiftEvent("liftState", "realtime"))
        JinBoEventBus.fireReplay(LIFT_ID, LiftEvent("liftState", "replay"))

        assertEquals(listOf("replay"), stateEvents.map { it.msg })
      } finally {
        JinBoEventBus.setReplayMode(LIFT_ID, false)
        JinBoEventBus.listener[LIFT_ID]?.remove(listener)
      }
    }
  }

  @Test
  fun `enabling replay loads a frame timeline and switches to replay data source`() {
    val replayFrame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Down,
      doorStatus = JinBoDoorStatus.OPEN,
      emergencyStopped = false,
      reqs = emptyList(),
      logs = listOf("replay-log"),
    )
    withReplayFile(FILE_NAME, frames = listOf(replayFrame), fileNamePattern = "replay-replayer-test.log") {
      val states = mutableListOf<String>()
      val listener = object : org.dao.device.common.GuiEventListener {
        override fun onEvent(event: LiftEvent) {
          if (event.topic == "liftState") states.add(event.msg)
        }
      }
      JinBoEventBus.register(LIFT_ID, listener)
      assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
      val frame = JFrame()
      val replayPanel = LiftReplayPanel(
        config = JinBoConfig(
          id = LIFT_ID,
          port = 0,
          floors = listOf(JinBoFloor(1, "1F", 0.0), JinBoFloor(2, "2F", 3.0)),
          liftSpeed = 1.0,
          openCost = 100,
          closeCost = 100,
          closeDelay = 100,
        ),
        parentWindow = { frame },
      )
      try {
        SwingUtilities.invokeAndWait { replayPanel.setReplayEnabled(true) }

        assertEquals(1, replayPanel.loadedFrameCount)
        assertEquals(1, replayPanel.timelineFrameCount)
        assertTrue(replayPanel.isReplaying)
        assertTrue(states.single().contains("Down"))
        assertTrue(JinBoEventBus.isReplayMode(LIFT_ID))
      } finally {
        SwingUtilities.invokeAndWait { replayPanel.setReplayEnabled(false) }
        frame.dispose()
        JinBoEventBus.listener[LIFT_ID]?.remove(listener)
      }
    }
  }

  @Test
  fun `clicking replay toggle enters replay mode and keeps the toggle selected`() {
    val replayFrame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Down,
      doorStatus = JinBoDoorStatus.OPEN,
      emergencyStopped = false,
      reqs = emptyList(),
      logs = emptyList(),
    )
    withReplayFile(FILE_NAME, frames = listOf(replayFrame), fileNamePattern = "replay-replayer-test.log") {
      assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
      val frame = JFrame()
      val config = JinBoConfig(
        id = LIFT_ID,
        port = 0,
        floors = listOf(JinBoFloor(1, "1F", 0.0), JinBoFloor(2, "2F", 3.0)),
        liftSpeed = 1.0,
        openCost = 100,
        closeCost = 100,
        closeDelay = 100,
      )
      val replayPanel = LiftReplayPanel(config = config, parentWindow = { frame })
      val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})
      try {
        SwingUtilities.invokeAndWait {
          eventLogPanel.attachReplayPanel(replayPanel)
          eventLogPanel.replayToggleForTest().doClick()
        }

        assertTrue(eventLogPanel.replayToggleForTest().isSelected)
        assertTrue(replayPanel.isReplaying)
        assertTrue(JinBoEventBus.isReplayMode(LIFT_ID))

        SwingUtilities.invokeAndWait { eventLogPanel.replayToggleForTest().doClick() }
        assertTrue(!eventLogPanel.replayToggleForTest().isSelected)
        assertTrue(!JinBoEventBus.isReplayMode(LIFT_ID))
      } finally {
        SwingUtilities.invokeAndWait { replayPanel.setReplayEnabled(false) }
        frame.dispose()
      }
    }
  }

  @Test
  fun `old replay frame without direction falls back to lift status`() {
    val frame = JsonHelper.mapper.readValue(
      """{"liftId":"$LIFT_ID","ts":1000,"curFloor":2,"h":3.0,"liftStatus":"Down","doorStatus":"OPEN","emergencyStopped":false,"reqs":[],"logs":[]}""",
      JinBoFrame::class.java,
    )

    assertEquals(JinBoLiftStatus.Down, frame.direction)
  }

  private fun withReplayFile(
    fileName: String,
    timestamps: List<Long> = emptyList(),
    fileNamePattern: String = fileName,
    frames: List<JinBoFrame> = timestamps.map { ts ->
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
      )
    },
    block: () -> Unit,
  ) {
    val dir = File("logs").apply { mkdirs() }
    val file = File(dir, fileNamePattern)
    file.writeText(
      frames.joinToString("\n") { frame ->
        JsonHelper.mapper.writeValueAsString(frame)
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
