package org.dao.device.lift.jinbo

/**
 * 电梯方向与请求方向的判定。
 *
 * 外部呼梯与前进方向不一致时不能停靠；顶层向下、底层向上的呼梯在端点
 * 仍允许服务，避免端点呼梯永久留在队列中。
 */
object JinBoDirectionHelper {
  /** 判断电梯以 [arrivalDirection] 到达目标楼层时，能否服务该呼梯。 */
  fun arrivalMatches(config: JinBoConfig, req: JinBoReq, arrivalDirection: JinBoLiftStatus): Boolean {
    if (req.source != JinBoReqSource.OutDoor || req.type == null) return true
    // 顶层向下、底层向上的呼梯是离开端点的请求，可在到达时反向服务。
    return when (req.type) {
      JinBoLiftStatus.Down -> arrivalDirection == JinBoLiftStatus.Down || isTopFloor(config, req.destFloor)
      JinBoLiftStatus.Up -> arrivalDirection == JinBoLiftStatus.Up || isBottomFloor(config, req.destFloor)
      JinBoLiftStatus.Idle -> true
    }
  }

  private fun isTopFloor(config: JinBoConfig, floorIndex: Int): Boolean {
    val floors = config.floors.filter { !it.disabled }
    val floor = floors.firstOrNull { it.index == floorIndex } ?: return false
    return floors.all { it.height <= floor.height }
  }

  private fun isBottomFloor(config: JinBoConfig, floorIndex: Int): Boolean {
    val floors = config.floors.filter { !it.disabled }
    val floor = floors.firstOrNull { it.index == floorIndex } ?: return false
    return floors.all { it.height >= floor.height }
  }
}
