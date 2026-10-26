package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 回放录制器
 *
 * 设计要点：
 * - 录制搭在仿真 tick 上（JinBoServer.processLift 内），一个 tick 一帧，最高帧率 = 仿真 20fps。
 * - capture 在调用方的 synchronized(lr) 内执行，取到的是一致快照，无需额外加锁。
 * - 帧以 JSONL（每行一帧）写入 REPLAY logger → logs/replay.log，复用 log4j2 轮转/删除机制。
 * - 去重：可视状态与上一写入帧完全相同、且本帧无日志时跳过写盘。
 *   回放侧按“取 ts <= 目标时刻的最后一帧”消费稀疏帧，语义不受影响。
 * - 日志锚定：事件日志先 bufferLog 入队，capture 时 drain 归入当前帧，保证日志与状态帧对齐。
 */
object JinBoRecorder {
  // 绑定 log4j2 中 name="REPLAY" 的 logger（写 logs/replay.log）
  private val replayLog = LoggerFactory.getLogger("REPLAY")
  private val executor = Executors.newSingleThreadExecutor()

  // liftId -> 待归帧的事件日志行
  private val logBuf: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

  // liftId -> 上一写入帧的去重快照
  private val lastKey: MutableMap<String, Snapshot> = ConcurrentHashMap()

  /**
   * 缓冲一条事件日志，等待下一次 capture 归入当前帧。
   * 由 JinBoLogService / LiftFrame.logEvent 调用。
   */
  fun bufferLog(liftId: String, line: String) {
    logBuf.getOrPut(liftId) { CopyOnWriteArrayList() }.add(line)
  }

  fun dispose() {
    executor.shutdown()
  }

  /**
   * 采集一帧。必须在 synchronized(lr) 内调用（processLift 已满足）。
   * 急停状态也应录制，故调用点需放在 emc 提前返回之前。
   */
  fun capture(lr: JinBoRuntime) {
    val liftId = lr.config.id
    val buttons = JinBoBtnService.buttons(liftId)
    val reqs = requestEntries(liftId, lr.reqs.toList(), System.currentTimeMillis())
    val doors = lr.doors.map { it.copy() }
    val logs = logBuf.remove(liftId).orEmpty().toList()

    // 在采集线程完成归帧、去重和时间戳，避免异步队列把日志挂到错误的帧。
    val snapshot = Snapshot(
      curFloor = lr.curFloor,
      h = lr.h,
      liftStatus = lr.liftStatus,
      direction = lr.direction,
      emergencyStopped = lr.emc,
      buttons = buttons,
      reqs = reqs,
      doors = doors,
    )
    if (logs.isEmpty() && snapshot == lastKey[liftId]) {
      return // 状态无变化且无日志，跳过写盘
    }
    lastKey[liftId] = snapshot

    val frame = JinBoFrame(
      liftId = liftId,
      ts = System.currentTimeMillis(),
      curFloor = snapshot.curFloor,
      h = snapshot.h,
      liftStatus = snapshot.liftStatus,
      direction = snapshot.direction,
      doorStatus = aggregateDoorStatus(snapshot.doors),
      emergencyStopped = snapshot.emergencyStopped,
      reqs = snapshot.reqs,
      logs = logs,
      doors = snapshot.doors,
      buttons = snapshot.buttons,
    )
    // 仅把可能阻塞的序列化和日志写盘移出电梯锁与仿真线程。
    executor.execute {
      replayLog.info(JsonHelper.mapper.writeValueAsString(frame))
    }
  }

  /** 兼容旧帧字段：状态不一致时优先保留异常和门动作。 */
  private fun aggregateDoorStatus(doors: List<JinBoDoor>): JinBoDoorStatus =
    doors.minByOrNull { it.status.displayPriority }?.status ?: JinBoDoorStatus.CLOSE

  private fun requestEntries(liftId: String, reqs: List<JinBoReq>, capturedAt: Long): JinBoRequestEntries {
    val known = JinBoBtnService.requests(liftId).associateBy { it.req }
    return JinBoRequestEntries(reqs.map { req -> known[req] ?: JinBoRequestEntry(req, capturedAt) })
  }
}
