package org.dao.device.lift.jinbo

import org.slf4j.LoggerFactory
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
      JinBoMoveService.syncCurrentFloor(lr)
      if (lr.emc) return@withLiftLock JinBoSnapshot.capture(lr)

      JinBoDoorService.update(lr)
      if (!lr.isMoving()) JinBoMoveService.cleanOpenedFloorReq(lr)

      if (lr.targetFloor == null) {
        if (lr.hasOpenDoor()) {
          JinBoRequestSelector.selectDirectionDuringDoorHold(lr)
        } else if (!lr.holdAtCurrentFloor()) {
          JinBoRequestSelector.selectBetterReq(lr)
        }
      }

      JinBoRequestSelector.retargetNearerFloor(lr)

      if (lr.targetFloor != null && lr.targetFloor != lr.curFloor) {
        JinBoDoorService.close(lr)
        if (lr.allDoorsClosed()) {
          JinBoMoveService.advance(lr)
        }
      }

      JinBoMoveService.arrive(lr)
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
