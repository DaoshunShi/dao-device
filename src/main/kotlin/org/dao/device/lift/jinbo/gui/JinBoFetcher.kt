package org.dao.device.lift.jinbo.gui

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.JinBoServer

object JinBoFetcher {
  // 获取电梯运行动态
  fun fetch(): String {
    val lr = JinBoServer.lifts["A"] ?: return ""

    JinBoEventBus.fire(
      LiftEvent(
        "outside",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs.filter { it.source == JinBoReqSource.OutDoor },
        ),
      ),
    )
    JinBoEventBus.fire(
      LiftEvent(
        "liftState",
        JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to lr.h / 12.0, // TODO 最高楼层的高度
            // "o" to (lr.doorStatus in listOf(JinBoDoorStatus.OPEN, JinBoDoorStatus.OPENING, JinBoDoorStatus.CLOSING)),
            "status" to lr.doorStatus,
          ),
        ),
      ),
    )
    val l0 = lr.reqs.filter { it.source == JinBoReqSource.InDoor }
    JinBoEventBus.fire(
      LiftEvent(
        "inside",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs.filter { it.source == JinBoReqSource.InDoor },
        ),
      ),
    )
    JinBoEventBus.fire(
      LiftEvent(
        "all",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs, // .filter { it.source == JinBoReqSource.InDoor },
        ),
      ),
    )

    return JsonHelper.writeValueAsString(lr)
  }
}