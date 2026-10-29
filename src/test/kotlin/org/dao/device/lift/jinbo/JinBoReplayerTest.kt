package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import org.dao.device.lift.jinbo.fe.LiftEventLogPanel
import org.dao.device.lift.jinbo.fe.LiftReplayPanel
import org.dao.device.lift.jinbo.event.LiftEvent
import org.junit.jupiter.api.Test
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
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
    val logFile = File("logs/replay.log").apply { parentFile.mkdirs() }
    val original = if (logFile.exists()) logFile.readText() else null
    logFile.writeText(
      JsonHelper.mapper.writeValueAsString(
        JinBoFrame(
          liftId = "B",
          ts = 1_000L,
          curFloor = 1,
          h = 0.0,
          liftStatus = JinBoLiftStatus.Idle,
          emergencyStopped = false,
          reqs = emptyList(),
          logs = emptyList(),
        ),
      ),
    )

    try {
      val replay = JinBoReplayer("B")
      assertTrue(replay.load("replay.log") > 0)

      val changes = mutableListOf<Pair<Int, Long>>()
      replay.onFrameChanged = { _, index, clockTs -> changes.add(index to clockTs) }
      replay.seekIndex(0)

      assertTrue(replay.currentTs > 0)
      assertEquals(0, changes.single().first)
    } finally {
      if (original != null) logFile.writeText(original) else logFile.delete()
    }
  }

  @Test
  fun `loading another file resets playback rate to one`() {
    withReplayFile(FILE_NAME, listOf(1_000L, 2_000L), fileNamePattern = "replay-replayer-test.log") {
      val replay = JinBoReplayer(LIFT_ID)
      replay.load("replay-replayer-test.log")
      replay.setPlaybackRate(20.0)

      replay.load("replay-replayer-test.log")

      assertEquals(1_000L, replay.currentTs)
      val changes = mutableListOf<Long>()
      replay.onFrameChanged = { _, _, clockTs -> changes.add(clockTs) }
      SwingUtilities.invokeAndWait { replay.play() }
      try {
        val deadline = System.currentTimeMillis() + 1_000L
        while (changes.isEmpty() && System.currentTimeMillis() < deadline) {
          Thread.sleep(20)
        }
        assertEquals(1_500L, changes.first())
      } finally {
        SwingUtilities.invokeAndWait { replay.pause() }
      }
    }
  }

  @Test
  fun `playback rate changes only virtual time step`() {
    withReplayFile(FILE_NAME, listOf(1_000L, 6_000L, 11_000L)) {
      val replay = JinBoReplayer(LIFT_ID)
      replay.load(FILE_NAME)
      val changes = mutableListOf<Long>()
      replay.onFrameChanged = { _, _, clockTs -> changes.add(clockTs) }
      replay.setPlaybackRate(2.0)

      SwingUtilities.invokeAndWait { replay.play() }
      try {
        val deadline = System.currentTimeMillis() + 2_000L
        while (changes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
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
            emergencyStopped = false,
      reqs = listOf(
        JinBoRequestEntry(JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0))),
        JinBoRequestEntry(JinBoReq(4, JinBoReqSource.InDoor, doorCodes = listOf(1))),
        JinBoRequestEntry(JinBoReq(5, JinBoReqSource.Tcp, doorCodes = listOf(0))),
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
      val replay = JinBoReplayer(LIFT_ID)
      val rendered = mutableListOf<Pair<Int, Long>>()
      replay.onFrameChanged = { _, index, clockTs -> rendered.add(index to clockTs) }
      try {
        assertEquals(1, replay.openFile("replay-replayer-test.log"))
        replay.seekFirstFrame()
        SwingUtilities.invokeAndWait { }

        assertEquals(listOf(0 to 1_000L), rendered)
        assertEquals(
          listOf("liftState", "outside", "inside", "tcp", "reqs"),
          events.map { it.topic },
        )
        val state = JsonHelper.mapper.readValue(events[0].msg, Map::class.java)
        assertEquals(3.0, (state["h"] as Number).toDouble())
        assertEquals("Up", state["liftStatus"])
        assertEquals(2, (state["doors"] as List<*>).size)
        val entries: List<JinBoRequestEntry> = JsonHelper.mapper.readValue(
          events[4].msg,
          com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
        )
        assertEquals(listOf(3, 4, 5), entries.map { it.req.destFloor })
      } finally {
        replay.close()
        JinBoEventBus.removeListener(LIFT_ID, listener)
      }
    }
  }

  @Test
  fun `replay restores button state independently from request queue`() {
    val frame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Idle,
            emergencyStopped = false,
      buttons = listOf(
        JinBoRequestEntry(JinBoReq(2, JinBoReqSource.InDoor)),
        JinBoRequestEntry(JinBoReq(0, JinBoReqSource.InDoor)).copy(action = "关门"),
      ),
      reqs = listOf(JinBoRequestEntry(JinBoReq(5, JinBoReqSource.Tcp))),
      logs = emptyList(),
    )
    withReplayFile(FILE_NAME, fileNamePattern = "replay-replayer-test.log", frames = listOf(frame)) {
      val events = mutableListOf<LiftEvent>()
      val listener = object : org.dao.device.common.GuiEventListener {
        override fun onEvent(event: LiftEvent) {
          if (event.topic in setOf("inside", "reqs")) events.add(event)
        }
      }
      JinBoEventBus.register(LIFT_ID, listener)
      val replay = JinBoReplayer(LIFT_ID)
      try {
        replay.openFile("replay-replayer-test.log")
        replay.seekFirstFrame()
        SwingUtilities.invokeAndWait { }

        val inside: List<JinBoReq> = JsonHelper.mapper.readValue(
          events.single { it.topic == "inside" }.msg,
          com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
        )
        val reqs: List<JinBoRequestEntry> = JsonHelper.mapper.readValue(
          events.single { it.topic == "reqs" }.msg,
          com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
        )
        assertEquals(listOf(2), inside.map { it.destFloor })
        assertEquals(listOf(5), reqs.map { it.req.destFloor })
      } finally {
        replay.close()
        JinBoEventBus.removeListener(LIFT_ID, listener)
      }
    }
  }

  @Test
  fun `replaying an outside down call preserves its source and floor`() {
    val requests = listOf(JinBoRequestEntry(JinBoReq(7, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0))))
    val frame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Idle,
      emergencyStopped = false,
      buttons = requests,
      reqs = requests,
      logs = emptyList(),
    )
    withReplayFile(FILE_NAME, fileNamePattern = "replay-replayer-test.log", frames = listOf(frame)) {
      val events = mutableListOf<LiftEvent>()
      val listener = object : org.dao.device.common.GuiEventListener {
        override fun onEvent(event: LiftEvent) {
          events.add(event)
        }
      }
      JinBoEventBus.register(LIFT_ID, listener)
      val replay = JinBoReplayer(LIFT_ID)
      try {
        replay.openFile("replay-replayer-test.log")
        replay.seekFirstFrame()

        val outside: List<JinBoReq> = JsonHelper.mapper.readValue(
          events.single { it.topic == "outside" }.msg,
          com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
        )
        val queued: List<JinBoRequestEntry> = JsonHelper.mapper.readValue(
          events.single { it.topic == "reqs" }.msg,
          com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
        )
        assertEquals(requests.map { it.req }, outside)
        assertEquals("[]", events.single { it.topic == "inside" }.msg)
        assertEquals(requests, queued)
      } finally {
        replay.close()
        JinBoEventBus.removeListener(LIFT_ID, listener)
      }
    }
  }

  @Test
  fun `failed replay switch keeps the active replay and replay mode`() {
    withReplayFile(FILE_NAME, listOf(1_000L), fileNamePattern = "replay-replayer-test.log") {
      val emptyFile = File("logs", "replay-empty-test.log").apply { writeText("") }
      val replay = JinBoReplayer(LIFT_ID)
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
        JinBoEventBus.fire(LIFT_ID, LiftEvent("liftState", "replay"))

        assertEquals(listOf("replay"), stateEvents.map { it.msg })
      } finally {
        JinBoEventBus.setReplayMode(LIFT_ID, false)
        JinBoEventBus.removeListener(LIFT_ID, listener)
      }
    }
  }

  @Test
  fun `replay notifies both control and log panel listeners`() {
    withReplayFile(FILE_NAME, listOf(1_000L), fileNamePattern = "replay-replayer-test.log") {
      val controlStates = mutableListOf<Boolean>()
      val logStates = mutableListOf<Boolean>()
      lateinit var panel: LiftReplayPanel
      try {
        SwingUtilities.invokeAndWait {
          panel = LiftReplayPanel(
            config = JinBoConfig(
              id = LIFT_ID,
              port = 0,
              floors = listOf(JinBoFloor(1, "1F", 0.0)),
              liftSpeed = 2.0,
              openCost = 100,
              closeCost = 100,
              closeDelay = 100,
            ),
            parentWindow = { error("有效回放不应打开提示窗口") },
          )
          panel.onReplayStateChanged { controlStates.add(it) }
          panel.onReplayStateChanged { logStates.add(it) }
          panel.setReplayEnabled(true)
          panel.setReplayEnabled(false)
        }

        assertEquals(listOf(true, false), controlStates)
        assertEquals(listOf(true, false), logStates)
      } finally {
        SwingUtilities.invokeAndWait { panel.setReplayEnabled(false) }
      }
    }
  }

  @Test
  fun `leaving replay immediately restores live state buttons and requests without a simulation tick`() {
    val historicalRequests = listOf(JinBoRequestEntry(JinBoReq(2, JinBoReqSource.InDoor, doorCodes = listOf(0))))
    val frame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Down,
      direction = JinBoLiftStatus.Down,
      emergencyStopped = false,
      buttons = historicalRequests,
      reqs = historicalRequests,
      logs = emptyList(),
    )
    withReplayFile(FILE_NAME, fileNamePattern = "replay-replayer-test.log", frames = listOf(frame)) {
      for (liveRequests in listOf(
        emptyList(),
        listOf(
          JinBoReq(6, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
          JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(0)),
        ),
      )) {
        val runtime = JinBoRuntime(
          JinBoConfig(
            id = LIFT_ID,
            port = 0,
            floors = listOf(JinBoFloor(1, "1F", 0.0), JinBoFloor(2, "2F", 3.0), JinBoFloor(6, "6F", 15.0)),
            liftSpeed = 2.0,
            openCost = 100,
            closeCost = 100,
            closeDelay = 100,
          ),
        ).apply { setInitialFloor() }
        val events = mutableListOf<LiftEvent>()
        val listener = object : org.dao.device.common.GuiEventListener {
          override fun onEvent(event: LiftEvent) {
            events.add(event)
          }
        }
        val previousRuntime = JinBoServer.lifts.put(LIFT_ID, runtime)
        JinBoEventBus.register(LIFT_ID, listener)
        val replay = JinBoReplayer(LIFT_ID)
        try {
          replay.openFile("replay-replayer-test.log")
          replay.seekFirstFrame()
          assertTrue(events.single { it.topic == "inside" }.msg.contains("\"destFloor\":2"))
          events.clear()

          liveRequests.forEach { assertEquals("0", JinBoCommandService.request(LIFT_ID, it).code) }
          assertTrue(events.isEmpty())
          if (liveRequests.isEmpty()) assertTrue(runtime.isSimulationIdle())

          SwingUtilities.invokeAndWait { replay.close() }

          assertEquals(listOf("liftState", "outside", "inside", "tcp", "reqs"), events.map { it.topic })
          assertEquals("[]", events.single { it.topic == "inside" }.msg)
          for ((topic, source) in listOf("outside" to JinBoReqSource.OutDoor, "tcp" to JinBoReqSource.Tcp)) {
            val requests: List<JinBoReq> = JsonHelper.mapper.readValue(
              events.single { it.topic == topic }.msg,
              com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
            )
            assertEquals(liveRequests.filter { it.source == source }, requests)
          }
          val queued: List<JinBoRequestEntry> = JsonHelper.mapper.readValue(
            events.single { it.topic == "reqs" }.msg,
            com.fasterxml.jackson.module.kotlin.jacksonTypeRef(),
          )
          assertEquals(liveRequests, queued.map { it.req })
          val state = JsonHelper.mapper.readTree(events.single { it.topic == "liftState" }.msg)
          assertEquals(runtime.h, state["h"].asDouble())
          assertEquals("Idle", state["liftStatus"].asText())
          assertEquals("Idle", state["direction"].asText())
        } finally {
          JinBoEventBus.removeListener(LIFT_ID, listener)
          replay.close()
          if (previousRuntime == null) JinBoServer.lifts.remove(LIFT_ID) else JinBoServer.lifts[LIFT_ID] = previousRuntime
        }
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
        JinBoEventBus.removeListener(LIFT_ID, listener)
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
        assertEquals("● 回放中", eventLogPanel.replayToggleForTest().text)
        assertTrue(replayPanel.isReplaying)
        assertTrue(JinBoEventBus.isReplayMode(LIFT_ID))

        SwingUtilities.invokeAndWait { eventLogPanel.replayToggleForTest().doClick() }
        assertTrue(!eventLogPanel.replayToggleForTest().isSelected)
        assertEquals("回放", eventLogPanel.replayToggleForTest().text)
        assertTrue(!JinBoEventBus.isReplayMode(LIFT_ID))
      } finally {
        SwingUtilities.invokeAndWait { replayPanel.setReplayEnabled(false) }
        frame.dispose()
      }
    }
  }

  @Test
  fun `event log panel initializes replay toggle in realtime style`() {
    assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
    val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})

    assertEquals("回放", eventLogPanel.replayToggleForTest().text)
    assertTrue(!eventLogPanel.replayToggleForTest().isSelected)
  }

  @Test
  fun `leaving replay restores realtime logs including events received during replay`() {
    val replayFrame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Down,
            emergencyStopped = false,
      reqs = emptyList(),
      logs = listOf("replay-log"),
    )
    withReplayFile(FILE_NAME, frames = listOf(replayFrame), fileNamePattern = "replay-replayer-test.log") {
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
      val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})
      try {
        SwingUtilities.invokeAndWait {
          eventLogPanel.attachReplayPanel(replayPanel)
          eventLogPanel.appendLog(LiftEvent("before", "realtime-before"))
          eventLogPanel.replayToggleForTest().doClick()
        }
        assertTrue(eventLogPanel.logTextForTest().contains("replay-log"))

        SwingUtilities.invokeAndWait {
          eventLogPanel.appendLog(LiftEvent("during", "realtime-during"))
          eventLogPanel.replayToggleForTest().doClick()
        }

        val realtimeText = eventLogPanel.logTextForTest()
        assertTrue(realtimeText.contains("realtime-before"))
        assertTrue(realtimeText.contains("realtime-during"))
        assertTrue(!realtimeText.contains("replay-log"))
      } finally {
        SwingUtilities.invokeAndWait { replayPanel.setReplayEnabled(false) }
        frame.dispose()
      }
    }
  }

  @Test
  fun `enabling replay renders logs and gives the timeline measurable height`() {
    val replayFrame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Down,
            emergencyStopped = false,
      reqs = emptyList(),
      logs = listOf("replay-log"),
    )
    withReplayFile(FILE_NAME, frames = listOf(replayFrame), fileNamePattern = "replay-replayer-test.log") {
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
      val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})
      try {
        SwingUtilities.invokeAndWait {
          frame.add(eventLogPanel)
          frame.setSize(900, 600)
          eventLogPanel.attachReplayPanel(replayPanel)
          frame.isVisible = true
          eventLogPanel.replayToggleForTest().doClick()
          frame.validate()
        }

        assertTrue(eventLogPanel.logTextForTest().contains("replay-log"))
        assertTrue(eventLogPanel.timelineHeightForTest() > 0)
      } finally {
        SwingUtilities.invokeAndWait {
          replayPanel.setReplayEnabled(false)
          frame.dispose()
        }
      }
    }
  }

  @Test
  fun `replay renders recorded direction and lift status`() {
    val replayFrame = JinBoFrame(
      liftId = LIFT_ID,
      ts = 1_000L,
      curFloor = 2,
      h = 3.0,
      liftStatus = JinBoLiftStatus.Up,
      direction = JinBoLiftStatus.Down,
            emergencyStopped = false,
      reqs = emptyList(),
      logs = emptyList(),
    )
    withReplayFile(FILE_NAME, frames = listOf(replayFrame), fileNamePattern = "replay-replayer-test.log") {
      assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
      val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})

      SwingUtilities.invokeAndWait {
        eventLogPanel.renderReplayLogs(listOf(replayFrame), JinBoReplayPosition(0, replayFrame.ts))
      }

      assertEquals("方向: Down", eventLogPanel.directionTextForTest())
      assertEquals("状态: Up", eventLogPanel.liftStatusTextForTest())
    }
  }

  @Test
  fun `seek highlights log from the frame at the requested second`() {
    val frames = listOf(
      replayLogFrame(ts = 1_000L, log = "first-log"),
      replayLogFrame(ts = 2_000L, log = "second-log"),
    )
    withReplayFile(FILE_NAME, frames = frames, fileNamePattern = "replay-replayer-test.log") {
      assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
      val eventLogPanel = LiftEventLogPanel(configId = LIFT_ID, onShowLiftStatusChanged = {})

      SwingUtilities.invokeAndWait { eventLogPanel.renderReplayLogs(frames, JinBoReplayPosition(0, 1_500L)) }
      assertTrue(eventLogPanel.logStyleForTest("first-log")!!.isDefined(StyleConstants.Bold))

      SwingUtilities.invokeAndWait { eventLogPanel.renderReplayLogs(frames, JinBoReplayPosition(1, 2_000L)) }
      assertTrue(eventLogPanel.logStyleForTest("second-log")!!.isDefined(StyleConstants.Bold))
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

  private fun replayLogFrame(ts: Long, log: String): JinBoFrame = JinBoFrame(
    liftId = LIFT_ID,
    ts = ts,
    curFloor = 1,
    h = 0.0,
    liftStatus = JinBoLiftStatus.Idle,
        emergencyStopped = false,
    reqs = emptyList(),
    logs = listOf(log),
  )

  private companion object {
    const val FILE_NAME = "replayer-test.log"
    const val LIFT_ID = "replayer-test-lift"
  }
}
