package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import java.util.Date

/** 电梯统一命令入口：HTTP、TCP、页面按钮都通过这里调用业务命令。 */
object JinBoCommandService {
  fun updateConfig(cfg: JinBoConfig) {
    val lift = JinBoServer.lifts[cfg.id] ?: return
    JinBoServer.withLiftLock(lift, "JinBoCommandService.updateConfig") {
      lift.updateConfig(cfg)
    }
  }

  fun request(liftId: String, req: JinBoReq): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    val reqJson = JsonHelper.writeValueAsString(req)
    JinBoRecorder.logEvent(liftId, LiftEvent("request-start", "hash=${req.hashCode()}, req=$reqJson"))
    val response = try {
      JinBoServer.withLiftLock(
        lift,
        "JinBoCommandService.request, hash=${req.hashCode()}, destFloor=${req.destFloor}, source=${req.source}",
      ) {
        if (lift.emc) {
          JinBoResp("409", "电梯处于急停状态")
        } else {
          val invalidCode = req.doorCodes.firstOrNull { code -> lift.doors.none { it.code == code } }
          if (invalidCode != null) {
            JinBoResp("400", "$liftId 电梯不存在门号 $invalidCode")
          } else {
            val resolvedReq = JinBoDoorService.resolveDoorCodes(req)
            if (lift.reqs.none { it == resolvedReq }) {
              lift.reqs.add(resolvedReq)
            }
            if (req.destFloor == lift.curFloor && JinBoDirection.arrivalMatches(lift.config, resolvedReq, lift.direction)) {
              JinBoDoorService.refreshSameFloorTimer(lift, resolvedReq.doorCodes)
            }
            JinBoResp()
          }
        }
      }
    } catch (e: Exception) {
      JinBoRecorder.logEvent(liftId, LiftEvent("request-failed", "hash=${req.hashCode()}, req=$reqJson, error=${e.message}"))
      throw e
    }
    JinBoRecorder.logEvent(
      liftId,
      LiftEvent("request-complete", "hash=${req.hashCode()}, req=$reqJson, resp=${JsonHelper.writeValueAsString(response)}"),
    )
    return response
  }

  fun open(liftId: String, doorCodes: List<Int>): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.open, doorCodes=$doorCodes") {
      if (lift.emc) return JinBoResp("409", "电梯处于急停状态")
      if (lift.isMoving()) return JinBoResp("400", "电梯正在运行中，不能开门")
      val error = JinBoDoorService.open(lift, doorCodes)
      if (error != null) return JinBoResp("400", error)
      return JinBoResp()
    }
  }

  fun close(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.close") {
      if (lift.emc) return JinBoResp("409", "电梯处于急停状态")
      JinBoDoorService.close(lift)
      lift.targetFloor = null
      return JinBoResp()
    }
  }

  fun emergencyStop(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.emergencyStop") {
      if (lift.emc) return JinBoResp()
      lift.emc = true
      lift.emcStartedOn = Date()
      JinBoRecorder.logEvent(liftId, LiftEvent("急停", "$liftId 已进入急停状态"))
    }
    return JinBoResp()
  }

  fun releaseEmergencyStop(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.releaseEmergencyStop") {
      if (!lift.emc) return JinBoResp()
      val pausedAt = lift.emcStartedOn
      val now = Date()
      if (pausedAt != null) {
        JinBoDoorService.shiftOperationTimers(lift, now.time - pausedAt.time)
      }
      lift.emc = false
      lift.emcStartedOn = null
      lift.lastMoveOn = null
      JinBoRecorder.logEvent(liftId, LiftEvent("解除急停", "$liftId 已解除急停状态"))
    }
    return JinBoResp()
  }

  fun toggleEmergencyStop(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.toggleEmergencyStop") {
      return if (lift.emc) releaseEmergencyStop(liftId) else emergencyStop(liftId)
    }
  }

  fun resetRequests(liftId: String): JinBoResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    JinBoServer.withLiftLock(lift, "JinBoCommandService.resetRequests") {
      lift.reqs.clear()
    }
    JinBoStateService.publishRequests(liftId, emptyList())
    JinBoRecorder.logEvent(liftId, LiftEvent("重置", "$liftId 已清空所有呼梯请求"))
    return JinBoResp()
  }

  fun status(liftId: String): JinBoStatusResp {
    val lift = JinBoServer.lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
    return JinBoServer.withLiftLock(lift, "JinBoCommandService.status") {
      JinBoStatusResp(
        currentFloor = lift.curFloor.toString(),
        doorStatus = lift.doors.firstOrNull { it.status != JinBoDoorStatus.CLOSE }?.status ?: JinBoDoorStatus.CLOSE,
        emergencyStopped = lift.emc,
        doors = lift.doors.map { JinBoDoorResp(it.code, it.status.openStatus) },
        destFloor = lift.targetFloor,
        disabled = lift.config.disabled,
        fault = lift.emc,
      )
    }
  }

  /** TCP/HTTP 请求：直接入队并立即刷新请求显示。 */
  fun press(liftId: String, req: JinBoReq): JinBoResp {
    val response = request(liftId, req)
    if (response.code == "0") {
      JinBoRecorder.logEvent(liftId, LiftEvent(req.source.name, JsonHelper.writeValueAsString(req)))
      refreshRequests(liftId)
    }
    return response
  }

  /** 页面按钮呼梯入口：回放模式下禁止页面呼梯。 */
  fun pressButton(liftId: String, req: JinBoReq): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面呼梯")
    logCallPress(liftId, req)
    return press(liftId, req)
  }

  fun pressOpen(liftId: String): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面开门")
    return pressControl(liftId, "LiftInsidePanel", "开门") {
      val lift = JinBoServer.lifts[liftId] ?: return@pressControl JinBoResp("404", "$liftId 电梯不存在")
      open(liftId, emptyList()).also {
        if (it.code == "0") JinBoRecorder.logEvent(liftId, LiftEvent("in-open", "destFloor=${lift.curFloor}"))
      }
    }
  }

  fun pressClose(liftId: String, fromTcp: Boolean = false): JinBoResp {
    if (JinBoEventBus.isReplayMode(liftId)) return JinBoResp("409", "回放状态下禁止页面关门")
    val panel = if (fromTcp) "LiftTcpPanel" else "LiftInsidePanel"
    return pressControl(liftId, panel, "关门") {
      close(liftId).also {
        if (it.code == "0") {
          JinBoRecorder.logEvent(liftId, LiftEvent("关闭", if (fromTcp) "TCP栏关门按钮" else "关门按钮"))
        }
      }
    }
  }

  private fun logCallPress(liftId: String, req: JinBoReq) {
    val direction = when (req.type) {
      JinBoLiftStatus.Up -> ", 方向=上行"
      JinBoLiftStatus.Down -> ", 方向=下行"
      else -> ""
    }
    JinBoRecorder.logEvent(liftId, LiftEvent("按钮按下", "电梯=$liftId, 面板=${req.source.panelName}, 楼层=${req.destFloor}$direction"))
  }

  private fun pressControl(liftId: String, panel: String, action: String, command: () -> JinBoResp): JinBoResp {
    val floor = JinBoServer.lifts[liftId]?.curFloor
    JinBoRecorder.logEvent(liftId, LiftEvent("按钮按下", "电梯=$liftId, 面板=$panel, 楼层=$floor, 按钮=$action"))
    return command()
  }

  private fun refreshRequests(liftId: String) {
    val lift = JinBoServer.lifts[liftId] ?: return
    JinBoStateService.publishRequests(liftId, lift.reqs.toList())
  }

}
