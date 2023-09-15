// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3.{fromDoubleToLiteral => _, fromIntToBinaryPoint => _, _}
import fixedpoint._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import dsptools.numbers._

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

class AccumulatorChain[T <: Data: Real: BinaryRepresentation](
  val accParams: AccParams[T],
  accAddressSet: AddressSet,
  accQueueBase:  BigInt,
  val beatBytes: Int
)(
  implicit p: Parameters)
    extends LazyModule {
  val len: Int = accParams.maxNumWindows

//   Instantiate lazy modules
  val accumulator = LazyModule(new AXI4AccumulatorBlock(accParams, accAddressSet, beatBytes))
  //val dspFIFO = LazyModule(new AXI4DspFIFO(len, mapMem, dspFIFOBaseAddress))
  val dspQueue = LazyModule(new AXI4DspQueueBlock(accParams.accDepth, accQueueBase))

  //val lhs = AXI4StreamIdentityNode()
  //val rhs = AXI4StreamIdentityNode()
  //rhs := dspQueue.streamNode := accumulator.streamNode := lhs
  dspQueue.streamNode := accumulator.streamNode

  val streamNode = NodeHandle(accumulator.streamNode, dspQueue.streamNode)

  // From standalone blocks
  // val streamNode = NodeHandle(lhs.inward, rhs.outward)

  //:= AXI4Buffer(BufferParams.flow)

  lazy val blocks = Seq(dspQueue, accumulator)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  for (b <- blocks) {
    // use default parameters for AXI4Buffer, no flow, no pipe!
    b.mem.foreach { _ := AXI4Buffer() := bus.node }
  }

  lazy val module = new LazyModuleImp(this)
}

object AccChainApp extends App {

  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 14.BP),
    protoAcc = FixedPoint(64.W, 14.BP)
  )
  implicit val p: Parameters = Parameters.empty

  // can work for beatBytes equal to 4 and beatBytes equal to 8
  //val testModule = LazyModule(new AccumulatorChain(params, false, 8, AddressSet(0x001000, 0xFF), 0x010000) {
  val testModule = LazyModule(new AccumulatorChain(params, AddressSet(0x001000, 0xff), 0x010000, 4) {

    //def standaloneParams = AXI4BundleParameters(addrBits = 64, dataBits = 64, idBits = 1)
    def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
    val ioMem = mem.map { m =>
      {
        val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

        m :=
          BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
          ioMemNode

        val ioMem = InModuleBody { ioMemNode.makeIO() }
        ioMem
      }
    }

    val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
    val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

    ioOutNode :=
      AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
      streamNode :=
      // previous version!
      //   BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
      BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 2)) :=
      ioInNode

    val in = InModuleBody { ioInNode.makeIO() }
    val out = InModuleBody { ioOutNode.makeIO() }
  })
  (new ChiselStage)
    .execute(Array("--target-dir", "verilog/AccumulatorChain"), Seq(ChiselGeneratorAnnotation(() => testModule.module)))

}
