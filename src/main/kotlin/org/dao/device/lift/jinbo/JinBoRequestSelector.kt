package org.dao.device.lift.jinbo

/** 按完整方向规则选择电梯运行方向；调用方负责变更运行时状态。 */
object JinBoRequestSelector {
  /** 运行方向上最近停靠层及本次停靠实际服务的呼梯方向。 */
  data class JinBoNextStop(val floor: JinBoFloor, val direction: JinBoLiftStatus)

  /** 选择下一运行方向：优先保持当前物理方向，其次反向，最后休息。 */
  fun selectNextDirection(lr: JinBoRuntime): JinBoLiftStatus {
    val direction = currentDirection(lr)
    val hasAbove = hasRequestAbove(lr)
    val hasBelow = hasRequestBelow(lr)

    return when (direction) {
      JinBoLiftStatus.Up -> when {
        hasAbove -> JinBoLiftStatus.Up
        hasBelow -> JinBoLiftStatus.Down
        else -> JinBoLiftStatus.Idle
      }

      JinBoLiftStatus.Down -> when {
        hasBelow -> JinBoLiftStatus.Down
        hasAbove -> JinBoLiftStatus.Up
        else -> JinBoLiftStatus.Idle
      }

      JinBoLiftStatus.Idle -> when {
        hasAbove && !hasBelow -> JinBoLiftStatus.Up
        hasBelow && !hasAbove -> JinBoLiftStatus.Down
        hasAbove && hasBelow -> decideIdleDirection(lr)
        else -> JinBoLiftStatus.Idle
      }
    }
  }

  /** 当前楼层可立即服务的呼梯请求；没有时返回空列表。 */
  fun currentFloorReqs(lr: JinBoRuntime): List<JinBoReq> {
    val direction = currentDirection(lr)
    return lr.reqs
      .filter { req -> canServeAtCurrentFloor(lr, req, direction) }
      .ifEmpty { emptyList() }
  }

  /** 运行方向上最近需要停靠的楼层；没有可停靠请求时返回 null。 */
  fun nextStop(lr: JinBoRuntime): JinBoNextStop? {
    val direction = currentDirection(lr)
    val floorsAhead = when (direction) {
      JinBoLiftStatus.Up -> lr.config.floors.asSequence().filter { !it.disabled && it.height >= lr.h - JinBoConstants.FLOOR_EPSILON }
      JinBoLiftStatus.Down -> lr.config.floors.asReversed().asSequence().filter { !it.disabled && it.height <= lr.h + JinBoConstants.FLOOR_EPSILON }
      JinBoLiftStatus.Idle -> emptySequence()
    }.toList()

    val floor = floorsAhead.firstOrNull { floor ->
      lr.reqs.any { req ->
        req.destFloor == floor.index && JinBoDirectionHelper.arrivalMatches(lr.config, req, direction)
      }
    } ?: floorsAhead.firstOrNull { floor ->
      lr.reqs.any { it.destFloor == floor.index }
    } ?: return null

    return JinBoNextStop(floor, direction)
  }

  /** 精确停靠到 [floorIndex] 后，按 [stopDirection] 可打开的门号；没有可服务请求时返回空列表。 */
  fun doorCodesAtFloor(lr: JinBoRuntime, floorIndex: Int, stopDirection: JinBoLiftStatus): List<Int> {
    if (lr.floorForOpening() != floorIndex) return emptyList()
    return lr.reqs
      .filter { req -> canServeAtFloor(lr, req, floorIndex, stopDirection) }
      .flatMap { it.doorCodes.ifEmpty { listOf(0) } }
  }

  /** 门已经打开后，清理当前楼层可服务的呼梯请求。 */
  fun cleanOpenedFloorReq(lr: JinBoRuntime, direction: JinBoLiftStatus) {
    val floorIndex = lr.floorForOpening() ?: return
    val reqs = lr.reqs.filter { req -> canServeAtFloor(lr, req, floorIndex, direction) }.toSet()
    lr.reqs.removeIf { req ->
      req in reqs &&
        req.doorCodes.ifEmpty { listOf(0) }.all { code ->
          lr.doors.firstOrNull { it.code == code }?.status == JinBoDoorStatus.OPEN
        }
    }
  }

  private fun canServeAtCurrentFloor(lr: JinBoRuntime, req: JinBoReq, direction: JinBoLiftStatus): Boolean =
    req.destFloor == lr.floorForOpening() &&
      (direction == JinBoLiftStatus.Idle || JinBoDirectionHelper.arrivalMatches(lr.config, req, direction))

  private fun canServeAtFloor(lr: JinBoRuntime, req: JinBoReq, floorIndex: Int, direction: JinBoLiftStatus): Boolean =
    req.destFloor == floorIndex &&
      lr.floorForOpening() == floorIndex &&
      (direction == JinBoLiftStatus.Idle || JinBoDirectionHelper.arrivalMatches(lr.config, req, direction))

  fun currentDirection(lr: JinBoRuntime): JinBoLiftStatus =
    if (lr.liftStatus == JinBoLiftStatus.Idle) lr.direction else lr.liftStatus

  private fun hasRequestAbove(lr: JinBoRuntime): Boolean = lr.reqs.any { it.destFloor > lr.curFloor }

  private fun hasRequestBelow(lr: JinBoRuntime): Boolean = lr.reqs.any { it.destFloor < lr.curFloor }

  private fun decideIdleDirection(lr: JinBoRuntime): JinBoLiftStatus {
    val above = lr.reqs.map { it.destFloor }.filter { it > lr.curFloor }.minOrNull() ?: return JinBoLiftStatus.Down
    val below = lr.reqs.map { it.destFloor }.filter { it < lr.curFloor }.maxOrNull() ?: return JinBoLiftStatus.Up
    return if (above - lr.curFloor <= lr.curFloor - below) JinBoLiftStatus.Up else JinBoLiftStatus.Down
  }
}
