package org.dao.device.lift.jinbo

import kotlin.math.abs

/** 在当前未完成请求中选择下一停靠楼层；调用方负责持有电梯运行时的同步锁。 */
object JinBoRequestSelector {
  /** 运行中遇到同方向更近的请求时，提前把目标切换到该楼层。 */
  fun retargetNearerFloor(lr: JinBoRuntime) {
    val target = lr.targetFloor ?: return
    if (!lr.isMoving()) return

    lr.targetFloor = when (lr.liftStatus) {
      JinBoLiftStatus.Up -> lr.reqs
        .filter { it.destFloor > lr.curFloor && it.destFloor < target && matchesTravelDirection(lr, it) }
        .minOfOrNull { it.destFloor }

      JinBoLiftStatus.Down -> lr.reqs
        .filter { it.destFloor < lr.curFloor && it.destFloor > target && matchesTravelDirection(lr, it) }
        .maxOfOrNull { it.destFloor }

      JinBoLiftStatus.Idle -> null
    } ?: target
  }

  /** 保留原有选择语义，同时更新目标楼层、方向及运行状态。 */
  fun selectBetterReq(lr: JinBoRuntime) {
    val direction = if (lr.liftStatus == JinBoLiftStatus.Idle) lr.direction else lr.liftStatus
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
          JinBoDirection.requestMatches(it, direction.opposite())
      }
      .minByOrNull { abs(it.destFloor - lr.curFloor) }
      ?.destFloor
    // 同层同方向梯外呼梯需要停靠开门；选中的同层目标不能丢掉当前方向。
    // 空闲电梯停在同层时没有既定运行方向，任一端点方向呼梯都应开门服务。
    val sameFloorOutDoor = lr.reqs.any { req ->
      req.destFloor == lr.curFloor &&
        (direction == JinBoLiftStatus.Idle || JinBoDirection.requestMatches(req, direction))
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
      lr.direction = lr.liftStatus
    }

    if (lr.targetFloor == null) {
      lr.liftStatus = JinBoLiftStatus.Idle
      lr.direction = JinBoLiftStatus.Idle
    }
  }

  private fun matchesTravelDirection(lr: JinBoRuntime, req: JinBoReq): Boolean =
    JinBoDirection.arrivalMatches(lr.config, req, lr.direction)
}
