package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.fe.LiftEvent
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

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

  private const val MAX_STEP_SECONDS = 1.0 // 单次位移上限，避免线程暂停后跳层
  private const val DEFAULT_MOVE_SECONDS = 0.1 // 没有上一帧时间时，按主循环周期估算

  fun init() {
    logger.info("JinBoLiftService init")

    // 加载 lifts
    lifts.putAll(JinBoRepo.load())
    lifts.values.map { it.init() }

    worker = executor.submit { process() }
  }

  fun dispose() {
    lifts.values.map { it.dispose() }
    worker?.cancel(true)
  }

  fun disposeLift(liftId: String) {
    lifts.remove(liftId)?.dispose()
  }

  /**
   * 仅用于更新
   */
  fun updateConfig(cfg: JinBoConfig) {
    lifts[cfg.id]?.let { lift ->
      synchronized(lift) {
        lift.updateCfg(cfg)
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
      Thread.sleep(100)
    }
  }

  /**
   * 处理电梯
   */
  fun processLift(lr: JinBoRuntime) {
    synchronized(lr) {
      // 按高度和已启用楼层定位当前层，避免外部刚更新 curFloor/h 时仍用旧楼层做到站判定。
      syncCurrentFloor(lr)
      if (lr.emergencyStopped) {
        // 急停期间也录制一帧，保证时间轴连续
        JinBoRecorder.capture(lr)
        return
      }

      // 先完成已到期的开门/关门操作，再判断是否开始移动
      updateDoorState(lr)
      // 只有已经停在当前楼层且目标门打开，才说明本次呼梯完成。
      // 刚到站时门仍为 OPENING，路过或正在离开时也不能清理请求。
      if (!lr.lifting) cleanOpenedFloorReq(lr)

      // 门流程结束前不选择下一站，保证到站后完整等待自动关门延迟。
      if (lr.targetFloor == null && !lr.holdAtCurrentFloor()) selectBetterReq(lr)

      // 运行中收到当前方向上更近的停靠请求时，不能等到原目标楼层；
      // 在越过新目标楼层前切换目标，使其正常停靠、开门并清理该请求。
      retargetNearerFloor(lr)

      // 跨层移动必须先关闭全部门；门关闭后本 tick 才开始移动
      if (lr.targetFloor != null && lr.targetFloor != lr.curFloor) {
        closeDoors(lr)
        if (lr.canLifting()) {
          lr.lifting = true
          liftStep(lr)
        }
      }

      afterArrive(lr)

      // 录制本 tick 的状态快照
      JinBoRecorder.capture(lr)
    }
  }

  /** 用实际高度校正当前楼层，防止到站后仍以移动前楼层处理请求。 */
  private fun syncCurrentFloor(lr: JinBoRuntime) {
    val floor = lr.config.floors
      .filter { !it.disabled }
      .minByOrNull { abs(it.height - lr.h) }
    if (floor != null && abs(floor.height - lr.h) < 1e-6) lr.curFloor = floor.index
  }

  /** 按每扇门的状态刷新其关闭过程；门已关闭时无需处理。 */
  private fun updateClosingDoors(lr: JinBoRuntime, now: Date) {
    lr.doors.filter { it.status == JinBoDoorStatus.CLOSING }.forEach { door ->
      val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
      if (now.time - startedAt.time >= lr.config.closeCost) {
        door.setStatus(JinBoDoorStatus.CLOSE, now)
      }
    }
  }

  /** 当前楼层指定门已经打开时，才视为完成对应的呼梯请求；梯外呼梯还要匹配运行方向。 */
  private fun cleanOpenedFloorReq(lr: JinBoRuntime) {
    lr.reqs.removeIf { req ->
      req.destFloor == lr.curFloor &&
        lr.targetFloor == null &&
        matchesTravelDirection(lr, req) &&
        req.doorCodes.ifEmpty { listOf(0) }.all { code ->
          lr.doors.firstOrNull { it.code == code }?.status == JinBoDoorStatus.OPEN
        }
    }
  }

  /** 梯外呼梯仅在电梯本次运行方向上有效；未指定方向的旧请求保持兼容。 */
  private fun matchesTravelDirection(lr: JinBoRuntime, req: JinBoReq): Boolean =
    arrivesFromDirection(lr, req, lr.travelDirection)

  private fun isTopFloor(lr: JinBoRuntime, floorIndex: Int): Boolean {
    val floors = lr.config.floors.filter { !it.disabled }
    val floor = floors.firstOrNull { it.index == floorIndex } ?: return false
    return floors.all { it.height <= floor.height }
  }

  private fun isBottomFloor(lr: JinBoRuntime, floorIndex: Int): Boolean {
    val floors = lr.config.floors.filter { !it.disabled }
    val floor = floors.firstOrNull { it.index == floorIndex } ?: return false
    return floors.all { it.height >= floor.height }
  }

  /** 梯外呼梯在目标楼层所在端点反向时，仍由到站方向判定能否服务。 */
  private fun arrivesFromDirection(
    lr: JinBoRuntime,
    req: JinBoReq,
    arrivalDirection: JinBoLiftStatus,
  ): Boolean {
    if (req.source != JinBoReqSource.OutDoor || req.type == null) return true
    return when (req.type) {
      JinBoLiftStatus.Down -> arrivalDirection != JinBoLiftStatus.Up || isTopFloor(lr, req.destFloor)
      JinBoLiftStatus.Up -> arrivalDirection != JinBoLiftStatus.Down || isBottomFloor(lr, req.destFloor)
      JinBoLiftStatus.Idle -> true
    }
  }

  private fun retargetNearerFloor(lr: JinBoRuntime) {
    val targetFloor = lr.targetFloor ?: return
    if (!lr.lifting && lr.liftStatus != JinBoLiftStatus.Idle) return
    val current = lr.curFloor
    val next = when (lr.liftStatus) {
      JinBoLiftStatus.Up -> lr.reqs
        .filter {
          it.destFloor > current && it.destFloor < targetFloor &&
            matchesTravelDirection(lr, it)
        }
        .minOfOrNull { it.destFloor }
      JinBoLiftStatus.Down -> lr.reqs
        .filter {
          it.destFloor < current && it.destFloor > targetFloor &&
            matchesTravelDirection(lr, it)
        }
        .maxOfOrNull { it.destFloor }
      JinBoLiftStatus.Idle -> null
    } ?: return
    lr.targetFloor = next
  }

  /**
   * 选择更优的呼叫电梯请求
   */
  private fun selectBetterReq(lr: JinBoRuntime) {
    val direction = if (lr.liftStatus == JinBoLiftStatus.Idle) lr.travelDirection else lr.liftStatus
    val sameDirectionFloor = when (direction) {
      JinBoLiftStatus.Up -> lr.reqs
        .filter { it.destFloor > lr.curFloor && matchesTravelDirection(lr, it) }
        .minOfOrNull { it.destFloor }

      JinBoLiftStatus.Down -> lr.reqs
        .filter { it.destFloor < lr.curFloor && matchesTravelDirection(lr, it) }
        .maxOfOrNull { it.destFloor }

      JinBoLiftStatus.Idle -> lr.reqs
        .filter { it.destFloor != lr.curFloor }
        .minByOrNull { abs(it.destFloor - lr.curFloor) }
        ?.destFloor
    }

    // 当前方向已无后续请求时才反向；同层反方向呼梯不能成为目标。
    val oppositeDirectionFloor = lr.reqs
      .filter {
        it.destFloor != lr.curFloor &&
          matchesRequestDirection(it, oppositeDirectionOf(direction))
      }
      .minByOrNull { abs(it.destFloor - lr.curFloor) }
      ?.destFloor
    // 同层同方向梯外呼梯需要停靠开门；选中的同层目标不能丢掉当前方向。
    // 空闲电梯停在同层时没有既定运行方向，任一端点方向呼梯都应开门服务。
    val sameFloorOutDoor = lr.reqs.any { req ->
      req.destFloor == lr.curFloor &&
        (direction == JinBoLiftStatus.Idle || matchesRequestDirection(req, direction))
    }
    lr.targetFloor = sameDirectionFloor
      ?: if (sameFloorOutDoor) lr.curFloor else oppositeDirectionFloor
    if (lr.targetFloor != null) {
      val floor = lr.targetFloor ?: error("目标楼层不应为空")
      lr.liftStatus = when {
        floor == lr.curFloor && sameFloorOutDoor && direction != JinBoLiftStatus.Idle -> direction
        floor > lr.curFloor -> JinBoLiftStatus.Up
        floor < lr.curFloor -> JinBoLiftStatus.Down
        // 空闲时的同层呼梯先开门；方向只影响请求是否仍有效，不用于移动。
        else -> lr.reqs.firstOrNull { it.destFloor == lr.curFloor }?.type ?: JinBoLiftStatus.Idle
      }
      lr.travelDirection = lr.liftStatus
      // 同层请求也要先到达并开门，不能因为无需移动而跳过 afterArrive。
      lr.lifting = false
    }

    if (lr.targetFloor == null) {
      lr.liftStatus = JinBoLiftStatus.Idle
      lr.travelDirection = JinBoLiftStatus.Idle
      lr.lifting = false
    }
  }

  private fun oppositeDirectionOf(direction: JinBoLiftStatus): JinBoLiftStatus = when (direction) {
    JinBoLiftStatus.Up -> JinBoLiftStatus.Down
    JinBoLiftStatus.Down -> JinBoLiftStatus.Up
    JinBoLiftStatus.Idle -> JinBoLiftStatus.Idle
  }

  /** out-goto 的方向与电梯接下来计划运行的方向一致时，才能作为停靠目标。 */
  private fun matchesRequestDirection(req: JinBoReq, direction: JinBoLiftStatus): Boolean =
    req.source != JinBoReqSource.OutDoor ||
      req.type == null ||
      req.type == direction

  /**
   * 电梯移动
   */
  private fun liftStep(lr: JinBoRuntime) {
    if (!lr.lifting || !lr.canLifting()) return

    val tf = lr.config.floors.firstOrNull { it.index == lr.targetFloor } ?: return // 找不到，就不升降
    val now = System.currentTimeMillis()
    val elapsedSeconds = lr.lastMoveOn?.let { (now - it) / 1000.0 } ?: DEFAULT_MOVE_SECONDS
    lr.lastMoveOn = now
    val stepH = lr.config.liftSpeed * elapsedSeconds.coerceIn(0.0, MAX_STEP_SECONDS)

    when (lr.liftStatus) {
      JinBoLiftStatus.Up -> {
        if (lr.h + stepH >= tf.height) {
          lr.h = tf.height
        } else {
          lr.h += stepH
        }
        lr.config.floors.lastOrNull { it.height <= lr.h }?.index?.let { lr.curFloor = it }
      }

      JinBoLiftStatus.Down -> {
        if (lr.h - stepH <= tf.height) {
          lr.h = tf.height
        } else {
          lr.h -= stepH
        }
        lr.config.floors.firstOrNull { it.height >= lr.h }?.index?.let { lr.curFloor = it }
      }

      else -> {
        lr.curFloor = lr.config.floors.minBy { abs(it.height - lr.h) }.index
      }
    }
  }

  /**
   * 电梯到达目标楼层后的处理
   */
  private fun afterArrive(lr: JinBoRuntime) {
    if (lr.curFloor == lr.targetFloor) {
      val doorCodes = lr.reqs
        .filter { it.destFloor == lr.curFloor && matchesTravelDirection(lr, it) }
        .flatMap { it.doorCodes }
        .ifEmpty { listOf(0) }
      lr.lifting = false
      lr.targetFloor = null
      lr.lastMoveOn = null
      val now = Date()
      openDoors(lr, doorCodes, now)
    }
  }

  /**
   * 打开指定门，并从到站时刻开始计算自动关门延迟。
   * 指定门正在开启时不中断；已经开启时从新的开门时刻重新计算倒计时。
   */
  private fun openDoors(lr: JinBoRuntime, doorCodes: List<Int>, openedAt: Date = Date()): String? {
    val targets = doorCodes.distinct().ifEmpty { listOf(0) }.mapNotNull { code ->
      lr.doors.firstOrNull { it.code == code }
        ?: return "门号 $code 不存在"
    }
    if (targets.isEmpty()) return "电梯没有可打开的门"
    targets.forEach { door ->
      door.openOn = openedAt
      if (door.status != JinBoDoorStatus.OPENING) {
        door.setStatus(JinBoDoorStatus.OPENING, openedAt)
      }
    }
    return null
  }

  /** 关闭全部门；电梯等所有门关闭后才开始移动。 */
  private fun closeDoors(lr: JinBoRuntime): String? {
    val now = Date()
    lr.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.CLOSE, JinBoDoorStatus.CLOSING -> Unit
        JinBoDoorStatus.OPENING, JinBoDoorStatus.OPEN -> door.setStatus(JinBoDoorStatus.CLOSING, now)
        JinBoDoorStatus.ERROR -> Unit
      }
    }
    return null
  }

  /** 更新门状态；门动作可能发生在跨层请求已选定、电梯尚未移动的关门过程中。 */
  private fun updateDoorState(lr: JinBoRuntime) {
    val now = Date()
    lr.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPENING -> {
          val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
          if (now.time - startedAt.time >= lr.config.openCost) {
            door.markOpen(now)
          }
        }

        JinBoDoorStatus.CLOSING -> Unit

        JinBoDoorStatus.OPEN -> {
          val openedAt = door.openOn ?: now.also { door.openOn = it }
          if (now.time - openedAt.time >= lr.config.closeDelay) {
            door.setStatus(JinBoDoorStatus.CLOSING, now)
          }
        }

        JinBoDoorStatus.CLOSE, JinBoDoorStatus.ERROR -> Unit
      }
    }
    updateClosingDoors(lr, now)
  }

  /** 同层呼梯重新打开对应门，并从当前时刻重新计算自动关门延迟。 */
  private fun refreshSameFloorTimer(lr: JinBoRuntime, doorCodes: List<Int>) {
    val requested = doorCodes.ifEmpty { lr.doors.take(1).map { it.code } }
    val now = Date()
    requested.mapNotNull { code -> lr.doors.firstOrNull { it.code == code } }.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPEN -> door.openOn = now
        JinBoDoorStatus.CLOSING -> {
          door.openOn = now
          door.setStatus(JinBoDoorStatus.OPENING, now)
        }
        else -> Unit
      }
    }
  }

  /** 未指定门号时使用默认 0 号门。 */
  private fun resolveDoorCodes(req: JinBoReq): JinBoReq =
    if (req.doorCodes.isEmpty()) req.copy(doorCodes = listOf(0)) else req

  /**
   * 请求电梯到指定楼层
   */
  fun request(liftId: String, req: JinBoReq, log: Boolean = true): JinBoResp {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      if (lift.emergencyStopped) {
        return JinBoResp("409", EMERGENCY_STOP_MSG)
      }
      val invalidCode = req.doorCodes.firstOrNull { code -> lift.doors.none { it.code == code } }
      if (invalidCode != null) {
        return JinBoResp("400", "${liftId} 电梯不存在门号 $invalidCode")
      }
      val resolvedReq = resolveDoorCodes(req)
      if (req.destFloor == lift.curFloor) {
        // 同层呼梯也先进入请求队列；只有目标门真正打开后才算完成并清理。
        if (lift.reqs.none { it == resolvedReq }) {
          lift.reqs.add(resolvedReq)
        }
        if (matchesTravelDirection(lift, resolvedReq)) {
          refreshSameFloorTimer(lift, resolvedReq.doorCodes)
        }
        return JinBoResp()
      }
      if (lift.reqs.none { it == resolvedReq }) {
        lift.reqs.add(resolvedReq)
      }
    }
    // 按来源统一打呼梯日志：梯外 out-goto / 梯内 in-goto / TCP tcp-goto
    // 开门按钮虽走 InDoor 请求，但需打 in-open，故由调用方传 log=false 自行打印
    if (log) {
      val prefix = when (req.source) {
        JinBoReqSource.OutDoor -> "out-goto"
        JinBoReqSource.InDoor -> "in-goto"
        JinBoReqSource.Tcp -> "tcp-goto"
      }
      logEvent(liftId, LiftEvent(prefix, JsonHelper.writeValueAsString(req)))
    }
    return JinBoResp()
  }

  /** 独立开门指令：打开指定门，空列表表示打开默认 0 号门。 */
  fun open(liftId: String, doorCodes: List<Int>): JinBoResp {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      if (lift.emergencyStopped) {
        return JinBoResp("409", EMERGENCY_STOP_MSG)
      }
      if (lift.isMoving()) {
        return JinBoResp("400", "电梯正在运行中，不能开门")
      }
      val error = openDoors(lift, doorCodes)
      if (error != null) {
        return JinBoResp("400", error)
      }
      logEvent(liftId, LiftEvent("open", JsonHelper.writeValueAsString(mapOf("doorCodes" to doorCodes))))
      return JinBoResp()
    }
  }

  /**
   * 请求电梯关门
   */
  fun close(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    synchronized(lr) {
      if (lr.emergencyStopped) {
        return JinBoResp("409", EMERGENCY_STOP_MSG)
      }
      val error = closeDoors(lr)
      if (error != null) return JinBoResp("400", error)
      lr.targetFloor = null
      logEvent(liftId, LiftEvent("close", "关闭全部门"))
      return JinBoResp()
    }
  }

  fun emergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    synchronized(lr) {
      if (lr.emergencyStopped) {
        return JinBoResp()
      }
      lr.emergencyStopped = true
      lr.emergencyStartedAt = Date()
      logEvent(liftId, LiftEvent("急停", "$liftId 已进入急停状态"))
    }
    return JinBoResp()
  }

  fun releaseEmergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    synchronized(lr) {
      if (!lr.emergencyStopped) {
        return JinBoResp()
      }

      val pausedAt = lr.emergencyStartedAt
      val now = Date()
      if (pausedAt != null) {
        val pausedDuration = now.time - pausedAt.time
        lr.doors.forEach { door ->
          door.openOn = door.openOn?.let { Date(it.time + pausedDuration) }
          door.opStartOn = door.opStartOn?.let { Date(it.time + pausedDuration) }
        }
      }

      lr.emergencyStopped = false
      lr.emergencyStartedAt = null
      lr.lastMoveOn = null
      logEvent(liftId, LiftEvent("解除急停", "$liftId 已解除急停状态"))
    }
    return JinBoResp()
  }

  fun toggleEmergencyStop(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    synchronized(lr) {
      return if (lr.emergencyStopped) {
        releaseEmergencyStop(liftId)
      } else {
        emergencyStop(liftId)
      }
    }
  }

  /**
   * 获取电梯信息
   */
  fun status(liftId: String): JinBoStatusResp {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      return JinBoStatusResp.of(lift)
    }
  }

  fun getLifts(): List<JinBoRuntime> = lifts.values.toList()

  private fun mustGetLift(liftId: String): JinBoRuntime = lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")

  /**
   * 在页面上打印日志
   */
  fun logEvent(liftId: String, e: LiftEvent) {
    JinBoRecorder.bufferLog(liftId, "${e.topic}:${e.msg}")
    // 无头运行（测试、服务端模式）不创建 Swing 窗口，日志仍由录制器保存。
    if (GraphicsEnvironment.isHeadless()) return
    lifts[liftId]?.frame?.logEvent(e)
  }
}
