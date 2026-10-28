package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯按钮与请求队列：呼梯请求入队后，按钮高亮直接从请求队列派生。 */
object JinBoBtnService {

  /** TCP/HTTP 和页面按钮共用的请求入队入口。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp {
    val response = JinBoServer.request(liftId, req)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
      refreshRequests(liftId)
    }
    return response
  }

  /** 面板按钮与 TCP/HTTP 共用同一入队路径。 */
  fun pressButton(liftId: String, req: JinBoReq): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面呼梯")
    logCallPress(liftId, req)
    return press(liftId, req)
  }

  private fun logCallPress(liftId: String, req: JinBoReq) {
    val direction = when (req.type) {
      JinBoLiftStatus.Up -> ", 方向=上行"
      JinBoLiftStatus.Down -> ", 方向=下行"
      else -> ""
    }
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("按钮按下", "电梯=$liftId, 面板=${req.source.panelName}, 楼层=${req.destFloor}$direction"),
    )
  }

  fun pressOpen(liftId: String): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面开门")
    return pressControl(liftId, "LiftInsidePanel", "开门") {
      val lift = JinBoServer.lifts[liftId] ?: return@pressControl JinBoResp("404", "$liftId 电梯不存在")
      JinBoServer.open(liftId, emptyList()).also {
        if (it.code == "0") JinBoLogService.logEvent(liftId, LiftEvent("in-open", "destFloor=${lift.curFloor}"))
      }
    }
  }

  /** 关门不区分门号；[fromTcp] 仅用于日志中的按钮位置。 */
  fun pressClose(liftId: String, fromTcp: Boolean = false): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面关门")
    val panel = if (fromTcp) "LiftTcpPanel" else "LiftInsidePanel"
    return pressControl(liftId, panel, "关门") {
      JinBoServer.close(liftId).also {
        if (it.code == "0") {
          JinBoLogService.logEvent(liftId, LiftEvent("关闭", if (fromTcp) "TCP栏关门按钮" else "关门按钮"))
        }
      }
    }
  }

  private fun pressControl(liftId: String, panel: String, action: String, command: () -> JinBoResp): JinBoResp {
    val floor = JinBoServer.lifts[liftId]?.curFloor
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("按钮按下", "电梯=$liftId, 面板=$panel, 楼层=$floor, 按钮=$action"),
    )
    return command()
  }

  /** 请求入队后立即刷新 reqs，不等待下一次仿真 tick。 */
  private fun refreshRequests(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    // 请求队列使用 CopyOnWriteArrayList，直接读取时无需等待 processLift 的电梯锁。
    JinBoStateService.publishRequests(liftId, lift.reqs.toList())
  }
}
