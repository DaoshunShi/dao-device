package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus

/** 电梯按钮与请求队列：呼梯请求入队后，按钮高亮直接从请求队列派生。 */
object JinBoBtnService {

  /** TCP/HTTP 请求没有实体按钮，只记录请求队列。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp = request(liftId, req)

  /** 面板按钮与 TCP/HTTP 共用同一入队路径。 */
  fun pressButton(liftId: String, req: JinBoReq): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面呼梯")
    logCallPress(liftId, req)
    return request(liftId, req)
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

  private fun request(liftId: String, req: JinBoReq): JinBoResp {
    val response = JinBoServer.request(liftId, req)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
      refreshFromRuntime(liftId)
    }
    return response
  }

  /** 实时运行时刷新请求队列。 */
  fun refresh(liftId: String, reqs: List<JinBoReq>) {
    val entries = reqs.map { JinBoRequestEntry(it) }
    fire(liftId, buttons = entries, reqs = entries, realtime = true)
  }

  /** 请求入队后立即刷新 reqs，不等待下一次仿真 tick。 */
  private fun refreshFromRuntime(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    // 请求队列使用 CopyOnWriteArrayList，直接读取时无需等待 processLift 的电梯锁。
    refresh(liftId, lift.reqs.toList())
  }

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

  /** 清空呼梯请求及其按钮高亮。 */
  fun clearRequests(liftId: String) = refresh(liftId, emptyList())
}
