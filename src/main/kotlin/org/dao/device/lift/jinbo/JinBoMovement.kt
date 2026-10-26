package org.dao.device.lift.jinbo

import kotlin.math.abs

/** 电梯单步位移；调用方负责持有电梯运行时的同步锁。 */
object JinBoMovement {
  private const val MAX_STEP_SECONDS = 1.0 // 单次位移上限，避免线程暂停后跳层
  private const val DEFAULT_MOVE_SECONDS = 0.1 // 没有上一帧时间时，按主循环周期估算

  /** 用实际高度校正当前楼层，防止到站后仍以移动前楼层处理请求。 */
  fun syncCurrentFloor(lr: JinBoRuntime) {
    val floor = lr.config.floors
      .filter { !it.disabled }
      .minByOrNull { abs(it.height - lr.h) }
    if (floor != null && abs(floor.height - lr.h) < 1e-6) lr.curFloor = floor.index
  }

  /** 按当前速度推进一帧；门未全部关闭或没有目标楼层时不移动。 */
  fun advance(lr: JinBoRuntime) {
    if (!lr.lifting || !lr.canLifting()) return

    val target = lr.config.floors.firstOrNull { it.index == lr.targetFloor } ?: return
    val now = System.currentTimeMillis()
    val elapsedSeconds = lr.lastMoveOn?.let { (now - it) / 1000.0 } ?: DEFAULT_MOVE_SECONDS
    lr.lastMoveOn = now
    val stepHeight = lr.config.liftSpeed * elapsedSeconds.coerceIn(0.0, MAX_STEP_SECONDS)

    when (lr.liftStatus) {
      JinBoLiftStatus.Up -> {
        lr.h = minOf(lr.h + stepHeight, target.height)
        lr.config.floors.lastOrNull { it.height <= lr.h }?.index?.let { lr.curFloor = it }
      }

      JinBoLiftStatus.Down -> {
        lr.h = maxOf(lr.h - stepHeight, target.height)
        lr.config.floors.firstOrNull { it.height >= lr.h }?.index?.let { lr.curFloor = it }
      }

      JinBoLiftStatus.Idle -> {
        lr.curFloor = lr.config.floors.minBy { abs(it.height - lr.h) }.index
      }
    }
  }
}
