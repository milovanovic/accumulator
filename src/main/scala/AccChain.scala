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

class AccumulatorChain[T <: Data : Real : BinaryRepresentation]
(
  val accParams: AccParams[T],
  val mapMem: Boolean = true,
  val beatBytes: Int,
  accAddressSet: AddressSet,
 // dspFIFOBaseAddress: BigInt
  dspQueueBaseAddress: BigInt
)(implicit p: Parameters) extends LazyModule {
  val len: Int = accParams.maxNumWindows
  
//   Instantiate lazy modules
 val accumulator = LazyModule(new AXI4AccumulatorBlock(accParams, accAddressSet, beatBytes))
 //val dspFIFO = LazyModule(new AXI4DspFIFO(len, mapMem, dspFIFOBaseAddress))
 val dspQueue = LazyModule(new AXI4DspQueueBlock(accParams.accDepth, dspQueueBaseAddress))
 
  val lhs = AXI4StreamIdentityNode()
  val rhs = AXI4StreamIdentityNode()
  rhs := dspQueue.streamNode := accumulator.streamNode := lhs
  
  // From standalone blocks
  val streamNode = NodeHandle(lhs.inward, rhs.outward)
  
  lazy val blocks = Seq(dspQueue, accumulator)
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)
  for (b <- blocks) {
    b.mem.foreach { _ := bus.node }
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
  val testModule = LazyModule(new AccumulatorChain(params, false, 4, AddressSet(0x001000, 0xFF), 0x010000) {
  
    //def standaloneParams = AXI4BundleParameters(addrBits = 64, dataBits = 64, idBits = 1)
    def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
    val ioMem = mem.map { m => {
      val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

      m :=
        BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
        ioMemNode

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
  })
  chisel3.Driver.execute(Array("--target-dir", "verilog", "--top-name", "AccChainApp"), ()=> testModule.module)
}
