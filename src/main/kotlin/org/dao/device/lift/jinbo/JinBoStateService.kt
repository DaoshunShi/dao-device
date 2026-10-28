package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯位置和门状态的图形刷新事件；状态数据来自实时运行时或回放帧。 */
object JinBoStateService {
  /** 由仿真线程在 tick 内发布；状态事件用于界面更新，不读取运行中的可变对象。 */
  fun publish(lift: JinBoRuntime) {
    val reqs = lift.reqs.toList()
    val snapshot = LiftState.capture(lift)
    fireState(
      liftId = lift.config.id,
      h = snapshot.h,
      liftStatus = snapshot.liftStatus,
      direction = snapshot.direction,
      doors = snapshot.doors,
      emergencyStopped = snapshot.emergencyStopped,
      realtime = true,
    )
    publishRequests(lift.config.id, reqs)
  }

  fun fireState(liftId: String, frame: JinBoFrame) {
    fireState(
      liftId = liftId,
      h = frame.h,
      liftStatus = frame.liftStatus,
      direction = frame.direction,
      doors = frame.doors.ifEmpty { listOf(JinBoDoor(code = 0, status = frame.doorStatus)) },
      emergencyStopped = frame.emergencyStopped,
      realtime = false,
    )
  }

  /** 发布实时请求队列和按钮高亮事件。 */
  fun publishRequests(liftId: String, reqs: List<JinBoReq>) {
    val entries = reqs.map { JinBoRequestEntry(it) }
    fire(liftId, buttons = entries, reqs = entries, realtime = true)
  }

  /** 发布回放帧中的按钮高亮和请求队列事件。 */
  fun fireFrame(liftId: String, frame: JinBoFrame) {
    fire(liftId, buttons = frame.buttons, reqs = frame.reqs, realtime = false)
  }

  private fun fire(liftId: String, buttons: List<JinBoRequestEntry>, reqs: List<JinBoRequestEntry>, realtime: Boolean) {
    val active = buttons.filter { it.active }
    val outside = active.filter { it.panel == "LiftOutsidePanel" }.map { it.req }
    val inside = active.filter { it.panel == "LiftInsidePanel" && it.action == "呼梯" }.map { it.req }
    val tcp = active.filter { it.panel == "LiftTcpPanel" }.map { it.req }
    val fire = if (realtime) JinBoEventBus::fireRealtime else JinBoEventBus::fire
    fire(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(outside)))
    fire(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(inside)))
    fire(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(tcp)))
    fire(liftId, LiftEvent("reqs", JsonHelper.mapper.writeValueAsString(reqs)))
  }

  private fun fireState(
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
    if (realtime) JinBoEventBus.fireRealtime(liftId, event) else JinBoEventBus.fire(liftId, event)
  }

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
