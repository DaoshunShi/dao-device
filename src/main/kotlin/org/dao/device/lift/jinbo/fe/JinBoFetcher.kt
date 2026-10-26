package org.dao.device.lift.jinbo.fe

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoRuntime
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.JinBoStateService
import org.dao.device.lift.jinbo.JinBoBtnService

object JinBoFetcher {
  /** 获取电梯运行动态并刷新位置、门和按钮显示。 */
  fun fetch(liftId: String): String {
    val lr = JinBoServer.lifts[liftId] ?: return ""
    JinBoStateService.fireLive(liftId)
    JinBoBtnService.refresh(liftId)
    return JsonHelper.writeValueAsString(lr)
  }
}
