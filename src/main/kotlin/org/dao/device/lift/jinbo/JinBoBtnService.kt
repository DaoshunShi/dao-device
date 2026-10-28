package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import java.util.concurrent.ConcurrentHashMap

/** 电梯按钮与请求队列：呼梯请求入队后，按钮高亮直接从请求队列派生。 */
object JinBoBtnService {
  private val replayButtons: MutableMap<String, List<JinBoButton>> = ConcurrentHashMap()
  private val requestEntries: MutableMap<String, List<JinBoRequestEntry>> = ConcurrentHashMap()
  private val queuedAt: MutableMap<String, MutableMap<JinBoReq, Long>> = ConcurrentHashMap()

  /** TCP/HTTP 请求没有实体按钮，只记录请求队列。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp = request(liftId, req)

  /** 面板按钮与 TCP/HTTP 共用同一入队路径。 */
  fun pressButton(liftId: String, req: JinBoReq): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面呼梯")
    logCallPress(liftId, req)
    return request(liftId, req)
  }

  /** GUI 点击入口；请求直接入队，随后由请求队列刷新按钮高亮。 */
  fun pressButtonLater(liftId: String, req: JinBoReq) {
    pressButton(liftId, req)
  }

  private fun logCallPress(liftId: String, req: JinBoReq) {
    val button = JinBoButton(
      panel = req.source.panelName,
      floor = req.destFloor,
      direction = req.type,
      action = "呼梯",
      pressedAt = System.currentTimeMillis(),
    )
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("按钮按下", "电梯=$liftId, 面板=${button.panel}, 楼层=${button.floor}${directionText(button.direction)}"),
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
      refreshRequests(liftId)
    }
    return response
  }

  /** 实时运行时刷新请求队列。 */
  fun refresh(liftId: String, reqs: List<JinBoReq>) = updateRequests(liftId, reqs)

  /** 请求入队后立即刷新 reqs，不等待下一次仿真 tick。 */
  private fun refreshRequests(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    // 请求队列使用 CopyOnWriteArrayList，直接读取时无需等待 processLift 的电梯锁。
    val reqs = lift.reqs.toList()
    updateRequests(liftId, reqs)
  }

  private fun updateRequests(liftId: String, reqs: List<JinBoReq>) {
    val now = System.currentTimeMillis()
    val times = queuedAt.getOrPut(liftId) { ConcurrentHashMap() }
    val entries = reqs.map { req -> JinBoRequestEntry(req, times.getOrPut(req) { now }) }
    times.keys.retainAll(reqs.toSet())
    requestEntries[liftId] = entries
    fireButtons(liftId, entries.map { it.req.toButton(it.queuedAt) }, realtime = true)
    fireRequests(liftId, entries, realtime = true)
  }

  private val JinBoReqSource.panelName: String
    get() = when (this) {
      JinBoReqSource.OutDoor -> "LiftOutsidePanel"
      JinBoReqSource.InDoor -> "LiftInsidePanel"
      JinBoReqSource.Tcp -> "LiftTcpPanel"
    }

  fun fireFrame(liftId: String, frame: JinBoFrame) {
    replayButtons[liftId] = frame.buttons
    fireButtons(liftId, frame.buttons, realtime = false)
    fireRequests(liftId, frame.reqs, realtime = false)
  }

  private fun fireButtons(liftId: String, buttons: List<JinBoButton>, realtime: Boolean) {
    val active = buttons.filter { it.active }
    val outside = active.filter { it.panel == "LiftOutsidePanel" }.map { it.toReq() }
    val inside = active.filter { it.panel == "LiftInsidePanel" && it.action == "呼梯" }.map { it.toReq() }
    val tcp = active.filter { it.panel == "LiftTcpPanel" }.map { it.toReq() }
    val fire = if (realtime) JinBoEventBus::fireRealtime else JinBoEventBus::fire
    fire(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(outside)))
    fire(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(inside)))
    fire(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(tcp)))
  }

  private fun fireRequests(liftId: String, entries: List<JinBoRequestEntry>, realtime: Boolean) {
    val fire = if (realtime) JinBoEventBus::fireRealtime else JinBoEventBus::fire
    fire(liftId, LiftEvent("reqs", JsonHelper.mapper.writeValueAsString(entries)))
  }

  private fun JinBoButton.toReq(): JinBoReq = JinBoReq(
    destFloor = floor ?: 0,
    source = JinBoReqSource.entries.firstOrNull { it.panelName == panel } ?: JinBoReqSource.Tcp,
    type = direction,
  )

  private fun directionText(direction: JinBoLiftStatus?): String = when (direction) {
    JinBoLiftStatus.Up -> ", 方向=上行"
    JinBoLiftStatus.Down -> ", 方向=下行"
    else -> ""
  }

  /** 回放帧提供自己的按钮状态；实时运行时则从请求队列派生。 */
  fun buttons(liftId: String): List<JinBoButton> =
    if (JinBoEventBus.isReplayMode(liftId)) replayButtons[liftId].orEmpty()
    else requests(liftId).map { it.req.toButton(it.queuedAt) }

  private fun JinBoReq.toButton(pressedAt: Long): JinBoButton = JinBoButton(
    panel = source.panelName,
    floor = destFloor,
    direction = type,
    action = "呼梯",
    pressedAt = pressedAt,
  )

  /** 按钮绘制只读取当前服务状态，不依赖可能延迟到达的事件快照。 */
  fun isInsideActive(liftId: String, floor: Int): Boolean =
    buttons(liftId).any { it.panel == "LiftInsidePanel" && it.floor == floor && it.direction == null && it.action == "呼梯" && it.active }

  fun isTcpActive(liftId: String, floor: Int): Boolean =
    buttons(liftId).any { it.panel == "LiftTcpPanel" && it.floor == floor && it.action == "呼梯" && it.active }

  fun isOutsideActive(liftId: String, floor: Int, up: Boolean): Boolean {
    val direction = if (up) JinBoLiftStatus.Up else JinBoLiftStatus.Down
    return buttons(liftId).any {
      it.panel == "LiftOutsidePanel" && it.floor == floor && it.direction == direction && it.action == "呼梯" && it.active
    }
  }

  fun requests(liftId: String): List<JinBoRequestEntry> = requestEntries[liftId].orEmpty()

  fun reset(liftId: String) {
    replayButtons.remove(liftId)
    requestEntries.remove(liftId)
    queuedAt.remove(liftId)
  }

  /** 清空呼梯请求及其按钮高亮。 */
  fun clearRequests(liftId: String) {
    replayButtons.remove(liftId)
    requestEntries.remove(liftId)
    queuedAt.remove(liftId)
    fireButtons(liftId, emptyList(), realtime = true)
    fireRequests(liftId, emptyList(), realtime = true)
  }
}
