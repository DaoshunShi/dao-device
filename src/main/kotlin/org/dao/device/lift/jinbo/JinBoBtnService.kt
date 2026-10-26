package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯呼梯按钮：发送请求，并记录按钮对应的请求状态供界面亮灭显示。 */
object JinBoBtnService {
  /** 接收 HTTP/TCP 呼梯请求并记录日志。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp {
    val response = JinBoServer.request(liftId, req)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
    }
    return response
  }

  fun pressInDoor(liftId: String, floor: Int): JinBoResp =
    press(liftId, JinBoReq(floor, JinBoReqSource.InDoor))

  fun pressOutDoor(liftId: String, floor: Int, direction: JinBoLiftStatus): JinBoResp =
    press(liftId, JinBoReq(floor, JinBoReqSource.OutDoor, direction))

  fun pressOpen(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: return JinBoResp("404", "$liftId 电梯不存在")
    val floor = lift.curFloor
    val response = JinBoServer.request(liftId, JinBoReq(floor, JinBoReqSource.InDoor))
    if (response.code == "0") JinBoLogService.logEvent(liftId, LiftEvent("in-open", "destFloor=$floor"))
    return response
  }

  /** 关门不区分门号；按钮位置仅写入日志消息。 */
  fun pressClose(liftId: String, fromTcp: Boolean = false): JinBoResp {
    val response = JinBoServer.close(liftId)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent("关闭", if (fromTcp) "TCP栏关门按钮" else "关门按钮"))
    }
    return response
  }

  /** 按实时请求快照刷新按钮；回放模式由回放器直接推送对应的请求事件。 */
  fun refresh(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    val snapshot = synchronized(lift) {
      BtnSnapshot(
        outside = lift.reqs.filter { it.source == JinBoReqSource.OutDoor },
        inside = lift.reqs.filter { it.source == JinBoReqSource.InDoor },
        tcp = lift.reqs.filter { it.source == JinBoReqSource.Tcp },
      )
    }
    fireRequests(liftId, snapshot)
  }

  fun fireRequests(liftId: String, outside: List<JinBoReq>, inside: List<JinBoReq>, tcp: List<JinBoReq>) {
    fireRequests(liftId, BtnSnapshot(outside, inside, tcp))
  }

  private fun fireRequests(liftId: String, snapshot: BtnSnapshot) {
    JinBoEventBus.fire(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(snapshot.outside)))
    JinBoEventBus.fire(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(snapshot.inside)))
    JinBoEventBus.fire(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(snapshot.tcp)))
  }

  private data class BtnSnapshot(
    val outside: List<JinBoReq>,
    val inside: List<JinBoReq>,
    val tcp: List<JinBoReq>,
  )
}
