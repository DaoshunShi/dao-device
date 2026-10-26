package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 电梯按钮与请求队列：按钮按下、按钮高亮和请求入队分别记录。 */
object JinBoBtnService {
  private val pressedButtons: MutableMap<String, MutableList<JinBoButton>> = ConcurrentHashMap()
  private val requestEntries: MutableMap<String, List<JinBoRequestEntry>> = ConcurrentHashMap()
  private val queuedAt: MutableMap<String, MutableMap<JinBoReq, Long>> = ConcurrentHashMap()

  /** TCP/HTTP 请求没有实体按钮，只记录请求队列。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp = request(liftId, req)

  /** 面板按钮先高亮，再提交请求；提交失败时撤销高亮。 */
  fun pressButton(liftId: String, req: JinBoReq): JinBoResp {
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
    // 点击反馈不能被后续请求锁或 Swing 重绘阻塞。
    recordButton(liftId, button, asynchronous = true)

    val response = request(liftId, req)
    if (response.code != "0") recordButton(liftId, button.copy(active = false))
    return response
  }

  fun pressInDoor(liftId: String, floor: Int): JinBoResp =
    pressButton(liftId, JinBoReq(floor, JinBoReqSource.InDoor))

  fun pressOutDoor(liftId: String, floor: Int, direction: JinBoLiftStatus): JinBoResp =
    pressButton(liftId, JinBoReq(floor, JinBoReqSource.OutDoor, direction))

  fun pressOpen(liftId: String): JinBoResp = pressControl(liftId, "LiftInsidePanel", "开门") {
    val lift = JinBoServer.lifts[liftId] ?: return@pressControl JinBoResp("404", "$liftId 电梯不存在")
    JinBoServer.open(liftId, emptyList()).also {
      if (it.code == "0") JinBoLogService.logEvent(liftId, LiftEvent("in-open", "destFloor=${lift.curFloor}"))
    }
  }

  /** 关门不区分门号；[fromTcp] 仅用于日志中的按钮位置。 */
  fun pressClose(liftId: String, fromTcp: Boolean = false): JinBoResp {
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
    val button = JinBoButton(panel, floor, null, action, System.currentTimeMillis())
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("按钮按下", "电梯=$liftId, 面板=$panel, 楼层=$floor, 按钮=$action"),
    )
    recordButton(liftId, button, asynchronous = true)
    val response = command()
    if (response.code != "0") recordButton(liftId, button.copy(active = false))
    return response
  }

  private fun request(liftId: String, req: JinBoReq): JinBoResp {
    val response = JinBoServer.request(liftId, req)
    if (response.code == "0") {
      JinBoLogService.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
      refreshRequests(liftId)
    }
    return response
  }

  /** 实时运行时刷新按钮快照与请求队列。 */
  fun refresh(liftId: String, reqs: List<JinBoReq>, buttons: List<JinBoButton>) {
    if (JinBoServer.lifts[liftId] == null) return
    updateRequests(liftId, reqs)
    fireButtons(liftId, buttons, realtime = true)
  }

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
    fireRequests(liftId, entries, realtime = true)
  }

  /** 楼层按钮随请求入队亮起，随请求消费熄灭；开关门按钮按操作状态处理。 */
  private fun JinBoButton.isNoLongerActive(reqs: List<JinBoReq>, doors: List<JinBoDoor>): Boolean = when (action) {
    "呼梯" -> reqs.none { req ->
      req.destFloor == floor && req.type == direction && req.source == panel.source
    }
    "开门" -> doors.none { it.status == JinBoDoorStatus.OPENING }
    else -> doors.any { it.status == JinBoDoorStatus.CLOSING || it.status == JinBoDoorStatus.CLOSE }
  }

  private val String.source: JinBoReqSource?
    get() = JinBoReqSource.entries.firstOrNull { it.panelName == this }

  private val JinBoReqSource.panelName: String
    get() = when (this) {
      JinBoReqSource.OutDoor -> "LiftOutsidePanel"
      JinBoReqSource.InDoor -> "LiftInsidePanel"
      JinBoReqSource.Tcp -> "LiftTcpPanel"
    }

  private fun recordButton(liftId: String, button: JinBoButton, asynchronous: Boolean = false) {
    val list = pressedButtons.getOrPut(liftId) { CopyOnWriteArrayList() }
    val index = list.indexOfFirst {
      it.panel == button.panel && it.floor == button.floor && it.direction == button.direction && it.action == button.action
    }
    if (index < 0) list.add(button) else list[index] = button
    fireButtons(liftId, list.toList(), realtime = true, asynchronous = asynchronous)
  }

  fun fireFrame(liftId: String, frame: JinBoFrame) {
    fireButtons(liftId, frame.buttons, realtime = false)
    fireRequests(liftId, frame.reqs, realtime = false)
  }

  private fun fireButtons(liftId: String, buttons: List<JinBoButton>, realtime: Boolean, asynchronous: Boolean = false) {
    val active = buttons.filter { it.active }
    val outside = active.filter { it.panel == "LiftOutsidePanel" }.map { it.toReq() }
    val inside = active.filter { it.panel == "LiftInsidePanel" && it.action == "呼梯" }.map { it.toReq() }
    val tcp = active.filter { it.panel == "LiftTcpPanel" }.map { it.toReq() }
    val fire = when {
      asynchronous -> JinBoEventBus::fireReplayAsync
      realtime -> JinBoEventBus::fireRealtime
      else -> JinBoEventBus::fireReplay
    }
    fire(liftId, LiftEvent("outside", JsonHelper.mapper.writeValueAsString(outside)))
    fire(liftId, LiftEvent("inside", JsonHelper.mapper.writeValueAsString(inside)))
    fire(liftId, LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(tcp)))
  }

  private fun fireRequests(liftId: String, entries: List<JinBoRequestEntry>, realtime: Boolean) {
    val fire = if (realtime) JinBoEventBus::fireRealtime else JinBoEventBus::fireReplay
    fire(liftId, LiftEvent("reqs", JsonHelper.mapper.writeValueAsString(entries)))
  }

  private fun JinBoButton.toReq(): JinBoReq = JinBoReq(
    destFloor = floor ?: 0,
    source = panel.source ?: JinBoReqSource.Tcp,
    type = direction,
  )

  private fun directionText(direction: JinBoLiftStatus?): String = when (direction) {
    JinBoLiftStatus.Up -> ", 方向=上行"
    JinBoLiftStatus.Down -> ", 方向=下行"
    else -> ""
  }

  fun buttons(liftId: String): List<JinBoButton> = pressedButtons[liftId]?.toList().orEmpty()

  fun requests(liftId: String): List<JinBoRequestEntry> = requestEntries[liftId].orEmpty()

  fun reset(liftId: String) {
    pressedButtons.remove(liftId)
    requestEntries.remove(liftId)
    queuedAt.remove(liftId)
  }

  /** 清空呼梯请求及其按钮高亮，保留开门、关门等控制操作记录。 */
  fun clearRequests(liftId: String) {
    val pressed = pressedButtons[liftId]
    if (pressed != null) {
      pressed.removeIf { it.action == "呼梯" }
      fireButtons(liftId, pressed.toList(), realtime = true)
    }
    requestEntries.remove(liftId)
    queuedAt.remove(liftId)
    fireRequests(liftId, emptyList(), realtime = true)
  }

  /** 按请求队列和门状态更新按钮快照；只在状态实际变化时重绘。 */
  fun release(liftId: String, reqs: List<JinBoReq>, doors: List<JinBoDoor>) {
    val pressed = pressedButtons[liftId] ?: return
    var changed = false
    pressed.indices.forEach { index ->
      val button = pressed[index]
      if (button.active && button.isNoLongerActive(reqs, doors)) {
        pressed[index] = button.copy(active = false)
        changed = true
      }
    }
    if (changed) fireButtons(liftId, pressed.toList(), realtime = true)
  }
}
