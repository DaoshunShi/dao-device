package org.dao.device.lift.jinbo

import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 仿真金博电梯。
 *
 * 支持按高度推进电梯、多门开关门、TCP/HTTP 呼梯、回放记录与页面监控。
 */
object JinBoServer {
  private val logger = LoggerFactory.getLogger(javaClass)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var worker: Future<*>? = null

  val lifts: MutableMap<String, JinBoRuntime> = ConcurrentHashMap()

  fun init() {
    logger.info("JinBoLiftService init")

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

  private fun process() {
    while (!Thread.interrupted()) {
      for (lift in lifts.values) {
        if (lift.isSimulationIdle()) continue
        try {
          processLift(lift)
        } catch (e: Exception) {
          logger.error("processLift failed for ${lift.config.id}: ${e.message}", e)
        }
      }
      Thread.sleep(JinBoConstants.SIMULATION_INTERVAL_MS)
    }
  }

  /** 推进一次电梯仿真 tick。 */
  fun processLift(lr: JinBoRuntime) {
    val snapshot = withLiftLock(lr, "JinBoServer.processLift") {
      // 1. 先同步电梯当前楼层。
      lr.floorForOpening()?.let { lr.curFloor = it }

      // 2. 如果急停，则只生成快照，不推进门、方向、移动。
      if (lr.emc) return@withLiftLock JinBoSnapshot.capture(lr)

      // 3. 推进所有门的状态。
      val now = Date()
      lr.doors.forEach { it.advance(now, lr.config) }

      // 4. 如果电梯没有在移动，尝试清理当前楼层已经完成服务的请求。
      if (!lr.isMoving()) JinBoRequestSelector.cleanOpenedFloorReq(lr)

      // 5. 计算当前楼层可服务的请求。
      val currentReqs = JinBoRequestSelector.currentFloorReqs(lr)

      // 6. 判断电梯是否正在移动。
      if (lr.isMoving()) {
        if (!lr.allDoorsClosed()) {
          // 移动时：让所有门先关闭。
          lr.doors.forEach { it.startClosing(now) }
        } else {
          // 门全关后才能继续移动。
          lr.applyNextDirection(JinBoRequestSelector.selectNextDirection(lr))

          // - 当前高度已有可服务请求时，本帧停靠当前楼层高度；否则继续前进。
          val stopHeight = lr.config.floors.firstOrNull { it.index == currentReqs.firstOrNull()?.destFloor }?.height

          // - 推进一帧高度；
          JinBoMoveService.advance(lr, stopHeight)

          // - 同步经过楼层；
          // 按本帧结束高度一次性同步最后经过的楼层，容差内视为恰好到层。
          lr.refreshFloorByH()

          // - 到达可停楼层后，把 status 设为 Idle 并安排开门。
          val arrivalDoorCodes = currentReqs.flatMap { it.doorCodes.ifEmpty { listOf(0) } }
          if (arrivalDoorCodes.isNotEmpty()) {
            lr.liftStatus = JinBoLiftStatus.Idle
            lr.lastMoveOn = null
            JinBoDoorService.open(lr, arrivalDoorCodes)
          }
        }
      } else {
        // 静止时：
        if (currentReqs.isNotEmpty()) {
          // - 门未开且当前楼层有可服务请求：开门。
          JinBoDoorService.open(lr, currentReqs.flatMap { it.doorCodes.ifEmpty { listOf(0) } })
        } else if (lr.holdAtCurrentFloor()) {
          // - 当前楼层没有可服务请求，且门仍处于开门停留/开关门过程中：
          //   保持当前楼层，等待自动关门延迟结束后再决定下一方向。
        } else {
          // - 当前楼层没有可服务请求，且门已全部关闭：
          //   重新选择方向，更新 status/direction。
          lr.applyNextDirection(JinBoRequestSelector.selectNextDirection(lr))
        }
      }

      JinBoSnapshot.capture(lr)
    }

    JinBoStateService.publish(lr.config.id, snapshot)
    JinBoRecorder.capture(lr.config.id, snapshot)
  }

  internal inline fun <T> withLiftLock(lift: JinBoRuntime, remark: String, block: () -> T): T {
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
