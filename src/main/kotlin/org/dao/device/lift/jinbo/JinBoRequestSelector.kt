package org.dao.device.lift.jinbo

/** 按完整方向规则选择电梯运行方向；调用方负责变更运行时状态。 */
object JinBoRequestSelector {
  /** 开门期间重新选择方向；返回新方向，无需变化时返回 null。 */
  fun selectDirectionDuringDoorHold(lr: JinBoRuntime): JinBoLiftStatus? {
    if (lr.liftStatus != JinBoLiftStatus.Idle) return null
    if (lr.doors.none { it.status == JinBoDoorStatus.OPEN }) return null

    val nextDirection = selectNextDirection(lr)
    return if (nextDirection == lr.direction) null else nextDirection
  }

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

  /** 当前楼层需要打开的门号；没有可立即服务的当前楼层请求时返回空列表。 */
  fun doorCodesForCurrentFloor(lr: JinBoRuntime): List<Int> {
    val direction = currentDirection(lr)
    return lr.reqs
      .filter { req -> canServeAtCurrentFloor(lr, req, direction) }
      .flatMap { it.doorCodes.ifEmpty { listOf(0) } }
      .ifEmpty { emptyList() }
  }

  private fun canServeAtCurrentFloor(lr: JinBoRuntime, req: JinBoReq, direction: JinBoLiftStatus): Boolean =
    req.destFloor == lr.curFloor &&
      (direction == JinBoLiftStatus.Idle || JinBoDirectionHelper.arrivalMatches(lr.config, req, direction))

  private fun currentDirection(lr: JinBoRuntime): JinBoLiftStatus =
    if (lr.liftStatus == JinBoLiftStatus.Idle) lr.direction else lr.liftStatus

  private fun hasRequestAbove(lr: JinBoRuntime): Boolean =
    lr.reqs.any { it.destFloor > lr.curFloor }

  private fun hasRequestBelow(lr: JinBoRuntime): Boolean =
    lr.reqs.any { it.destFloor < lr.curFloor }

  private fun decideIdleDirection(lr: JinBoRuntime): JinBoLiftStatus {
    val above = lr.reqs.map { it.destFloor }.filter { it > lr.curFloor }.minOrNull() ?: return JinBoLiftStatus.Down
    val below = lr.reqs.map { it.destFloor }.filter { it < lr.curFloor }.maxOrNull() ?: return JinBoLiftStatus.Up
    return if (above - lr.curFloor <= lr.curFloor - below) JinBoLiftStatus.Up else JinBoLiftStatus.Down
  }
}
