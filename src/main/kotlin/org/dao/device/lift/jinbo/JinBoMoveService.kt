package org.dao.device.lift.jinbo

/** 电梯移动推进；调用方负责持有电梯运行时的同步锁。 */
object JinBoMoveService {

  /** 按当前速度沿方向推进，不越过 [stopHeight]；为 null 时不限制本次停靠高度。 */
  fun advance(lift: JinBoRuntime, stopHeight: Double?) {
    if (!lift.isMoving() || !lift.allDoorsClosed()) return

    val now = System.currentTimeMillis()
    val t = lift.lastMoveOn?.let { (now - it) / 1000.0 } ?: JinBoConstants.DEFAULT_MOVE_SECONDS
    val d = lift.config.liftSpeed * t
    if (d <= 0) return

    val nextHeight = lift.h + d * lift.liftStatus.heightMultiplier
    val endHeight = when (lift.liftStatus) {
      JinBoLiftStatus.Up -> minOf(nextHeight, stopHeight ?: nextHeight)
      JinBoLiftStatus.Down -> maxOf(nextHeight, stopHeight ?: nextHeight)
      JinBoLiftStatus.Idle -> error("Idle 状态不应进入移动步骤")
    }
    lift.h = endHeight
    lift.lastMoveOn = now
  }
}