package org.dao.device.lift.jinbo

import java.util.Date

/** 门状态推进及门号解析；调用方负责持有电梯运行时的同步锁。 */
object JinBoDoorService {
  /** 按每扇门的状态推进开门、自动关门和关门动作。 */
  fun update(lift: JinBoRuntime) {
    val now = Date()
    lift.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPENING -> {
          val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
          if (now.time - startedAt.time >= lift.config.openCost) door.markOpen(now)
        }

        JinBoDoorStatus.OPEN -> {
          val openedAt = door.openOn ?: now.also { door.openOn = it }
          if (now.time - openedAt.time >= lift.config.closeDelay) {
            door.setStatus(JinBoDoorStatus.CLOSING, now)
          }
        }

        JinBoDoorStatus.CLOSING -> {
          val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
          if (now.time - startedAt.time >= lift.config.closeCost) {
            door.setStatus(JinBoDoorStatus.CLOSE, now)
          }
        }

        JinBoDoorStatus.CLOSE, JinBoDoorStatus.ERROR -> Unit
      }
    }
  }

  /**
   * 打开指定门。已有的开门动作不会重复启动；已打开的门重新开始自动关门计时。
   * 返回错误说明，成功时返回 null。
   */
  fun open(lift: JinBoRuntime, doorCodes: List<Int>, openedAt: Date = Date()): String? {
    val targets = doorCodes.distinct().ifEmpty { listOf(0) }.mapNotNull { code ->
      lift.doors.firstOrNull { it.code == code } ?: return "门号 $code 不存在"
    }
    if (targets.isEmpty()) return "电梯没有可打开的门"
    targets.forEach { door ->
      door.openOn = openedAt
      if (door.status != JinBoDoorStatus.OPENING) door.setStatus(JinBoDoorStatus.OPENING, openedAt)
    }
    return null
  }

  /** 关闭全部门；已关闭或正在关闭的门保持不变。 */
  fun close(lift: JinBoRuntime) {
    val now = Date()
    lift.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPENING, JinBoDoorStatus.OPEN -> door.setStatus(JinBoDoorStatus.CLOSING, now)
        JinBoDoorStatus.CLOSE, JinBoDoorStatus.CLOSING, JinBoDoorStatus.ERROR -> Unit
      }
    }
  }

  /** 同层呼梯重新打开对应门，并从当前时刻重新计算自动关门延迟。 */
  fun refreshSameFloorTimer(lift: JinBoRuntime, doorCodes: List<Int>) {
    val requested = doorCodes.ifEmpty { lift.doors.take(1).map { it.code } }
    val now = Date()
    requested.mapNotNull { code -> lift.doors.firstOrNull { it.code == code } }.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPEN -> door.openOn = now
        JinBoDoorStatus.CLOSING -> {
          door.openOn = now
          door.setStatus(JinBoDoorStatus.OPENING, now)
        }

        else -> Unit
      }
    }
  }

  /** 未指定门号时使用默认 0 号门。 */
  fun resolveDoorCodes(req: JinBoReq): JinBoReq =
    if (req.doorCodes.isEmpty()) req.copy(doorCodes = listOf(0)) else req

  /** 急停期间冻结各门的开门/关门计时，恢复时整体顺延。 */
  fun shiftOperationTimers(lift: JinBoRuntime, offsetMs: Long) {
    lift.doors.forEach { door ->
      door.openOn = door.openOn?.let { Date(it.time + offsetMs) }
      door.opStartOn = door.opStartOn?.let { Date(it.time + offsetMs) }
    }
  }
}
