package org.dao.device.lift.jinbo

import org.apache.logging.log4j.core.LogEvent
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.gui.LiftEvent
import org.dao.device.lift.jinbo.gui.LiftFrame
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

/**
 * 仿真金博电梯
 *
 * 暂定：只实现单部电梯的控制，不实现电梯组
 * 暂定：电梯移动的最小时间单位是秒
 * 判断电梯是否在某一层：curFloor = 1 && liftStatus !in [Up, Down]
 * 电梯到达下一层的条件：当前时间 - moveStartOn >= costPerFloor
 * TODO 基于电梯高度计算当前楼层
 *
 * 配置：
 * - 电梯 ID
 * - 总楼层，每层的 label (map)
 * - 上升/下降一层楼需要的时间
 * - 开门关门需要的时间
 * - 开门维持的时间
 * 运行状态
 * - 电梯目前的目标楼层
 *   - 外部按的 -> 不显示在电梯内
 *   - 内部按的 + tcp 请求的 -> 显示在电梯内
 * - 电梯到达顶层、底层后，清理所有目标楼层
 *
 */
object JinBoServer {
  private val logger = LoggerFactory.getLogger(javaClass)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var worker: Future<*>? = null

  val lifts: MutableMap<String, JinBoRuntime> = ConcurrentHashMap()

  private const val STEP_DURATION = 1 // 电梯移动的最小时间单位，秒
  val frame = LiftFrame()

  fun init() {
    logger.info("JinBoLiftService init")

    // 加载 lifts
    lifts.putAll(JinBoRepo.load())

    worker = executor.submit { process() }

    frame.isVisible = true
  }

  fun dispose() {
    worker?.cancel(true)
  }

  private fun process() {
    while (!Thread.interrupted()) {
      for (lift in lifts.values) {
        processLift(lift)
      }
      Thread.sleep(1000)
    }
  }

  /**
   * 处理电梯
   */
  fun processLift(lr: JinBoRuntime) {
    synchronized(lr) {
      // 打印日志
      logAll()

      // 清理原本的任务
      // cleanCurrentFloorReq(lr)

      // 选择更优的任务
      selectBetterReq(lr)

      // 电梯移动
      liftStep(lr)

      // 到达目标楼层
      afterArrive(lr)

      // 更新门的状态
      updateDoorState(lr)
    }
  }

  private fun cleanCurrentFloorReq(lr: JinBoRuntime) {
    if (!lr.lifting) {
      // 静止的电梯，先处理同楼层可以处理的任务
      // - 外部请求：同楼层、同方向
      // - 内部请求：所有
      // - tcp 请求：TODO 暂定同内部请求
      lr.reqs.removeIf {
        it.destFloor == lr.curFloor &&
          (it.source != JinBoReqSource.OutDoor || it.type == lr.liftStatus || lr.liftStatus == JinBoLiftStatus.Idle)
      }
    }
  }

  /**
   * 选择更优的呼叫电梯请求
   */
  private fun selectBetterReq(lr: JinBoRuntime) {
    // 运行中的电梯，只能选路上的电梯
    lr.targetFloor =
      when (lr.liftStatus) {
        JinBoLiftStatus.Up -> lr.reqs.filter {
          it.destFloor > lr.curFloor || (it.destFloor == lr.curFloor && lr.infloor())
        }.minByOrNull { it.destFloor }
        JinBoLiftStatus.Down -> lr.reqs.filter {
          it.destFloor < lr.curFloor || (it.destFloor == lr.curFloor && lr.infloor())
        }.maxByOrNull { it.destFloor }
        JinBoLiftStatus.Idle -> lr.reqs.minByOrNull { it.destFloor - lr.curFloor }
      }?.destFloor
    if (lr.targetFloor != null) {
      lr.liftStatus = if (lr.targetFloor!! > lr.curFloor) {
        JinBoLiftStatus.Up
      } else if (lr.targetFloor!! < lr.curFloor) {
        JinBoLiftStatus.Down
      } else {
        JinBoLiftStatus.Idle
      }
    }

    if (lr.targetFloor == null) {
      lr.liftStatus = JinBoLiftStatus.Idle
      lr.lifting = false
    }
  }

  /**
   * 电梯移动
   */
  private fun liftStep(lr: JinBoRuntime) {
    if (lr.doorStatus != JinBoDoorStatus.CLOSE) return

    val stepH = lr.config.liftSpeed * STEP_DURATION
    val tf = lr.config.floors.firstOrNull { it.index == lr.targetFloor } ?: return // 找不到，就不升降

    when (lr.liftStatus) {
      JinBoLiftStatus.Up -> {
        if (lr.h + stepH >= tf.height) {
          lr.h = tf.height
        } else {
          lr.h += stepH
        }
        lr.config.floors.lastOrNull { it.height <= lr.h }?.index?.let { lr.curFloor = it }
      }

      JinBoLiftStatus.Down -> {
        if (lr.h - stepH <= tf.height) {
          lr.h = tf.height
        } else {
          lr.h -= stepH
        }
        lr.config.floors.firstOrNull { it.height >= lr.h }?.index?.let { lr.curFloor = it }
      }

      else -> {
        lr.curFloor = lr.config.floors.minBy { abs(it.height - lr.h) }.index
      }
    }
  }

  /**
   * 电梯到达目标楼层后的处理
   */
  private fun afterArrive(lr: JinBoRuntime) {
    if (lr.curFloor == lr.targetFloor) {
      lr.lifting = false
      cleanCurrentFloorReq(lr)
      openDoor(lr)
    }
  }

  /**
   * 开电梯门 OPENING
   */
  private fun openDoor(lr: JinBoRuntime): Boolean {
    if (lr.lifting) {
      logger.warn("电梯正在运行，不能开门")
      return false
    } else if (lr.doorStatus == JinBoDoorStatus.OPENING) {
      // lr.doorOpDoneOn = null
      // lr.doorOpStartOn = Date()
      return true
    } else if (lr.doorStatus == JinBoDoorStatus.OPEN) {
      lr.doorOpDoneOn = Date()
      lr.doorOpStartOn = null
      return true
    } else {
      lr.doorStatus = JinBoDoorStatus.OPENING
      lr.doorOpDoneOn = null
      lr.doorOpStartOn = Date()
      return true
    }
  }

  /**
   * 关电梯门 CLOSING
   */
  private fun closeDoor(lr: JinBoRuntime): Boolean {
    if (lr.lifting) {
      logger.warn("电梯正在运行，不能关门")
      return false
    } else {
      lr.doorStatus = JinBoDoorStatus.CLOSING
      lr.doorOpDoneOn = null
      lr.doorOpStartOn = Date()
      return true
    }
  }

  /**
   * 标记并更新门的状态
   */
  private fun updateDoorState(lr: JinBoRuntime) {
    if (lr.lifting) return
    val now = Date()
    if (lr.doorStatus == JinBoDoorStatus.OPENING) {
      if (lr.doorOpStartOn == null) {
        lr.doorOpStartOn = now
      }
      if (now.time - (lr.doorOpStartOn ?: now).time >= lr.config.costDoorOp) {
        lr.doorStatus = JinBoDoorStatus.OPEN
      }
    } else if (lr.doorStatus == JinBoDoorStatus.CLOSING) {
      if (lr.doorOpStartOn == null) {
        lr.doorOpStartOn = now
      }
      if (now.time - (lr.doorOpStartOn ?: now).time >= lr.config.costDoorOp) {
        lr.doorStatus = JinBoDoorStatus.CLOSE
      }
    } else if (lr.doorStatus == JinBoDoorStatus.OPEN) {
      if (lr.doorOpDoneOn == null) {
        lr.doorOpDoneOn = now
      }
      if (now.time - (lr.doorOpDoneOn ?: now).time >= lr.config.doorHoldDuration) {
        closeDoor(lr)
      }
    }
  }

  /**
   * TODO 单纯的开门。
   *
   * 如果电梯此时在某楼层，就开门
   * 如果电梯此时在运行中，就不要开门
   */
  fun open(liftId: String): JinBoResp = JinBoResp()

  /**
   * 请求电梯到指定楼层
   */
  fun request(liftId: String, req: JinBoReq): JinBoResp {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      lift.reqs.add(req)
    }
    return JinBoResp()
  }

  /**
   * 请求电梯关门
   */
  fun close(liftId: String): JinBoResp {
    val lr = mustGetLift(liftId)
    synchronized(lr) {
      return if (closeDoor(lr)) {
        JinBoResp("400", "$liftId 电梯正在运行中，不能关门")
      } else {
        JinBoResp()
      }
    }
  }

  /**
   * 获取电梯信息
   */
  fun status(liftId: String): JinBoStatusResp {
    val lift = mustGetLift(liftId)
    return JinBoStatusResp.of(lift)
  }

  fun getLifts(): List<JinBoRuntime> = lifts.values.toList()

  private fun mustGetLift(liftId: String): JinBoRuntime = lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")

  /**
   * 打印所有日志
   */
  private fun logAll() {
    // logger.info("logAll: ${JsonHelper.mapper.writeValueAsString(lifts.values)}")
  }

  /**
   * 在页面上打印日志
   */
  fun logReq(e: LiftEvent) {
    frame.logEvent(e)
  }
}