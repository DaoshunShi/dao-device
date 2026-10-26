package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯位置和门状态的图形刷新事件；状态数据来自实时运行时或回放帧。 */
object JinBoStateService {
  /** 由仿真线程在 tick 内发布；状态事件用于界面更新，不读取运行中的可变对象。 */
  fun publish(lift: JinBoRuntime) {
    // 回放期间实时仿真仍会录制，但不应覆盖回放画面。
    if (JinBoEventBus.isReplayMode(lift.config.id)) return
    val reqs = lift.reqs.toList()
    val snapshot = LiftState.capture(lift)
    fire(
      liftId = lift.config.id,
      h = snapshot.h,
      liftStatus = snapshot.liftStatus,
      direction = snapshot.direction,
      doors = snapshot.doors,
      emergencyStopped = snapshot.emergencyStopped,
    )
    JinBoBtnService.refresh(lift.config.id, reqs)
  }

  fun fireState(liftId: String, frame: JinBoFrame) {
    fire(
      liftId = liftId,
      h = frame.h,
      liftStatus = frame.liftStatus,
      direction = frame.direction,
      doors = frame.doors.ifEmpty { listOf(JinBoDoor(code = 0, status = frame.doorStatus)) },
      emergencyStopped = frame.emergencyStopped,
      realtime = false,
    )
  }

  internal fun fireRealtime(liftId: String, frame: JinBoFrame) {
    fire(
      liftId = liftId,
      h = frame.h,
      liftStatus = frame.liftStatus,
      direction = frame.direction,
      doors = frame.doors.ifEmpty { listOf(JinBoDoor(code = 0, status = frame.doorStatus)) },
      emergencyStopped = frame.emergencyStopped,
    )
  }

  private fun fire(
    liftId: String,
    h: Double,
    liftStatus: JinBoLiftStatus,
    direction: JinBoLiftStatus,
    doors: List<JinBoDoor>,
    emergencyStopped: Boolean,
    realtime: Boolean = true,
  ) {
    val event = LiftEvent(
      "liftState",
      JsonHelper.mapper.writeValueAsString(
        mapOf(
          "h" to h,
          "liftStatus" to liftStatus.name,
          "direction" to direction.name,
          "doorStatus" to aggregateDoorStatus(doors),
          "doors" to doors,
          "emergencyStopped" to emergencyStopped,
        ),
      ),
    )
    if (realtime) JinBoEventBus.fireRealtime(liftId, event) else JinBoEventBus.fireReplay(liftId, event)
  }

  private fun aggregateDoorStatus(doors: List<JinBoDoor>): JinBoDoorStatus =
    doors.minByOrNull { it.status.displayPriority }?.status ?: JinBoDoorStatus.CLOSE

  private data class LiftState(
    val h: Double,
    val liftStatus: JinBoLiftStatus,
    val direction: JinBoLiftStatus,
    val doors: List<JinBoDoor>,
    val emergencyStopped: Boolean,
  ) {
    companion object {
      fun capture(lift: JinBoRuntime): LiftState = LiftState(
        h = lift.h,
        liftStatus = lift.liftStatus,
        direction = lift.direction,
        doors = lift.doors.map { it.copy() },
        emergencyStopped = lift.emc,
      )
    }
  }
}
