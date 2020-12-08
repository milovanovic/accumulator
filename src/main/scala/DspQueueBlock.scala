// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3._
import chisel3.util._

import dspblocks._ // included because of DspQueue

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

trait DspQueueBlockImp [D, U, EO, EI, B <: Data] extends LazyModuleImp with HasRegMap {
  def outer: DspQueue[D, U, EO, EI, B] = wrapper.asInstanceOf[DspQueue[D, U, EO, EI, B]]
  val streamNode = outer.streamNode
  val depth      = outer.depth

  val (streamIn, streamEdgeIn)   = streamNode.in.head
  val (streamOut, streamEdgeOut) = streamNode.out.head


  val queuedStream = Queue(streamIn, entries = depth)
  streamOut <> queuedStream

  val queueEntries = RegInit(UInt(log2Ceil(depth + 1).W), 0.U)
  queueEntries := queueEntries + streamIn.fire() - streamOut.fire()

  val queueThreshold = WireInit(UInt(32.W), depth.U)
  val queueFilling = queueEntries >= queueThreshold
  val queueFull    = queueEntries >= depth.U

  override val interrupts: Vec[Bool] = VecInit(queueFilling, queueFull)
  regmap(0 ->
    Seq(RegField(32, queueThreshold,
      RegFieldDesc("queueThreshold", "Threshold for number of elements to throw interrupt"))))
}

class AXI4DspQueueBlock(val depth: Int, val baseAddr: BigInt = 0, concurrency: Int = 4)(implicit p: Parameters)
  extends AXI4RegisterRouter(baseAddr, beatBytes = 4, concurrency = concurrency)(
    new AXI4RegBundle(depth, _))(
    new AXI4RegModule(depth, _, _)
      with DspQueueBlockImp[AXI4MasterPortParameters, AXI4SlavePortParameters, AXI4EdgeParameters, AXI4EdgeParameters, AXI4Bundle]
  ) with DspQueue[AXI4MasterPortParameters, AXI4SlavePortParameters, AXI4EdgeParameters, AXI4EdgeParameters, AXI4Bundle]
    with AXI4DspBlock {
  val mem = Some(node)
}
