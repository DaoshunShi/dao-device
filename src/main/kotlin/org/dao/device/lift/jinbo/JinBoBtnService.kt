package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯呼梯按钮：发送请求，并记录按钮对应的请求状态供界面亮灭显示。 */
object JinBoBtnService {
  /** 接收呼梯请求；界面按钮点击由 [fromPanel] 标识。 */
  fun press(liftId: String, req: JinBoReq, fromPanel: Boolean = false): JinBoResp {
    if (fromPanel) {
      logButtonPress(
        liftId,
        req.source.panelName,
        buildString {
          append("楼层=${req.destFloor}, 按钮=楼层")
          req.type?.let { append(", 方向=${if (it == JinBoLiftStatus.Up) "上行" else "下行"}") }
        },
      )
    }
    val response = JinBoServer.request(liftId, req)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
    }
    return response
  }

  fun pressInDoor(liftId: String, floor: Int): JinBoResp =
    press(liftId, JinBoReq(floor, JinBoReqSource.InDoor), fromPanel = true)

  fun pressOutDoor(liftId: String, floor: Int, direction: JinBoLiftStatus): JinBoResp =
    press(liftId, JinBoReq(floor, JinBoReqSource.OutDoor, direction), fromPanel = true)

  fun pressOpen(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: return JinBoResp("404", "$liftId 电梯不存在")
    val floor = lift.curFloor
    logButtonPress(liftId, "LiftInsidePanel", "楼层=$floor, 按钮=开门")
    val response = JinBoServer.request(liftId, JinBoReq(floor, JinBoReqSource.InDoor))
    if (response.code == "0") JinBoLogService.logEvent(liftId, LiftEvent("in-open", "destFloor=$floor"))
    return response
  }

  /** 关门不区分门号；仅界面点击记录为按钮按下。 */
  fun pressClose(liftId: String, fromTcp: Boolean = false): JinBoResp {
    if (!fromTcp) {
      val lift = JinBoServer.lifts[liftId] ?: return JinBoResp("404", "$liftId 电梯不存在")
      logButtonPress(liftId, "LiftInsidePanel", "楼层=${lift.curFloor}, 按钮=关门")
    }
    val response = JinBoServer.close(liftId)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent("关闭", if (fromTcp) "TCP栏关门按钮" else "关门按钮"))
    }
    return response
  }

  private fun logButtonPress(liftId: String, panel: String, button: String) {
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("按钮按下", "面板=$panel, 电梯=$liftId, $button"),
    )
  }

  private val JinBoReqSource.panelName: String
    get() = when (this) {
      JinBoReqSource.OutDoor -> "LiftOutsidePanel"
      JinBoReqSource.InDoor -> "LiftInsidePanel"
      JinBoReqSource.Tcp -> "LiftTcpPanel"
    }

  /** 按仿真 tick 的请求快照刷新按钮；回放模式由回放器直接推送对应的请求事件。 */
  fun refresh(liftId: String, reqs: List<JinBoReq>) {
    JinBoServer.lifts[liftId] ?: return
    val snapshot = BtnSnapshot(
      outside = reqs.filter { it.source == JinBoReqSource.OutDoor },
      inside = reqs.filter { it.source == JinBoReqSource.InDoor },
      tcp = reqs.filter { it.source == JinBoReqSource.Tcp },
    )
    JinBoEventBus.fireRealtime(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(snapshot.outside)))
    JinBoEventBus.fireRealtime(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(snapshot.inside)))
    JinBoEventBus.fireRealtime(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(snapshot.tcp)))
  }

  fun fireRequests(liftId: String, outside: List<JinBoReq>, inside: List<JinBoReq>, tcp: List<JinBoReq>) {
    JinBoEventBus.fireReplay(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(outside)))
    JinBoEventBus.fireReplay(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(inside)))
    JinBoEventBus.fireReplay(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(tcp)))
  }

  private data class BtnSnapshot(val outside: List<JinBoReq>, val inside: List<JinBoReq>, val tcp: List<JinBoReq>)
}
