package org.dao.device.lift.jinbo

import kotlin.math.abs

/** 电梯升降、到站和请求清理；调用方负责持有电梯运行时的同步锁。 */
object JinBoMoveService {
  /** 用实际高度校正当前楼层，防止到站后仍以移动前楼层处理请求。 */
  fun syncCurrentFloor(lift: JinBoRuntime) {
    val floor = lift.config.floors
      .filter { !it.disabled }
      .minByOrNull { abs(it.height - lift.h) }
    if (floor != null && abs(floor.height - lift.h) < JinBoConstants.FLOOR_EPSILON) lift.curFloor = floor.index
  }

  /** 按当前速度推进一帧；门未全部关闭或没有目标楼层时不移动。 */
  fun advance(lift: JinBoRuntime) {
    if (!lift.isMoving() || !canLift(lift)) return

    val target = lift.config.floors.firstOrNull { it.index == lift.targetFloor } ?: return
    val now = System.currentTimeMillis()
    val elapsedSeconds = lift.lastMoveOn?.let { (now - it) / 1000.0 } ?: JinBoConstants.DEFAULT_MOVE_SECONDS
    lift.lastMoveOn = now
    val stepHeight = lift.config.liftSpeed * elapsedSeconds.coerceIn(0.0, JinBoConstants.MAX_STEP_SECONDS)

    when (lift.liftStatus) {
      JinBoLiftStatus.Up -> {
        lift.h = minOf(lift.h + stepHeight, target.height)
        lift.config.floors.lastOrNull { it.height <= lift.h }?.index?.let { lift.curFloor = it }
      }

      JinBoLiftStatus.Down -> {
        lift.h = maxOf(lift.h - stepHeight, target.height)
        lift.config.floors.firstOrNull { it.height >= lift.h }?.index?.let { lift.curFloor = it }
      }

      JinBoLiftStatus.Idle -> {
        lift.curFloor = lift.config.floors.minBy { abs(it.height - lift.h) }.index
      }
    }
  }

  /** 到站后开启请求对应的门；只有门真正打开后，呼梯请求才会由 [cleanOpenedFloorReq] 清理。 */
  fun arrive(lift: JinBoRuntime) {
    if (lift.curFloor != lift.targetFloor) return

    val direction = if (lift.liftStatus == JinBoLiftStatus.Idle) lift.direction else lift.liftStatus
    val doorCodes = lift.reqs
      .filter { it.destFloor == lift.curFloor && matchesTravelDirection(lift, it, direction) }
      .flatMap { it.doorCodes }
      .ifEmpty { listOf(0) }
    lift.liftStatus = JinBoLiftStatus.Idle
    lift.targetFloor = null
    lift.lastMoveOn = null
    JinBoDoorService.open(lift, doorCodes)
  }

  /** 当前楼层指定门已经打开时，才视为完成对应的呼梯请求。 */
  fun cleanOpenedFloorReq(lift: JinBoRuntime) {
    lift.reqs.removeIf { req ->
      val direction = if (lift.liftStatus == JinBoLiftStatus.Idle) lift.direction else lift.liftStatus
      req.destFloor == lift.curFloor &&
        lift.targetFloor == null &&
        matchesTravelDirection(lift, req, direction) &&
        req.doorCodes.ifEmpty { listOf(0) }.all { code ->
          lift.doors.firstOrNull { it.code == code }?.status == JinBoDoorStatus.OPEN
        }
    }
  }

  private fun canLift(lift: JinBoRuntime): Boolean = lift.allDoorsClosed()

  private fun matchesTravelDirection(lift: JinBoRuntime, req: JinBoReq, direction: JinBoLiftStatus): Boolean =
    JinBoDirection.arrivalMatches(lift.config, req, direction)

}
