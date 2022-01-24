// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3._
import chisel3.util._
import chisel3.experimental._

import dspblocks._
import dsptools.numbers._

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

trait AXI4AccChainStandaloneBlock extends AXI4AccChainBlock[FixedPoint] {
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))
    m := BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) := ioMemNode
    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}
  
  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    streamNode :=
    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
    ioInNode

  val in = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }
}

class AXI4AccChainBlock[T <: Data : Real: BinaryRepresentation]
(
  accParams: AccParams[T],
  accAddressSet: AddressSet,
  accQueueBase: BigInt,
  beatBytes: Int
)(implicit p: Parameters) extends AccChainBlock[T, AXI4MasterPortParameters, AXI4SlavePortParameters, AXI4EdgeParameters, AXI4EdgeParameters, AXI4Bundle](accParams, accAddressSet, accQueueBase, beatBytes) with AXI4DspBlock {
  val bus = LazyModule(new AXI4Xbar)
  override val mem = Some(bus.node)
  for (b <- blocks) {
    // use default parameters for AXI4Buffer, no flow, no pipe!
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }
}

abstract class AccChainBlock[T <: Data : Real: BinaryRepresentation, D, U, E, O, B <: Data]
(
  accParams: AccParams[T],
  accAddressSet: AddressSet,
  accQueueBase: BigInt,
  beatBytes: Int
)(implicit p: Parameters) extends LazyModule()(Parameters.empty) with DspBlock[D, U, E, O, B]{
  val len: Int = accParams.maxNumWindows
  
  // Instantiate lazy modules
  val accumulator = LazyModule(new AXI4AccumulatorBlock(accParams, accAddressSet, beatBytes))
  val dspQueue = LazyModule(new AXI4DspQueueBlock(accParams.accDepth, accQueueBase))
  
  // Connect nodes
  dspQueue.streamNode := accumulator.streamNode
  
  val streamNode = NodeHandle(accumulator.streamNode, dspQueue.streamNode)

  lazy val blocks = Seq(dspQueue, accumulator)
  
  lazy val module = new LazyModuleImp(this)
}


object AccChainBlockApp extends App
{
  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 14.BP),
    protoAcc = FixedPoint(64.W, 14.BP)
  )
  implicit val p: Parameters = Parameters.empty

  val accModule = LazyModule(new AXI4AccChainBlock(params, AddressSet(0x001000, 0xFF), 0x010000, 4) with AXI4AccChainStandaloneBlock)
  chisel3.Driver.execute(args, ()=> accModule.module)
}
