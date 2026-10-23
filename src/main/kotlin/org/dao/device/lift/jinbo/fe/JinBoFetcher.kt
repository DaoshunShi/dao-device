package org.dao.device.lift.jinbo.fe

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoDoor
import org.dao.device.lift.jinbo.JinBoReq
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.JinBoRuntime
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.JinBoDoorStatus

object JinBoFetcher {
  // 获取电梯运行动态
  fun fetch(liftId: String): String {
    val lr = JinBoServer.lifts[liftId] ?: return ""
    // 在同一快照内读取状态和请求，避免 Swing 定时器看到门列表与聚合状态不一致。
    val snapshot = synchronized(lr) {
      FetchSnapshot(
        h = lr.h,
        doors = lr.doors.map { it.copy() },
        emergencyStopped = lr.emergencyStopped,
        outside = lr.reqs.filter { it.source == JinBoReqSource.OutDoor },
        inside = lr.reqs.filter { it.source == JinBoReqSource.InDoor },
        tcp = lr.reqs.filter { it.source == JinBoReqSource.Tcp },
        runtime = lr,
      )
    }

    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "outside",
        JsonHelper.mapper.writeValueAsString(
          snapshot.outside,
        ),
      ),
    )
    // 调试输出电梯高度
    // println("[JinBoFetcher DEBUG] Elevator ${liftId} height: ${snapshot.h} meters, doors: ${snapshot.doors}")
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "liftState",
        JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to snapshot.h, // 电梯当前实际高度（米）
            "status" to aggregateDoorStatus(snapshot.doors),
            "doors" to snapshot.doors,
            "emergencyStopped" to snapshot.emergencyStopped,
          ),
        ),
      ),
    )
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "inside",
        JsonHelper.mapper.writeValueAsString(
          snapshot.inside,
        ),
      ),
    )
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "tcp",
        JsonHelper.mapper.writeValueAsString(
          snapshot.tcp,
        ),
      ),
    )

    return JsonHelper.writeValueAsString(snapshot.runtime)
  }

  private fun aggregateDoorStatus(doors: List<JinBoDoor>): JinBoDoorStatus =
    doors.minByOrNull(::displayPriority)?.status ?: JinBoDoorStatus.CLOSE

  /** 多门状态不一致时，优先展示需要关注的异常、动作和开启状态。 */
  private fun displayPriority(door: JinBoDoor): Int = when (door.status) {
    JinBoDoorStatus.ERROR -> 0
    JinBoDoorStatus.OPENING, JinBoDoorStatus.CLOSING -> 1
    JinBoDoorStatus.OPEN -> 2
    JinBoDoorStatus.CLOSE -> 3
  }

  private data class FetchSnapshot(
    val h: Double,
    val doors: List<JinBoDoor>,
    val emergencyStopped: Boolean,
    val outside: List<JinBoReq>,
    val inside: List<JinBoReq>,
    val tcp: List<JinBoReq>,
    val runtime: JinBoRuntime,
  )
}
