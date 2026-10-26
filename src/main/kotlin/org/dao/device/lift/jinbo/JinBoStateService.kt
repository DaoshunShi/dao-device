package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯位置和门状态的图形刷新事件；状态数据来自实时运行时或回放帧。 */
object JinBoStateService {
  fun fireLive(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    val snapshot = synchronized(lift) {
      StateSnapshot(
        h = lift.h,
        doors = lift.doors.map { it.copy() },
        emergencyStopped = lift.emergencyStopped,
      )
    }
    fire(
      liftId = liftId,
      h = snapshot.h,
      doors = snapshot.doors,
      emergencyStopped = snapshot.emergencyStopped,
    )
  }

  fun fireState(liftId: String, frame: JinBoFrame) {
    fire(
      liftId = liftId,
      h = frame.h,
      doors = frame.doors.ifEmpty { listOf(JinBoDoor(code = 0, status = frame.doorStatus)) },
      emergencyStopped = frame.emergencyStopped,
    )
  }

  private fun fire(liftId: String, h: Double, doors: List<JinBoDoor>, emergencyStopped: Boolean) {
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "liftState",
        JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to h,
            "status" to aggregateDoorStatus(doors),
            "doors" to doors,
            "emergencyStopped" to emergencyStopped,
          ),
        ),
      ),
    )
  }

  private fun aggregateDoorStatus(doors: List<JinBoDoor>): JinBoDoorStatus =
    doors.minByOrNull(::displayPriority)?.status ?: JinBoDoorStatus.CLOSE

  private fun displayPriority(door: JinBoDoor): Int = when (door.status) {
    JinBoDoorStatus.ERROR -> 0
    JinBoDoorStatus.OPENING, JinBoDoorStatus.CLOSING -> 1
    JinBoDoorStatus.OPEN -> 2
    JinBoDoorStatus.CLOSE -> 3
  }

  private data class StateSnapshot(
    val h: Double,
    val doors: List<JinBoDoor>,
    val emergencyStopped: Boolean,
  )
}
