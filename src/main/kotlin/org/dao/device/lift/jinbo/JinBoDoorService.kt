package org.dao.device.lift.jinbo

import java.util.Date

/** 门状态推进及门号解析；调用方负责持有电梯运行时的同步锁。 */
object JinBoDoorService {
  /** 按每扇门的状态推进开门、自动关门和关门动作。 */
  fun update(lift: JinBoRuntime) {
    val now = Date()
    val config = JinBoDoorConfig(
      openCost = lift.config.openCost,
      closeCost = lift.config.closeCost,
      closeDelay = lift.config.closeDelay,
    )
    lift.doors.forEach {
      it.advance(now, config)
    }
  }

  /**
   * 打开指定门。已有的开门动作不会重复启动；已打开的门重新开始自动关门计时。
   * 返回错误说明，成功时返回 null。
   */
  fun open(lift: JinBoRuntime, doorCodes: List<Int>, openedAt: Date = Date()): String? {
    val targets = doorCodes.distinct().ifEmpty { listOf(0) }.map { code ->
      lift.doors.firstOrNull { it.code == code } ?: return "门号 $code 不存在"
    }
    if (targets.isEmpty()) return "电梯没有可打开的门"
    targets.forEach { it.startOpening(openedAt) }
    return null
  }

  /** 关闭全部门；已关闭或正在关闭的门保持不变。 */
  fun close(lift: JinBoRuntime) {
    val now = Date()
    lift.doors.forEach { it.startClosing(now) }
  }

  /** 同层呼梯重新打开对应门，并从当前时刻重新计算自动关门延迟。 */
  fun refreshSameFloorTimer(lift: JinBoRuntime, doorCodes: List<Int>) {
    val requested = doorCodes.ifEmpty { lift.doors.take(1).map { it.code } }
    val now = Date()
    requested.mapNotNull { code -> lift.doors.firstOrNull { it.code == code } }
      .forEach { it.refreshSameFloorTimer(now) }
  }

  /** 未指定门号时使用默认 0 号门。 */
  fun resolveDoorCodes(req: JinBoReq): JinBoReq = if (req.doorCodes.isEmpty()) req.copy(doorCodes = listOf(0)) else req

  /** 急停期间冻结各门的开门/关门计时，恢复时整体顺延。 */
  fun shiftOperationTimers(lift: JinBoRuntime, offsetMs: Long) {
    lift.doors.forEach { it.shiftOperationTimers(offsetMs) }
  }
}
