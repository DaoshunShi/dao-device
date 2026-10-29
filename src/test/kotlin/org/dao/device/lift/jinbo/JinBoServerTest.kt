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
  fun `opening floor includes the height tolerance boundary on either side`() {
    val runtime = newRuntime()
    val epsilon = JinBoConstants.FLOOR_EPSILON

    for (height in listOf(0.0, -epsilon / 2, epsilon / 2, -epsilon, epsilon)) {
      runtime.h = height
      assertEquals(1, runtime.floorForOpening(), "height=$height")
    }
    for (height in listOf(-2 * epsilon, 2 * epsilon, 1.5)) {
      runtime.h = height
      assertNull(runtime.floorForOpening(), "height=$height")
    }
  }

  @Test
  fun `opening floor uses actual height and skips disabled floors`() {
    val config = newRuntime().config.copy(
      floors = listOf(
        JinBoFloor(0, "地下室", -3.0),
        JinBoFloor(1, "1F", 0.0),
        JinBoFloor(2, "2F", 3.0, disabled = true),
      ),
    )
    val runtime = JinBoRuntime(config)
    runtime.curFloor = 2
    runtime.h = -3.0
    assertEquals(0, runtime.floorForOpening())
    assertEquals(2, runtime.curFloor)

    runtime.h = 0.0
    assertEquals(1, runtime.floorForOpening())
    runtime.h = 3.0
    assertNull(runtime.floorForOpening())
  }

  @Test
  fun `a request for the last passed floor does not open doors between floors`() {
    val runtime = newRuntime(liftSpeed = 2.0)
    runtime.h = 0.5
    runtime.direction = JinBoLiftStatus.Up
    runtime.liftStatus = JinBoLiftStatus.Up

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(1))
      JinBoCommandService.request(LIFT_ID, JinBoReq(3))

      JinBoServer.processLift(runtime)

      assertEquals(0.7, runtime.h, 1e-9)
      assertEquals(JinBoLiftStatus.Up, runtime.liftStatus)
      assertEquals(JinBoDoorStatus.CLOSE, runtime.doors.single().status)
      assertEquals(setOf(1, 3), runtime.requestedFloors())
    }
  }

  @Test
  fun `door service only starts opening at a floor within the height tolerance`() {
    val runtime = newRuntime()
    runtime.h = 0.5

    assertNotNull(JinBoDoorService.open(runtime, listOf(0)))
    assertEquals(JinBoDoorStatus.CLOSE, runtime.doors.single().status)

    runtime.h = JinBoConstants.FLOOR_EPSILON
    assertNull(JinBoDoorService.open(runtime, listOf(0)))
    assertEquals(JinBoDoorStatus.OPENING, runtime.doors.single().status)
  }

  @Test
  fun `explicit opening and request clearing require floor height alignment`() {
    val runtime = newRuntime()
    runtime.h = 0.5
    val request = JinBoReq(1, doorCodes = listOf(0))

    withLift(runtime) {
      assertEquals("400", JinBoCommandService.open(LIFT_ID, listOf(0)).code)
      assertEquals(JinBoDoorStatus.CLOSE, runtime.doors.single().status)

      runtime.reqs.add(request)
      runtime.doors.single().markOpen(Date())
      JinBoRequestSelector.cleanOpenedFloorReq(runtime)

      assertEquals(listOf(request), runtime.reqs)
    }
  }

  @Test
  fun `movement advances partially without changing the current floor`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      val initialFloor = if (direction == JinBoLiftStatus.Up) 1 else 3
      val runtime = newRuntime(initialFloor = initialFloor, liftSpeed = 2.0)
      val initialHeight = runtime.h
      runtime.liftStatus = direction

      JinBoMoveService.advance(runtime, stopHeight = 3.0)

      val expectedHeight = if (direction == JinBoLiftStatus.Up) initialHeight + 0.2 else initialHeight - 0.2
      assertEquals(expectedHeight, runtime.h, 1e-9)
      assertEquals(initialFloor, runtime.curFloor)
      assertNotNull(runtime.lastMoveOn)
    }
  }

  @Test
  fun `movement stops at the requested height or uses the full frame distance`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      for (stopAtMiddleFloor in listOf(false, true)) {
        val initialFloor = if (direction == JinBoLiftStatus.Up) 1 else 3
        val runtime = newRuntime(initialFloor = initialFloor, liftSpeed = 45.0)
        runtime.liftStatus = direction
        JinBoMoveService.advance(runtime, stopHeight = if (stopAtMiddleFloor) 3.0 else null)

        val expectedHeight = when {
          stopAtMiddleFloor -> 3.0
          direction == JinBoLiftStatus.Up -> 4.5
          else -> 1.5
        }
        assertEquals(expectedHeight, runtime.h, 1e-9)
        assertEquals(initialFloor, runtime.curFloor)
      }
    }
  }

  @Test
  fun `movement stops at the current floor without moving away`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      val runtime = newRuntime(initialFloor = 2)
      runtime.liftStatus = direction

      JinBoMoveService.advance(runtime, stopHeight = 3.0)

      assertEquals(3.0, runtime.h)
      assertEquals(2, runtime.curFloor)
      assertEquals(direction, runtime.liftStatus)
      assertEquals(JinBoDoorStatus.CLOSE, runtime.doors.single().status)
    }
  }

  @Test
  fun `movement requires a moving status and every door to be closed`() {
    for (doorStatus in JinBoDoorStatus.entries) {
      val runtime = newRuntime(liftSpeed = 2.0)
      runtime.doors.single().setStatus(doorStatus, Date())

      JinBoMoveService.advance(runtime, stopHeight = 3.0)
      assertEquals(0.0, runtime.h)
      assertNull(runtime.lastMoveOn)

      runtime.liftStatus = JinBoLiftStatus.Up
      JinBoMoveService.advance(runtime, stopHeight = 3.0)

      assertEquals(if (doorStatus == JinBoDoorStatus.CLOSE) 0.2 else 0.0, runtime.h, 1e-9)
      if (doorStatus != JinBoDoorStatus.CLOSE) assertNull(runtime.lastMoveOn)
    }
  }

  @Test
  fun `movement keeps the current floor unchanged and never passes the building boundary`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      for (distance in listOf(3.0 - JinBoConstants.FLOOR_EPSILON / 2, 3.0, 100.0)) {
        val initialFloor = if (direction == JinBoLiftStatus.Up) 1 else 3
        val runtime = newRuntime(initialFloor = initialFloor, liftSpeed = distance / JinBoConstants.DEFAULT_MOVE_SECONDS)
        runtime.liftStatus = direction

        JinBoMoveService.advance(runtime, stopHeight = null)

        val expectedHeight = if (direction == JinBoLiftStatus.Up) minOf(distance, 6.0) else maxOf(6.0 - distance, 0.0)
        assertEquals(expectedHeight, runtime.h, 1e-9)
        assertEquals(initialFloor, runtime.curFloor)
      }
    }
  }

  @Test
  fun `process lift synchronizes passed floors and snaps height within tolerance`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      for (distance in listOf(3.0 - JinBoConstants.FLOOR_EPSILON / 2, 3.0, 4.5, 100.0)) {
        val initialFloor = if (direction == JinBoLiftStatus.Up) 1 else 3
        val fartherFloor = if (direction == JinBoLiftStatus.Up) 3 else 1
        val runtime = newRuntime(initialFloor = initialFloor, liftSpeed = distance / JinBoConstants.DEFAULT_MOVE_SECONDS)
        runtime.liftStatus = direction
        runtime.direction = direction
        runtime.reqs.add(JinBoReq(fartherFloor))

        JinBoServer.processLift(runtime)

        val expectedFloor = when {
          distance < 6.0 -> 2
          direction == JinBoLiftStatus.Up -> 3
          else -> 1
        }
        val expectedHeight = when {
          distance <= 3.0 -> 3.0
          direction == JinBoLiftStatus.Up -> minOf(distance, 6.0)
          else -> maxOf(6.0 - distance, 0.0)
        }
        assertEquals(expectedHeight, runtime.h, 1e-9)
        assertEquals(expectedFloor, runtime.curFloor)
      }
    }
  }

  @Test
  fun `door advances through open close states using operation timings`() {
    val door = JinBoDoor(0)
    val startedAt = Date(0)
    val config = JinBoConfig(
      id = "test-door",
      port = 0,
      floors = listOf(JinBoFloor(1, "1F", 0.0)),
      liftSpeed = 2.0,
      openCost = 100,
      closeCost = 100,
      closeDelay = 200,
    )
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
        assertEquals("0", JinBoCommandService.pressButton(LIFT_ID, req).code)
        assertTrue(runtime.reqs.any { it.destFloor == 2 })

        val invalidReq = JinBoReq(3, JinBoReqSource.InDoor, doorCodes = listOf(9))
        assertEquals("400", JinBoCommandService.pressButton(LIFT_ID, invalidReq).code)
        assertTrue(runtime.reqs.none { it == invalidReq })
      } finally {
      }
    }
  }

  @Test
  fun `replay blocks page requests but keeps tcp and http requests alive`() {
    val runtime = newRuntime(initialFloor = 3)

    withLift(runtime) {
      try {
        JinBoEventBus.setReplayMode(LIFT_ID, true)

        assertEquals("409", JinBoCommandService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor)).code)
        assertEquals("409", JinBoCommandService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down)).code)
        assertEquals("409", JinBoCommandService.pressOpen(LIFT_ID).code)
        assertEquals("409", JinBoCommandService.pressClose(LIFT_ID, fromTcp = true).code)
        assertTrue(runtime.reqs.isEmpty())

        assertEquals("0", JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp)).code)
        assertTrue(runtime.reqs.any { it.destFloor == 2 })
      } finally {
        JinBoEventBus.setReplayMode(LIFT_ID, false)
      }
    }
  }

  @Test
  fun `request queue supplies the button highlight`() {
    val runtime = newRuntime(initialFloor = 3)

    withLift(runtime) {
      try {
        JinBoCommandService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor))
        val returnedReq = runtime.reqs.single()
        assertEquals(listOf(0), returnedReq.doorCodes)

        runtime.reqs.clear()
        JinBoStateService.publish(LIFT_ID, JinBoSnapshot.capture(runtime))
        assertTrue(runtime.reqs.isEmpty())
      } finally {
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
        JinBoCommandService.pressButton(LIFT_ID, JinBoReq(2, JinBoReqSource.InDoor))
        runtime.reqs.clear()
        JinBoStateService.publish(LIFT_ID, JinBoSnapshot.capture(runtime))

        val insideEvents = events.filter { it.topic == "inside" }
        assertTrue(insideEvents.dropLast(1).all { it.msg != "[]" })
        assertEquals("[]", insideEvents.last().msg)
      } finally {
        JinBoEventBus.removeListener(LIFT_ID, listener)
      }
    }
  }

  @Test
  fun `arrives and opens all requested doors before clearing request`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1)

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.processLift(runtime)
      assertEquals(1, runtime.curFloor)
      assertTrue(runtime.isMoving())
      JinBoServer.processLift(runtime)
      assertEquals(2, runtime.curFloor)
      assertEquals(listOf(OpenTracker.OPENING, OpenTracker.OPENING), runtime.doorStates())
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())
      assertTrue(runtime.reqs.isEmpty())
    }
  }

  @Test
  fun `does not clear a same floor request before its door reaches open`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
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
      JinBoCommandService.request(LIFT_ID, request)
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      val firstOpenedAt = runtime.doors.map { assertNotNull(it.openOn).time }

      Thread.sleep(2)
      JinBoCommandService.request(LIFT_ID, request)

      assertTrue(runtime.doors.all { it.status == JinBoDoorStatus.OPEN })
      assertTrue(runtime.doors.zip(firstOpenedAt).all { (door, openedAt) -> door.openOn!!.time > openedAt })
    }
  }

  @Test
  fun `keeps doors open after a different floor request until a close command`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1)

    withLift(runtime) {
      JinBoCommandService.open(LIFT_ID, listOf(0, 1))
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())

      JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
      JinBoServer.processLift(runtime)

      assertEquals(1, runtime.curFloor)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())
      assertFalse(runtime.isMoving())

      JinBoCommandService.close(LIFT_ID)
      JinBoServer.processLift(runtime)

      assertTrue(runtime.doors.all { it.status != JinBoDoorStatus.OPEN })
      JinBoServer.processLift(runtime)
      assertEquals(2, runtime.curFloor)
    }
  }

  @Test
  fun `same floor request reopens its doors while auto close delay is still active`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 1, closeCost = 1_000)
    val request = JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(0, 1))

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, request)
      JinBoServer.processLift(runtime)
      runtime.doors.forEach { it.setStatus(JinBoDoorStatus.CLOSING, Date()) }

      JinBoCommandService.request(LIFT_ID, JinBoReq(1, JinBoReqSource.Tcp, doorCodes = listOf(1)))

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
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      runtime.lastMoveOn = System.currentTimeMillis() - 10_000
      JinBoServer.processLift(runtime)
      assertTrue(runtime.isMoving())
      runtime.lastMoveOn = null
      JinBoServer.processLift(runtime)
      assertTrue(runtime.isMoving())
      assertTrue(runtime.h > 0.0)

      JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)

      assertTrue(runtime.isMoving())
      assertTrue(runtime.requestedFloors().contains(2))
    }
  }

  @Test
  fun `process lift opens at the selected stop for exact arrival and overshoot in either direction`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      for (distance in listOf(3.0, 4.5, 10.0)) {
        val initialFloor = if (direction == JinBoLiftStatus.Up) 1 else 3
        val fartherFloor = if (direction == JinBoLiftStatus.Up) 3 else 1
        val runtime = newRuntime(initialFloor = initialFloor, liftSpeed = distance / JinBoConstants.DEFAULT_MOVE_SECONDS)
        runtime.liftStatus = direction
        runtime.direction = direction
        runtime.reqs.add(JinBoReq(fartherFloor))
        runtime.reqs.add(JinBoReq(2, JinBoReqSource.OutDoor, direction))

        JinBoServer.processLift(runtime)

        assertEquals(3.0, runtime.h)
        assertEquals(2, runtime.curFloor)
        assertEquals(JinBoLiftStatus.Idle, runtime.liftStatus)
        assertEquals(direction, runtime.direction)
        assertNull(runtime.lastMoveOn)
        assertEquals(JinBoDoorStatus.OPENING, runtime.doors.single().status)
        assertEquals(setOf(2, fartherFloor), runtime.requestedFloors())
      }
    }
  }

  @Test
  fun `process lift recalculates its stop when a nearer request arrives during travel`() {
    val runtime = newRuntime(liftSpeed = 2.0)
    runtime.liftStatus = JinBoLiftStatus.Up
    runtime.direction = JinBoLiftStatus.Up
    runtime.reqs.add(JinBoReq(3))
    JinBoServer.processLift(runtime)
    assertEquals(0.2, runtime.h, 1e-9)

    runtime.reqs.add(JinBoReq(2))
    runtime.config.liftSpeed = 100.0
    runtime.lastMoveOn = null
    JinBoServer.processLift(runtime)

    assertEquals(3.0, runtime.h)
    assertEquals(2, runtime.curFloor)
    assertEquals(JinBoDoorStatus.OPENING, runtime.doors.single().status)
    assertEquals(setOf(2, 3), runtime.requestedFloors())
  }

  @Test
  fun `process lift serves an aligned current floor call before moving farther`() {
    for (direction in listOf(JinBoLiftStatus.Up, JinBoLiftStatus.Down)) {
      val runtime = newRuntime(initialFloor = 2)
      val fartherFloor = if (direction == JinBoLiftStatus.Up) 3 else 1
      runtime.liftStatus = direction
      runtime.direction = direction
      runtime.reqs.add(JinBoReq(fartherFloor))
      runtime.reqs.add(JinBoReq(2, JinBoReqSource.OutDoor, direction))

      JinBoServer.processLift(runtime)

      assertEquals(3.0, runtime.h)
      assertEquals(JinBoLiftStatus.Idle, runtime.liftStatus)
      assertEquals(direction, runtime.direction)
      assertEquals(JinBoDoorStatus.OPENING, runtime.doors.single().status)
      assertEquals(setOf(2, fartherFloor), runtime.requestedFloors())
    }
  }

  @Test
  fun `process lift leaves opposite direction calls pending at a shared stop`() {
    val runtime = newRuntime().let { base ->
      JinBoRuntime(base.config.copy(floors = base.config.floors + JinBoFloor(4, "4F", 9.0))).apply {
        setInitialFloor()
      }
    }
    runtime.liftStatus = JinBoLiftStatus.Up
    runtime.direction = JinBoLiftStatus.Up
    runtime.reqs.add(JinBoReq(4))
    val upCall = JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Up)
    val downCall = JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down)
    runtime.reqs.add(upCall)
    runtime.reqs.add(downCall)

    JinBoServer.processLift(runtime)
    JinBoServer.processLift(runtime)

    assertEquals(3.0, runtime.h)
    assertEquals(JinBoDoorStatus.OPEN, runtime.doors.single().status)
    assertEquals(JinBoLiftStatus.Up, runtime.direction)
    assertFalse(runtime.reqs.contains(upCall))
    assertTrue(runtime.reqs.contains(downCall))
    assertTrue(runtime.reqs.any { it.destFloor == 4 })
  }

  @Test
  fun `does not stop for a nearer request in the opposite travel direction`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)

      assertEquals(3, runtime.curFloor)
      assertEquals(setOf(2), runtime.requestedFloors())
    }
  }

  @Test
  fun `out goto opposite to an upward pass stays pending at the requested floor`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(2, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoServer.processLift(runtime)
      JinBoServer.processLift(runtime)

      assertEquals(3, runtime.curFloor)
      assertEquals(setOf(2), runtime.requestedFloors())

      JinBoCommandService.close(LIFT_ID)
      repeat(4) { JinBoServer.processLift(runtime) }

      assertEquals(2, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertTrue(runtime.reqs.none { it.destFloor == 2 && it.type == JinBoLiftStatus.Down })
    }
  }

  @Test
  fun `out goto opposite the current travel direction is left pending at a non endpoint floor`() {
    val runtime = newRuntime(initialFloor = 1)

    withLift(runtime) {
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)
      JinBoCommandService.request(
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

      val currentFloorRequest = JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      JinBoCommandService.request(
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

      JinBoCommandService.close(LIFT_ID)
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
      JinBoCommandService.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      repeat(3) { JinBoServer.processLift(runtime) }
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)

      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      assertEquals(JinBoLiftStatus.Idle, runtime.direction)
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
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      repeat(3) { JinBoServer.processLift(runtime) }
      assertEquals(3, runtime.curFloor)
      JinBoCommandService.request(
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
      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )

      repeat(3) { JinBoServer.processLift(runtime) }
      assertEquals(3, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(setOf(1), runtime.requestedFloors())

      JinBoCommandService.close(LIFT_ID)
      repeat(3) { JinBoServer.processLift(runtime) }
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
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      repeat(2) { JinBoServer.processLift(runtime) }
      assertTrue(runtime.h > 3.0 && runtime.h < 6.0)

      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(3, JinBoReqSource.OutDoor, JinBoLiftStatus.Down, listOf(0)),
      )
      JinBoCommandService.request(
        LIFT_ID,
        JinBoReq(1, JinBoReqSource.OutDoor, JinBoLiftStatus.Up, listOf(0)),
      )
      assertTrue(runtime.isMoving())
      runtime.lastMoveOn = System.currentTimeMillis() - 1_000
      repeat(2) { JinBoServer.processLift(runtime) }
      assertEquals(3, runtime.curFloor)
      assertEquals(JinBoDoorStatus.OPEN, runtime.doors[0].status)
      assertEquals(setOf(1), runtime.requestedFloors())

      JinBoCommandService.close(LIFT_ID)
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
      JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoCommandService.request(LIFT_ID, JinBoReq(1, JinBoReqSource.Tcp))
      runtime.direction = JinBoLiftStatus.Down
      repeat(2) { JinBoServer.processLift(runtime) }

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
      val response = JinBoCommandService.status(LIFT_ID)

      assertEquals(listOf(JinBoDoorResp(0, 1), JinBoDoorResp(1, 4)), response.doors)
      assertEquals(JinBoDoorStatus.OPEN, response.doorStatus)
    }
  }

  @Test
  fun `reset clears calls and their highlights`() {
    val runtime = newRuntime(doorCount = 2, initialFloor = 2)

    withLift(runtime) {
      try {
        JinBoCommandService.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp, doorCodes = listOf(0, 1)))
        JinBoCommandService.pressButton(LIFT_ID, JinBoReq(3, JinBoReqSource.InDoor))
        assertTrue(runtime.reqs.isNotEmpty())

        assertEquals("0", JinBoCommandService.resetRequests(LIFT_ID).code)

        assertTrue(runtime.reqs.isEmpty())
      } finally {
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
      JinBoCommandService.open(LIFT_ID, listOf(0))
      JinBoServer.processLift(runtime)
      val openedAt = runtime.doors[0].openOn

      JinBoCommandService.emergencyStop(LIFT_ID)
      Thread.sleep(2)
      JinBoServer.processLift(runtime)
      JinBoCommandService.releaseEmergencyStop(LIFT_ID)

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
