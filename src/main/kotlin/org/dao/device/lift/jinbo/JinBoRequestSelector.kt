package org.dao.device.lift.jinbo

/** 按完整方向规则选择电梯运行方向；调用方负责变更运行时状态。 */
object JinBoRequestSelector {
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
    val direction = serviceDirectionForCurrentFloor(lr)
    return lr.reqs
      .filter { req -> canServeAtCurrentFloor(lr, req, direction) }
      .ifEmpty { emptyList() }
  }

  /** 门已经打开后，清理当前楼层可服务的呼梯请求。 */
  fun cleanOpenedFloorReq(lr: JinBoRuntime) {
    val reqs = currentFloorReqs(lr).toSet()
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

  private fun currentDirection(lr: JinBoRuntime): JinBoLiftStatus =
    if (lr.liftStatus == JinBoLiftStatus.Idle) lr.direction else lr.liftStatus

  /**
   * 当前楼层本次停靠实际服务的呼梯方向。
   *
   * 通常与电梯物理运行方向一致；但当电梯为响应相反方向的梯外呼梯而到达
   * 中间楼层时，应按该梯外请求的方向服务，而不是按到达方向误判为不可服务。
   */
  private fun serviceDirectionForCurrentFloor(lr: JinBoRuntime): JinBoLiftStatus {
    val arrivalDirection = currentDirection(lr)
    val atCurrentFloor = lr.reqs.filter { it.destFloor == lr.floorForOpening() }
    if (atCurrentFloor.any { JinBoDirectionHelper.arrivalMatches(lr.config, it, arrivalDirection) }) {
      return arrivalDirection
    }

    return atCurrentFloor
      .firstOrNull { it.source == JinBoReqSource.OutDoor && it.type != null }
      ?.type
      ?: arrivalDirection
  }

  private fun hasRequestAbove(lr: JinBoRuntime): Boolean = lr.reqs.any { it.destFloor > lr.curFloor }

  private fun hasRequestBelow(lr: JinBoRuntime): Boolean = lr.reqs.any { it.destFloor < lr.curFloor }

  private fun decideIdleDirection(lr: JinBoRuntime): JinBoLiftStatus {
    val above = lr.reqs.map { it.destFloor }.filter { it > lr.curFloor }.minOrNull() ?: return JinBoLiftStatus.Down
    val below = lr.reqs.map { it.destFloor }.filter { it < lr.curFloor }.maxOrNull() ?: return JinBoLiftStatus.Up
    return if (above - lr.curFloor <= lr.curFloor - below) JinBoLiftStatus.Up else JinBoLiftStatus.Down
  }
}
