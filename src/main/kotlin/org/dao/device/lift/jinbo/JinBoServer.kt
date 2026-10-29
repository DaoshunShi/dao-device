package org.dao.device.lift.jinbo

import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

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
      lr.floorForOpening()?.let { lr.curFloor = it }
      if (lr.emc) return@withLiftLock JinBoSnapshot.capture(lr)

      val now = Date()
      lr.doors.forEach { it.advance(now, lr.config) }
      if (!lr.isMoving()) JinBoRequestSelector.cleanOpenedFloorReq(lr)

      if (!lr.isMoving()) {
        if (lr.hasOpenDoor()) {
          val nextDirection = JinBoRequestSelector.selectDirectionDuringDoorHold(lr)
          if (nextDirection != null) lr.direction = nextDirection
        } else if (!lr.holdAtCurrentFloor()) {
          val currentDoorCodes = JinBoRequestSelector.doorCodesForCurrentFloor(lr)
          if (currentDoorCodes.isNotEmpty()) {
            JinBoDoorService.open(lr, currentDoorCodes)
          } else {
            val nextDirection = JinBoRequestSelector.selectNextDirection(lr)
            lr.liftStatus = nextDirection
            lr.direction = nextDirection
          }
        }
      } else {
        lr.doors.forEach { it.startClosing(now) }
        if (lr.allDoorsClosed()) {
          // 当前高度已有可服务请求时，先按到达方向停靠，避免换向或继续移动后漏掉它。
          val nextDirection = if (JinBoRequestSelector.doorCodesForCurrentFloor(lr).isNotEmpty()) {
            lr.liftStatus
          } else {
            JinBoRequestSelector.selectNextDirection(lr)
          }
          lr.liftStatus = nextDirection
          lr.direction = nextDirection
          if (nextDirection == JinBoLiftStatus.Idle) lr.lastMoveOn = null

          val stopHeight = when (lr.direction) {
            JinBoLiftStatus.Up -> lr.config.floors.asSequence()
              .filter { it.height >= lr.h - JinBoConstants.FLOOR_EPSILON }
            JinBoLiftStatus.Down -> lr.config.floors.asReversed().asSequence()
              .filter { it.height <= lr.h + JinBoConstants.FLOOR_EPSILON }
            JinBoLiftStatus.Idle -> emptySequence()
          }.firstOrNull { floor ->
            !floor.disabled &&
              lr.reqs.any { req ->
                req.destFloor == floor.index && JinBoDirectionHelper.arrivalMatches(lr.config, req, lr.direction)
              }
          }?.height
          JinBoMoveService.advance(lr, stopHeight)

          // 按本帧结束高度一次性同步最后经过的楼层，容差内视为恰好到层。
          val floor = when (lr.liftStatus) {
            JinBoLiftStatus.Up -> lr.config.floors.lastOrNull {
              !it.disabled && it.height <= lr.h + JinBoConstants.FLOOR_EPSILON
            }
            JinBoLiftStatus.Down -> lr.config.floors.firstOrNull {
              !it.disabled && it.height >= lr.h - JinBoConstants.FLOOR_EPSILON
            }
            JinBoLiftStatus.Idle -> null
          }
          if (floor != null) {
            lr.curFloor = floor.index
            if (abs(lr.h - floor.height) <= JinBoConstants.FLOOR_EPSILON) lr.h = floor.height
          }

          val arrivalDoorCodes = JinBoRequestSelector.doorCodesForCurrentFloor(lr)
          if (arrivalDoorCodes.isNotEmpty()) {
            lr.liftStatus = JinBoLiftStatus.Idle
            lr.lastMoveOn = null
            JinBoDoorService.open(lr, arrivalDoorCodes)
          }
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
