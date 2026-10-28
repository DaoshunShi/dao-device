package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import org.dao.device.lift.jinbo.event.LiftEvent
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JinBoServerTest {

  @Test
  fun `all elevator configurations use default lift speed 2`() {
    val configFiles = File("config").listFiles { file -> file.extension == "json" }.orEmpty()

    assertTrue(configFiles.isNotEmpty())
    configFiles.forEach { file ->
      val config = JsonHelper.mapper.readValue(file, JinBoConfig::class.java)
      assertEquals(2.0, config.liftSpeed, file.name)
    }
  }

  @Test
  fun `opposite lift status reverses direction while idle stays idle`() {
    assertEquals(JinBoLiftStatus.Down, JinBoLiftStatus.Up.opposite())
    assertEquals(JinBoLiftStatus.Up, JinBoLiftStatus.Down.opposite())
    assertEquals(JinBoLiftStatus.Idle, JinBoLiftStatus.Idle.opposite())
  }

  @Test
  fun `door advances through open close states using operation timings`() {
    val door = JinBoDoor(0)
    val startedAt = Date(0)
    val config = JinBoDoorConfig(openCost = 100, closeCost = 100, closeDelay = 200)
    door.setStatus(JinBoDoorStatus.OPENING, startedAt)

    door.advance(Date(99), config)
    assertEquals(JinBoDoorStatus.OPENING, door.status)

    door.advance(Date(100), config)
    assertEquals(JinBoDoorStatus.OPEN, door.status)

    door.advance(Date(299), config)
    assertEquals(JinBoDoorStatus.OPEN, door.status)

    door.advance(Date(300), config)
    assertEquals(JinBoDoorStatus.CLOSING, door.status)

    door.advance(Date(400), config)
    assertEquals(JinBoDoorStatus.CLOSE, door.status)
  }

  @Test
  fun `door handles closing same floor refresh and emergency timer shift`() {
    val door = JinBoDoor(0, status = JinBoDoorStatus.OPEN, openOn = Date(0), opStartOn = Date(0))

    door.refreshSameFloorTimer(Date(10))
    assertEquals(10, door.openOn?.time)

    door.startClosing(Date(20))
    assertEquals(JinBoDoorStatus.CLOSING, door.status)

    door.refreshSameFloorTimer(Date(30))
    assertEquals(JinBoDoorStatus.OPENING, door.status)
    assertEquals(30, door.opStartOn?.time)

    door.shiftOperationTimers(100)
    assertEquals(130, door.openOn?.time)
    assertEquals(130, door.opStartOn?.time)
  }

  @Test
  fun `button press directly enqueues the request`() {
    val runtime = newRuntime(initialFloor = 3)

    withLift(runtime) {
      try {
        val req = JinBoReq(2, JinBoReqSource.InDoor)
        assertEquals("0", JinBoBtnService.pressButton(LIFT_ID, req).code)
        assertTrue(runtime.reqs.any { it.destFloor == 2 })
        assertTrue(JinBoBtnService.buttons(LIFT_ID).single { it.destFloor == 2 && it.action == "呼梯" }.active)

        val invalidReq = JinBoReq(3, JinBoReqSource.InDoor, doorCodes = listOf(9))
        assertEquals("400", JinBoBtnService.pressButton(LIFT_ID, invalidReq).code)
        assertTrue(JinBoBtnService.buttons(LIFT_ID).none { it.destFloor == 3 })
        assertTrue(runtime.reqs.none { it == invalidReq })
      } finally {
        JinBoBtnService.reset(LIFT_ID)
      }
    }
  }

  @Test
  fun `replay blocks page requests but keeps tcp and http requests alive`() {
    val runtime = newRuntime(initialFloor = 3)

    withLift(runtime) {
      try {
        JinBoEventBus.setReplayMode(LIFT_ID, true)

        assertEquals("409", JinBoBtnService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor)).code)
        assertEquals("409", JinBoBtnService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down)).code)
        assertEquals("409", JinBoBtnService.pressOpen(LIFT_ID).code)
        assertEquals("409", JinBoBtnService.pressClose(LIFT_ID, fromTcp = true).code)
        assertTrue(runtime.reqs.isEmpty())
        assertTrue(JinBoBtnService.buttons(LIFT_ID).isEmpty())

        assertEquals("0", JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp)).code)
        assertTrue(runtime.reqs.any { it.destFloor == 2 })
      } finally {
        JinBoEventBus.setReplayMode(LIFT_ID, false)
        JinBoBtnService.reset(LIFT_ID)
      }
    }
  }

  @Test
  fun `request queue supplies the button highlight`() {
    val runtime = newRuntime(initialFloor = 3)

    withLift(runtime) {
      try {
        JinBoBtnService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor))
        val returnedReq = runtime.reqs.single()
        assertEquals(listOf(0), returnedReq.doorCodes)

        assertTrue(JinBoBtnService.buttons(LIFT_ID).single().active)

        runtime.reqs.clear()
        JinBoBtnService.refresh(LIFT_ID, runtime.reqs)
        assertTrue(JinBoBtnService.buttons(LIFT_ID).isEmpty())
      } finally {
        JinBoBtnService.reset(LIFT_ID)
      }
    }
  }

  @Test
  fun `consuming a request emits cleared button state`() {
    val runtime = newRuntime(initialFloor = 3)
    val events = mutableListOf<LiftEvent>()
    val listener = object : GuiEventListener {
      override fun onEvent(event: LiftEvent) {
        events.add(event)
      }
    }
    JinBoEventBus.register(LIFT_ID, listener)

    withLift(runtime) {
      try {
        JinBoBtnService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor))
        runtime.reqs.clear()
        JinBoBtnService.refresh(LIFT_ID, runtime.reqs)

        val insideEvents = events.filter { it.topic == "inside" }
        assertTrue(insideEvents.dropLast(1).all { it.msg != "[]" })
        assertEquals("[]", insideEvents.last().msg)
      } finally {
        JinBoBtnService.reset(LIFT_ID)
        JinBoEventBus.listener[LIFT_ID]?.remove(listener)
      }
    }
  }

  @Test
  fun `arrives and opens all requested doors before clearing request`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.processLift(runtime)
      assertEquals(2, runtime.curFloor)
      assertEquals(listOf(OpenTracker.OPENING, OpenTracker.OPENING), runtime.doorStates())
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.processLift(runtime)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `does not clear a same floor request before its door reaches open`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      assertEquals(JinBoDoorStatus.OPENING, runtime.doors[0].status)
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.processLift(runtime)

      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `repeated same floor request restarts auto close delay for requested doors`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1)
    val request = JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(0, 1))

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, request)
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      val firstOpenedAt = runtime.doors.map { assertNotNull(it.openOn).time }

      Thread.sleep(2)
      JinBoServer.request(LIFT_ID, request)

      assertTrue(runtime.doors.all { it.status == JinBoDoorStatus.OPEN })
      assertTrue(runtime.doors.zip(firstOpenedAt).all { (door, openedAt) -> door.openOn!!.time > openedAt })
    }
  }

  @Test
  fun `keeps doors open after a different floor request until a close command`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1)

    withLift(runtime) {
      JinBoServer.open(LIFT_ID, listOf(0, 1))
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())

      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
      JinBoServer.processLift(runtime)

      assertEquals(1, runtime.curFloor)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())
      assertFalse(runtime.isMoving())

      JinBoServer.close(LIFT_ID)
      JinBoServer.processLift(runtime)

      assertTrue(runtime.doors.all { it.status != JinBoDoorStatus.OPEN })
      assertEquals(2, runtime.curFloor)
    }
  }

  @Test
  fun `same floor request reopens its doors while auto close delay is still active`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1, closeCost = 1_000)
    val request = JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(0, 1))

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, request)
      JinBoServer.processLift(runtime)
      runtime.doors.forEach { it.setStatus(JinBoDoorStatus.CLOSING, Date()) }

      JinBoServer.request(LIFT_ID, JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(1)))

      assertTrue(runtime.reqs.any { it.destFloor == 1 })
      assertEquals(JinBoDoorStatus.CLOSING, runtime.doors[0].status)
      assertEquals(JinBoDoorStatus.OPENING, runtime.doors[1].status)
      assertEquals(setOf(1), runtime.requestedFloors())
    }
  }

  @Test
  fun `stops at nearer request in the same travel direction before the original floor`() {
    val runtime = newRuntime(initialFloor = 1, liftSpeed = 1.0)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      runtime.lastMoveOn = System.currentTimeMillis() - 10_000
      JinBoServer.processLift(runtime)
      assertEquals(3, runtime.targetFloor)
      assertTrue(runtime.isMoving())
      runtime.lastMoveOn = null
      JinBoServer.processLift(runtime)
      assertTrue(runtime.isMoving())
      assertEquals(3, runtime.targetFloor)
      assertTrue(runtime.h > 0.0)

      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)

      assertEquals(2, runtime.targetFloor)
      assertTrue(runtime.isMoving())
      assertTrue(runtime.requestedFloors().contains(2))
    }
  }

  @Test
  fun `does not stop for a nearer request in the opposite travel direction`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.processLift(runtime)

      assertEquals(3, runtime.curFloor)
      assertEquals(setOf(2), runtime.requestedFloors())
    }
  }

  @Test
  fun `out goto opposite to an upward pass stays pending at the requested floor`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.processLift(runtime)

      assertEquals(3, runtime.curFloor)
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.close(LIFT_ID)
      repeat(3) { JinBoServer.processLift(runtime) }

      assertEquals(2, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.none { it.destFloor == 2 && it.type == JinBoLiftStatus.Down })
    }
  }

  @Test
  fun `out goto opposite the current travel direction is left pending at a non endpoint floor`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      repeat(2) { JinBoServer.processLift(runtime) }
      assertEquals(3, runtime.curFloor)

      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)

      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(setOf(2), runtime.requestedFloors())
    }
  }

  @Test
  fun `direction changes while doors are open without consuming the opposite endpoint request`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 2, closeDelay = 1_000)

    withLift(runtime) {
      runtime.curFloor = 3
      runtime.h = 6.0
      runtime.liftStatus = JinBoLiftStatus.Idle
      runtime.direction = JinBoLiftStatus.Up
      runtime.doors.forEach {
        it.startOpening(Date())
        it.markOpen(Date())
      }
      assertTrue(runtime.doors.all { it.status == JinBoDoorStatus.OPEN })

      val currentFloorRequest = JinBoServer.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      assertEquals("0", currentFloorRequest.code)
      assertTrue(runtime.reqs.any { it.destFloor == 3 })
      assertTrue(runtime.reqs.any { it.destFloor == 1 })
      JinBoServer.processLift(runtime)

      assertEquals(JinBoLiftStatus.Down, runtime.direction)
      assertTrue(runtime.doors.any { it.status == JinBoDoorStatus.OPEN })
      assertTrue(runtime.reqs.none { it.destFloor == 3 })
      assertTrue(runtime.reqs.any { it.destFloor == 1 })

      JinBoServer.close(LIFT_ID)
      repeat(3) { JinBoServer.processLift(runtime) }
      assertEquals(1, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `direction is chosen during door hold before closing starts`() {
    val runtime = newRuntime(initialFloor = 1, closeDelay = 100)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      repeat(2) { JinBoServer.processLift(runtime) }
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)

      JinBoServer.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      assertEquals(JinBoLiftStatus.Up, runtime.direction)
      JinBoServer.processLift(runtime)

      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(JinBoLiftStatus.Down, runtime.direction)
      assertTrue(runtime.reqs.any { it.destFloor == 1 })

      Thread.sleep(120)
      JinBoServer.processLift(runtime)
      assertEquals(JinBoDoorStatus.CLOSING, runtime.doors[0].status)
      assertEquals(JinBoLiftStatus.Down, runtime.direction)
    }
  }

  @Test
  fun `same direction call at top floor is served while descending`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      repeat(3) { JinBoServer.processLift(runtime) }
      assertEquals(3, runtime.curFloor)
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      repeat(2) { JinBoServer.processLift(runtime) }

      assertEquals(3, runtime.curFloor)
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `endpoint calls are cleared only after the elevator opens at their floors`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )

      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      assertEquals(3, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(setOf(1), runtime.requestedFloors())

      JinBoServer.close(LIFT_ID)
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      assertEquals(1, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `endpoint calls received while moving are served before travel continues`() {
    val runtime = newRuntime(initialFloor = 2, liftSpeed = 4.0)

    withLift(runtime) {
      runtime.doors.forEach { it.setStatus(JinBoDoorStatus.CLOSE, Date()) }
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      assertTrue(runtime.h > 3.0 && runtime.h < 6.0)

      JinBoServer.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      assertEquals(3, runtime.targetFloor)
      runtime.lastMoveOn = System.currentTimeMillis() - 1_000
      JinBoServer.processLift(runtime)
      assertEquals(3, runtime.curFloor)
      JinBoServer.processLift(runtime)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(setOf(1), runtime.requestedFloors())

      JinBoServer.close(LIFT_ID)
      JinBoServer.processLift(runtime)
      runtime.lastMoveOn = System.currentTimeMillis() - 1_000
      JinBoServer.processLift(runtime)
      runtime.lastMoveOn = System.currentTimeMillis() - 1_000
      JinBoServer.processLift(runtime)
      assertEquals(1, runtime.curFloor)
      JinBoServer.processLift(runtime)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `idle elevator chooses next request according to current direction`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.request(LIFT_ID, JinBoReq(1, JinBoReqSource.Tcp))
      runtime.direction = JinBoLiftStatus.Down
      JinBoServer.processLift(runtime)

      assertEquals(1, runtime.curFloor)
      assertTrue(abs(runtime.h - runtime.config.floors.first { it.index == 1 }.height) < 1e-6)
    }
  }

  @Test
  fun `status exposes every door in code order with protocol open status`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 2)
    runtime.doors[0].setStatus(JinBoDoorStatus.OPEN, Date(0))
    runtime.doors[1].setStatus(JinBoDoorStatus.CLOSING, Date(0))

    withLift(runtime) {
      val response = JinBoServer.status(LIFT_ID)

      assertEquals(listOf(JinBoDoorResp(0, 1), JinBoDoorResp(1, 4)), response.doors)
      assertEquals(JinBoDoorStatus.OPEN, response.doorStatus)
    }
  }

  @Test
  fun `reset clears calls and their highlights`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 2)

    withLift(runtime) {
      try {
        JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
        JinBoBtnService.pressButton(LIFT_ID, JinBoReq(3, JinBoReqSource.InDoor))
        assertTrue(runtime.reqs.isNotEmpty())

        assertEquals("0", JinBoServer.resetRequests(LIFT_ID).code)

        assertTrue(runtime.reqs.isEmpty())
        assertTrue(JinBoBtnService.requests(LIFT_ID).isEmpty())
        assertFalse(JinBoBtnService.buttons(LIFT_ID).any { it.action == "呼梯" })
      } finally {
        JinBoBtnService.reset(LIFT_ID)
      }
    }
  }

  @Test
  fun `initial floor uses configured floor`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      runtime.setInitialFloor()
    }

    assertEquals(2, runtime.curFloor)
    assertEquals(3.0, runtime.h)
  }

  @Test
  fun `emergency stop pauses auto close timer`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoServer.open(LIFT_ID, listOf(0))
      JinBoServer.processLift(runtime)
      val openedAt = runtime.doors[0].openOn

      JinBoServer.emergencyStop(LIFT_ID)
      Thread.sleep(2)
      JinBoServer.processLift(runtime)
      JinBoServer.releaseEmergencyStop(LIFT_ID)

      assertTrue(runtime.doors[0].openOn!!.time > openedAt!!.time)
    }
  }

  private fun newRuntime(
    doorCount: Int = 1,
    initialFloor: Int = 1,
    liftSpeed: Double = 100.0,
    closeCost: Int = 0,
    closeDelay: Int = 60_000,
  ): JinBoRuntime = JinBoRuntime(
    JinBoConfig(
      id = LIFT_ID,
      port = 0,
      floors = listOf(
        JinBoFloor(1, "1F", 0.0),
        JinBoFloor(2, "2F", 3.0),
        JinBoFloor(3, "3F", 6.0),
      ),
      liftSpeed = liftSpeed,
      openCost = 0,
      closeCost = closeCost,
      closeDelay = closeDelay,
      doorCount = doorCount,
      initialFloor = initialFloor,
    ),
  ).apply {
    curFloor = initialFloor
    h = config.floors.first { it.index == initialFloor }.height
  }

  private fun JinBoRuntime.requestedFloors(): Set<Int> = reqs.map { it.destFloor }.toSet()

  private fun JinBoRuntime.doorStates(): List<OpenTracker> = doors.map { it.status.toTracker() }

  private fun JinBoDoorStatus.toTracker(): OpenTracker = when (this) {
    JinBoDoorStatus.OPEN -> OpenTracker.OPEN
    JinBoDoorStatus.OPENING -> OpenTracker.OPENING
    JinBoDoorStatus.CLOSE -> OpenTracker.CLOSE
    JinBoDoorStatus.CLOSING -> OpenTracker.CLOSING
    JinBoDoorStatus.ERROR -> OpenTracker.ERROR
  }

  private fun withLift(runtime: JinBoRuntime, block: () -> Unit) {
    JinBoServer.lifts[LIFT_ID] = runtime
    try {
      block()
    } finally {
      JinBoServer.lifts.remove(LIFT_ID)
    }
  }

  private enum class OpenTracker {
    OPEN,
    OPENING,
    CLOSE,
    CLOSING,
    ERROR,
  }

  private companion object {
    const val LIFT_ID = "test-lift"
  }
}
