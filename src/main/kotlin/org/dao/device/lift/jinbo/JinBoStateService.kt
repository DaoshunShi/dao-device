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
        liftStatus = lift.liftStatus,
        doors = lift.doors.map { it.copy() },
        emergencyStopped = lift.emc,
      )
    }
    fire(
      liftId = liftId,
      h = snapshot.h,
      liftStatus = snapshot.liftStatus,
      doors = snapshot.doors,
      emergencyStopped = snapshot.emergencyStopped,
    )
  }

  fun fireState(liftId: String, frame: JinBoFrame) {
    fire(
      liftId = liftId,
      h = frame.h,
      liftStatus = frame.liftStatus,
      doors = frame.doors.ifEmpty { listOf(JinBoDoor(code = 0, status = frame.doorStatus)) },
      emergencyStopped = frame.emergencyStopped,
    )
  }

  private fun fire(
    liftId: String,
    h: Double,
    liftStatus: JinBoLiftStatus,
    doors: List<JinBoDoor>,
    emergencyStopped: Boolean,
  ) {
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "liftState",
        JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to h,
            "liftStatus" to liftStatus.name,
            "doorStatus" to aggregateDoorStatus(doors),
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
    val liftStatus: JinBoLiftStatus,
    val doors: List<JinBoDoor>,
    val emergencyStopped: Boolean,
  )
}
