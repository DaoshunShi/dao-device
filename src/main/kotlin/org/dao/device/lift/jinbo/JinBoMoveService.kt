package org.dao.device.lift.jinbo

import kotlin.math.abs

/** 电梯升降、到站和请求清理；调用方负责持有电梯运行时的同步锁。 */
object JinBoMoveService {
  /** 用实际高度校正当前楼层，防止到站后仍以移动前楼层处理请求。 */
  fun syncCurrentFloor(lift: JinBoRuntime) {
    lift.floorForOpening()?.let { lift.curFloor = it }
  }

  /** 按当前速度沿方向推进；会逐层通过楼层，稍后由 [arrive] 判断是否停车。 */
  fun advance(lift: JinBoRuntime) {
    if (!lift.isMoving() || !lift.allDoorsClosed()) return

    val now = System.currentTimeMillis()
    val elapsedSeconds = lift.lastMoveOn?.let { (now - it) / 1000.0 } ?: JinBoConstants.DEFAULT_MOVE_SECONDS
    lift.lastMoveOn = now
    var remainingHeight = lift.config.liftSpeed * elapsedSeconds.coerceIn(0.0, JinBoConstants.MAX_STEP_SECONDS)

    while (remainingHeight > 0) {
      val next = nextFloor(lift) ?: break
      val distance = kotlin.math.abs(next.height - lift.h)
      when (lift.liftStatus) {
        JinBoLiftStatus.Up -> {
          if (remainingHeight + JinBoConstants.FLOOR_EPSILON >= distance) {
            lift.h = next.height
            lift.curFloor = next.index
            remainingHeight -= distance
          } else {
            lift.h += remainingHeight
            remainingHeight = 0.0
          }
        }

        JinBoLiftStatus.Down -> {
          if (remainingHeight + JinBoConstants.FLOOR_EPSILON >= distance) {
            lift.h = next.height
            lift.curFloor = next.index
            remainingHeight -= distance
          } else {
            lift.h -= remainingHeight
            remainingHeight = 0.0
          }
        }

        JinBoLiftStatus.Idle -> Unit
      }
      if (matchingDoorCodes(lift, lift.liftStatus).isNotEmpty()) return
      val nextDirection = JinBoRequestSelector.selectNextDirection(lift)
      if (nextDirection != lift.liftStatus) {
        lift.liftStatus = nextDirection
        lift.direction = nextDirection
        return
      }
    }
  }

  /** 到达当前楼层后判断是否应停车开门；没有对应请求时继续运行或在端点切换方向。 */
  fun arrive(lift: JinBoRuntime) {
    if (!lift.isMoving()) return

    val direction = lift.liftStatus
    val doorCodes = matchingDoorCodes(lift, direction)

    if (doorCodes.isNotEmpty()) {
      lift.liftStatus = JinBoLiftStatus.Idle
      lift.lastMoveOn = null
      JinBoDoorService.open(lift, doorCodes)
      return
    }

    val nextDirection = JinBoRequestSelector.selectNextDirection(lift)
    lift.liftStatus = nextDirection
    lift.direction = nextDirection
    if (nextDirection == JinBoLiftStatus.Idle) lift.lastMoveOn = null
  }

  /** 当前楼层指定门已经打开时，才视为完成对应的呼梯请求。 */
  fun cleanOpenedFloorReq(lift: JinBoRuntime) {
    val direction = if (lift.liftStatus == JinBoLiftStatus.Idle) lift.direction else lift.liftStatus
    lift.reqs.removeIf { req ->
      req.destFloor == lift.floorForOpening() &&
        (direction == JinBoLiftStatus.Idle || JinBoDirectionHelper.arrivalMatches(lift.config, req, direction)) &&
        req.doorCodes.ifEmpty { listOf(0) }.all { code ->
          lift.doors.firstOrNull { it.code == code }?.status == JinBoDoorStatus.OPEN
        }
    }
  }

  private fun matchingDoorCodes(lift: JinBoRuntime, direction: JinBoLiftStatus): List<Int> {
    return lift.reqs
      .filter { it.destFloor == lift.floorForOpening() && JinBoDirectionHelper.arrivalMatches(lift.config, it, direction) }
      .flatMap { it.doorCodes.ifEmpty { listOf(0) } }
      .ifEmpty { emptyList() }
  }

  private fun nextFloor(lift: JinBoRuntime): JinBoFloor? {
    return when (lift.liftStatus) {
      JinBoLiftStatus.Up -> lift.config.floors
        .filter { !it.disabled && it.height > lift.h + JinBoConstants.FLOOR_EPSILON }
        .minByOrNull { it.height }

      JinBoLiftStatus.Down -> lift.config.floors
        .filter { !it.disabled && it.height < lift.h - JinBoConstants.FLOOR_EPSILON }
        .maxByOrNull { it.height }

      JinBoLiftStatus.Idle -> null
    }
  }
}
