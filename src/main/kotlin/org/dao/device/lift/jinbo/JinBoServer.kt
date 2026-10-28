package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 仿真金博电梯
 *
 * 暂定：只实现单部电梯的控制，不实现电梯组
 * 暂定：电梯移动的最小时间单位是秒
 * 判断电梯是否在某一层：curFloor = 1 && liftStatus !in [Up, Down]
 * 电梯到达下一层的条件：当前时间 - moveStartOn >= costPerFloor
 * TODO 基于电梯高度计算当前楼层
 *
 * 配置：
 * - 电梯 ID
 * - 总楼层，每层的 label (map)
 * - 上升/下降一层楼需要的时间
 * - 开门关门需要的时间
 * - 开门维持的时间
 * 运行状态
 * - 电梯目前的目标楼层
 *   - 外部按的 -> 不显示在电梯内
 *   - 内部按的 + tcp 请求的 -> 显示在电梯内
 * - 电梯到达顶层、底层后，清理所有目标楼层
 *
 */
object JinBoServer {
  private val logger = LoggerFactory.getLogger(javaClass)
  private const val EMERGENCY_STOP_MSG = "电梯处于急停状态"

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var worker: Future<*>? = null

  val lifts: MutableMap<String, JinBoRuntime> = ConcurrentHashMap()

  fun init() {
    logger.info("JinBoLiftService init")

    // 加载 lifts
    lifts.putAll(JinBoRepo.load())
    lifts.values.forEach { it.init() }

    worker = executor.submit { process() }
  }

  fun dispose() {
    lifts.values.forEach { it.dispose() }
    worker?.cancel(true)
    JinBoRecorder.dispose()
  }

  fun disposeLift(liftId: String) {
    lifts.remove(liftId)?.dispose()
  }

  /**
   * 仅用于更新
   */
  fun updateConfig(cfg: JinBoConfig) {
    lifts[cfg.id]?.let { lift ->
      withLiftLock(lift, "JinBoServer.updateConfig") {
        lift.updateConfig(cfg)
      }
    }
  }

  private fun process() {
    while (!Thread.interrupted()) {
      for (lift in lifts.values) {
        try {
          processLift(lift)
        } catch (e: Exception) {
          // 单部电梯处理异常不应终止整个仿真线程，否则所有电梯静默停摆
          logger.error("processLift failed for ${lift.config.id}: ${e.message}", e)
        }
      }
      Thread.sleep(JinBoConstants.SIMULATION_INTERVAL_MS)
    }
  }

  /**
   * 处理电梯
   */
  fun processLift(lr: JinBoRuntime) {
    withLiftLock(lr, "JinBoServer.processLift") {
      // 按高度和已启用楼层定位当前层，避免外部刚更新 curFloor/h 时仍用旧楼层做到站判定。
      JinBoMoveService.syncCurrentFloor(lr)
      if (lr.emc) {
        return
      }

      // 先完成已到期的开门/关门操作，再判断是否开始移动
      JinBoDoorService.update(lr)
      // 只有已经停在当前楼层且目标门打开，才说明本次呼梯完成。
      // 刚到站时门仍为 OPENING，路过或正在离开时也不能清理请求。
      if (!lr.isMoving()) JinBoMoveService.cleanOpenedFloorReq(lr)

      // 门流程结束前不选择下一站；门保持开启期间只调整方向，关门后按新方向出发。
      if (lr.targetFloor == null) {
        if (lr.doors.any { it.status == JinBoDoorStatus.OPEN }) {
          JinBoRequestSelector.selectDirectionDuringDoorHold(lr)
        } else if (!lr.holdAtCurrentFloor()) {
          JinBoRequestSelector.selectBetterReq(lr)
        }
      }

      // 运行中收到当前方向上更近的停靠请求时，不能等到原目标楼层；
      // 在越过新目标楼层前切换目标，使其正常停靠、开门并清理该请求。
      JinBoRequestSelector.retargetNearerFloor(lr)

      // 跨层移动必须先关闭全部门；门关闭后本 tick 才开始移动
      if (lr.targetFloor != null && lr.targetFloor != lr.curFloor) {
        JinBoDoorService.close(lr)
        if (lr.doors.all { it.status == JinBoDoorStatus.CLOSE }) {
          JinBoMoveService.advance(lr)
        }
      }

      JinBoMoveService.arrive(lr)
    }

    JinBoStateService.publish(lr)
    // 急停期间也录制一帧，保证时间轴连续
    JinBoLogService.captureFrame(lr)
  }

  /** 梯外呼梯仅在电梯本次运行方向上有效；未指定方向的旧请求保持兼容。 */
  private fun matchesTravelDirection(lr: JinBoRuntime, req: JinBoReq): Boolean =
    JinBoDirection.arrivalMatches(lr.config, req, lr.direction)

  /**
   * 请求电梯到指定楼层
   */
  fun request(liftId: String, req: JinBoReq): JinBoResp {
    val lift = mustGetLift(liftId)
    val reqJson = JsonHelper.writeValueAsString(req)
    JinBoLogService.logEvent(
      liftId,
      LiftEvent("request-start", "hash=${req.hashCode()}, req=$reqJson"),
    )
    val response = try {
      withLiftLock(
        lift,
        "JinBoServer.request, hash=${req.hashCode()}, destFloor=${req.destFloor}, source=${req.source}",
      ) {
        if (lift.emc) {
          JinBoResp("409", EMERGENCY_STOP_MSG)
        } else {
          val invalidCode = req.doorCodes.firstOrNull { code -> lift.doors.none { it.code == code } }
          if (invalidCode != null) {
            JinBoResp("400", "$liftId 电梯不存在门号 $invalidCode")
          } else {
            val resolvedReq = JinBoDoorService.resolveDoorCodes(req)
            if (lift.reqs.none { it == resolvedReq }) {
              lift.reqs.add(resolvedReq)
            }
            if (req.destFloor == lift.curFloor && matchesTravelDirection(lift, resolvedReq)) {
              // 同层呼梯也先进入请求队列；只有目标门真正打开后才算完成并清理。
              JinBoDoorService.refreshSameFloorTimer(lift, resolvedReq.doorCodes)
            }
            JinBoResp()
          }
        }
      }
    } catch (e: Exception) {
      JinBoLogService.logEvent(
        liftId,
        LiftEvent("request-failed", "hash=${req.hashCode()}, req=$reqJson, error=${e.message}"),
      )
      throw e
    }
    JinBoLogService.logEvent(
      liftId,
      LiftEvent(
        "request-complete",
        "hash=${req.hashCode()}, req=$reqJson, resp=${JsonHelper.writeValueAsString(response)}",
      ),
    )
    return response
  }

  /** 独立开门指令：打开指定门，空列表表示打开默认 0 号门。 */
  fun open(liftId: String, doorCodes: List<Int>): JinBoResp {
    val lift = mustGetLift(liftId)
    withLiftLock(lift, "JinBoServer.open, doorCodes=$doorCodes") {
      if (lift.emc) {
        return JinBoResp("409", EMERGENCY_STOP_MSG)
      }
      if (lift.isMoving()) {
        return JinBoResp("400", "电梯正在运行中，不能开门")
      }
      val error = JinBoDoorService.open(lift, doorCodes)
      if (error != null) return JinBoResp("400", error)
      return JinBoResp()
    }
  }

  /**
   * 请求电梯关门
   */
  fun close(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    withLiftLock(lr, "JinBoServer.close") {
      if (lr.emc) {
        return JinBoResp("409", EMERGENCY_STOP_MSG)
      }
      JinBoDoorService.close(lr)
      lr.targetFloor = null
      return JinBoResp()
    }
  }

  fun emergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    withLiftLock(lr, "JinBoServer.emergencyStop") {
      if (lr.emc) {
        return JinBoResp()
      }
      lr.emc = true
      lr.emcStartedOn = Date()
      JinBoLogService.logEvent(liftId, LiftEvent("急停", "$liftId 已进入急停状态"))
    }
    return JinBoResp()
  }

  fun releaseEmergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    withLiftLock(lr, "JinBoServer.releaseEmergencyStop") {
      if (!lr.emc) {
        return JinBoResp()
      }

      val pausedAt = lr.emcStartedOn
      val now = Date()
      if (pausedAt != null) {
        val pausedDuration = now.time - pausedAt.time
        JinBoDoorService.shiftOperationTimers(lr, pausedDuration)
      }

      lr.emc = false
      lr.emcStartedOn = null
      lr.lastMoveOn = null
      JinBoLogService.logEvent(liftId, LiftEvent("解除急停", "$liftId 已解除急停状态"))
    }
    return JinBoResp()
  }

  fun toggleEmergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    withLiftLock(lr, "JinBoServer.toggleEmergencyStop") {
      return if (lr.emc) releaseEmergencyStop(liftId) else emergencyStop(liftId)
    }
  }

  /** 清空所有呼梯请求；电梯仍会完成已开始的行程，避免停在楼层之间。 */
  fun resetRequests(liftId: String): JinBoResp {
    val lift = mustGetLift(liftId)
    withLiftLock(lift, "JinBoServer.resetRequests") {
      lift.reqs.clear()
    }
    JinBoBtnService.clearRequests(liftId)
    JinBoLogService.logEvent(liftId, LiftEvent("重置", "$liftId 已清空所有呼梯请求"))
    return JinBoResp()
  }

  /**
   * 获取电梯信息
   */
  fun status(liftId: String): JinBoStatusResp {
    val lift = mustGetLift(liftId)
    return withLiftLock(lift, "JinBoServer.status") {
      JinBoStatusResp(
        currentFloor = lift.curFloor.toString(),
        doorStatus = lift.doors.firstOrNull { it.status != JinBoDoorStatus.CLOSE }?.status
          ?: JinBoDoorStatus.CLOSE,
        emergencyStopped = lift.emc,
        doors = lift.doors.map { JinBoDoorResp(it.code, it.status.openStatus) },
        destFloor = lift.targetFloor,
        disabled = lift.config.disabled,
        fault = lift.emc,
      )
    }
  }

  private fun mustGetLift(liftId: String): JinBoRuntime = lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")

  private inline fun <T> withLiftLock(lift: JinBoRuntime, remark: String, block: () -> T): T {
    val startedAt = System.currentTimeMillis()
    return synchronized(lift) {
      block()
    }.also {
      logger.info(
        "JinBoServer lock lift=${lift.config.id}, remark=$remark, " +
          "curFloor=${lift.curFloor}, status=${lift.liftStatus}, direction=${lift.direction}, " +
          "elapsedMs=${System.currentTimeMillis() - startedAt}",
      )
    }
  }
}
