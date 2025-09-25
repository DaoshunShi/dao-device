package org.dao.device.lift.jinbo.fe

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.JinBoServer

object JinBoFetcher {
  // 获取电梯运行动态
  fun fetch(liftId: String): String {
    val lr = JinBoServer.lifts[liftId] ?: return ""

    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "outside",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs.filter { it.source == JinBoReqSource.OutDoor },
        ),
      ),
    )
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "liftState",
        JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to lr.h / 12.0, // TODO 最高楼层的高度
            "status" to lr.doorStatus,
          ),
        ),
      ),
    )
    val l0 = lr.reqs.filter { it.source == JinBoReqSource.InDoor }
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "inside",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs.filter { it.source == JinBoReqSource.InDoor },
        ),
      ),
    )
    JinBoEventBus.fire(
      liftId,
      LiftEvent(
        "tcp",
        JsonHelper.mapper.writeValueAsString(
          lr.reqs.filter { it.source == JinBoReqSource.Tcp },
        ),
      ),
    )

    return JsonHelper.writeValueAsString(lr)
  }
}