package org.dao.device.lift.jinbo

import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JinBoServerTest {

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
      repeat(2) { JinBoServer.processLift(runtime) }

      assertEquals(1, runtime.curFloor)
      assertEquals(listOf(OpenTracker.OPEN, OpenTracker.OPEN), runtime.doorStates())
      assertFalse(runtime.lifting)

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
      assertEquals(3, runtime.targetFloor)
      assertTrue(runtime.lifting)
      runtime.lastMoveOn = null
      JinBoServer.processLift(runtime)
      assertTrue(runtime.lifting)
      assertEquals(3, runtime.targetFloor)
      assertTrue(runtime.h > 0.0)

      JinBoServer.request(LIFT_ID, JinBoReq(2, JinBoReqSource.Tcp))
      JinBoServer.processLift(runtime)

      assertEquals(2, runtime.targetFloor)
      assertTrue(runtime.lifting)
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
  fun `idle elevator chooses next request according to current direction`() {
    val runtime = newRuntime(initialFloor = 2)

    withLift(runtime) {
      JinBoServer.request(LIFT_ID, JinBoReq(3, JinBoReqSource.Tcp))
      JinBoServer.request(LIFT_ID, JinBoReq(1, JinBoReqSource.Tcp))
      runtime.travelDirection = JinBoLiftStatus.Down
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
      closeDelay = 60_000,
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
