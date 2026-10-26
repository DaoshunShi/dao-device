package org.dao.device.lift.jinbo

/** 电梯模拟器的共用运行常量。 */
object JinBoConstants {
  const val FLOOR_EPSILON = 1e-6 // 楼层高度浮点比较容差（米）
  const val MAX_STEP_SECONDS = 1.0 // 单次位移上限，避免线程暂停后跳层
  const val DEFAULT_MOVE_SECONDS = 0.1 // 没有上一帧时间时，按主循环周期估算
  const val SIMULATION_INTERVAL_MS = 50L // 仿真计算与画面刷新周期：每秒 20 帧
  const val REPLAY_INTERVAL_MS = 500L // 回放周期：每秒 2 帧
}
