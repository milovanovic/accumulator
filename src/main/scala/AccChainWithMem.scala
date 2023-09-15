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

class AccWithMem[T <: Data: Real: BinaryRepresentation](
  val accParams:    AccParams[T],
  val accAddress:   AddressSet,
  val accQueueBase: BigInt,
  val memAddress:   AddressSet,
  val protoMem:     T,
  val beatBytes:    Int
)(
  implicit p: Parameters)
    extends LazyModule {

  // Instantiate lazy modules
  val accumulator = LazyModule(new AccumulatorChain(accParams, accAddress, accQueueBase, beatBytes))
  val memForTest = LazyModule(new AXI4MemForTestingAcc(DspComplex(protoMem), memAddress, beatBytes, accParams.accDepth))

  accumulator.streamNode := memForTest.streamNode

  val ioStreamNode = BundleBridgeSink[AXI4StreamBundle]()
  ioStreamNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := accumulator.streamNode
  val outStream = InModuleBody { ioStreamNode.makeIO() }

  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)

  lazy val blocks = Seq(memForTest)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)

  memForTest.mem.get := bus.node
  accumulator.mem.get := bus.node

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

  lazy val module = new LazyModuleImp(this)
}

object AccWithMemApp extends App {
  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 0.BP),
    protoAcc = FixedPoint(32.W, 0.BP)
  )
  val accAddress = AddressSet(0x60000100, 0xff)
  val accQueueBase = 0x010000
  val memAddress = AddressSet(0x60003000, 0xf)
  val protoMem = FixedPoint(16.W, 0.BP)
  val beatBytes = 4

  implicit val p: Parameters = Parameters.empty
  val testModule = LazyModule(new AccWithMem(params, accAddress, accQueueBase, memAddress, protoMem, beatBytes))

  (new ChiselStage)
    .execute(Array("--target-dir", "verilog/AccChainWithMem"), Seq(ChiselGeneratorAnnotation(() => testModule.module)))
}
