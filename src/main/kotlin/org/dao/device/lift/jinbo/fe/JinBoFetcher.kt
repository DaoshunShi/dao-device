package org.dao.device.lift.jinbo.fe

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoServer

object JinBoFetcher {
  /** 获取电梯运行动态。位置、门和按钮由仿真 tick 发布的事件刷新。 */
  fun fetch(liftId: String): String {
    val lr = JinBoServer.lifts[liftId] ?: return ""
    return JsonHelper.writeValueAsString(lr)
  }
}
