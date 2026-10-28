package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
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
    JinBoLogService.logEvent(liftId, LiftEvent("request-start", "hash=${req.hashCode()}, req=$reqJson"))
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
      JinBoLogService.logEvent(liftId, LiftEvent("request-failed", "hash=${req.hashCode()}, req=$reqJson, error=${e.message}"))
      throw e
    }
    JinBoLogService.logEvent(
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
      JinBoLogService.logEvent(liftId, LiftEvent("急停", "$liftId 已进入急停状态"))
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
      JinBoLogService.logEvent(liftId, LiftEvent("解除急停", "$liftId 已解除急停状态"))
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
    JinBoLogService.logEvent(liftId, LiftEvent("重置", "$liftId 已清空所有呼梯请求"))
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
}
