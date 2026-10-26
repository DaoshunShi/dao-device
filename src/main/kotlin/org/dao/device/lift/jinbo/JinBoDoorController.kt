package org.dao.device.lift.jinbo

import java.util.Date

/** 门状态推进及门号解析；调用方负责持有电梯运行时的同步锁。 */
object JinBoDoorController {
  /** 按每扇门的状态推进开门、自动关门和关门动作。 */
  fun update(lr: JinBoRuntime) {
    val now = Date()
    lr.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPENING -> {
          val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
          if (now.time - startedAt.time >= lr.config.openCost) door.markOpen(now)
        }

        JinBoDoorStatus.OPEN -> {
          val openedAt = door.openOn ?: now.also { door.openOn = it }
          if (now.time - openedAt.time >= lr.config.closeDelay) {
            door.setStatus(JinBoDoorStatus.CLOSING, now)
          }
        }

        JinBoDoorStatus.CLOSING -> {
          val startedAt = door.opStartOn ?: now.also { door.opStartOn = it }
          if (now.time - startedAt.time >= lr.config.closeCost) {
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
  fun open(lr: JinBoRuntime, doorCodes: List<Int>, openedAt: Date = Date()): String? {
    val targets = doorCodes.distinct().ifEmpty { listOf(0) }.mapNotNull { code ->
      lr.doors.firstOrNull { it.code == code } ?: return "门号 $code 不存在"
    }
    if (targets.isEmpty()) return "电梯没有可打开的门"
    targets.forEach { door ->
      door.openOn = openedAt
      if (door.status != JinBoDoorStatus.OPENING) door.setStatus(JinBoDoorStatus.OPENING, openedAt)
    }
    return null
  }

  /** 关闭全部门；已关闭或正在关闭的门保持不变。 */
  fun close(lr: JinBoRuntime) {
    val now = Date()
    lr.doors.forEach { door ->
      when (door.status) {
        JinBoDoorStatus.OPENING, JinBoDoorStatus.OPEN -> door.setStatus(JinBoDoorStatus.CLOSING, now)
        JinBoDoorStatus.CLOSE, JinBoDoorStatus.CLOSING, JinBoDoorStatus.ERROR -> Unit
      }
    }
  }

  /** 同层呼梯重新打开对应门，并从当前时刻重新计算自动关门延迟。 */
  fun refreshSameFloorTimer(lr: JinBoRuntime, doorCodes: List<Int>) {
    val requested = doorCodes.ifEmpty { lr.doors.take(1).map { it.code } }
    val now = Date()
    requested.mapNotNull { code -> lr.doors.firstOrNull { it.code == code } }.forEach { door ->
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
}
