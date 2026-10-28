package org.dao.device.lift.jinbo

import java.util.Date

/** 到站开门与同层呼梯门计时刷新；调用方负责持有电梯运行时的同步锁。 */
object JinBoDoorService {
  /**
   * 打开指定门。已有的开门动作不会重复启动；已打开的门重新开始自动关门计时。
   * 返回错误说明，成功时返回 null。
   */
  fun open(lift: JinBoRuntime, doorCodes: List<Int>, openedAt: Date = Date()): String? {
    val targets = doorCodes.distinct().ifEmpty { listOf(0) }.map { code ->
      lift.doors.firstOrNull { it.code == code } ?: return "门号 $code 不存在"
    }
    targets.forEach { it.startOpening(openedAt) }
    return null
  }

  /** 同层呼梯重新打开对应门，并从当前时刻重新计算自动关门延迟。 */
  fun refreshSameFloorTimer(lift: JinBoRuntime, doorCodes: List<Int>) {
    val requested = doorCodes.ifEmpty { lift.doors.take(1).map { it.code } }
    val now = Date()
    requested.mapNotNull { code -> lift.doors.firstOrNull { it.code == code } }
      .forEach { it.refreshSameFloorTimer(now) }
  }

}
