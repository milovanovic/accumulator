//// SPDX-License-Identifier: Apache-2.0
//
//package accumulator
//
//import chisel3._
//import chisel3.util._
//import chisel3.experimental._
//import dsptools._
//import dsptools.numbers._
//
//import dspblocks._
//import freechips.rocketchip.amba.axi4._
//import freechips.rocketchip.amba.axi4stream._
//import freechips.rocketchip.config._
//import freechips.rocketchip.diplomacy._
//import freechips.rocketchip.regmapper._
//import freechips.rocketchip.tilelink._
//import chisel3.iotesters.Driver
//import chisel3.iotesters.PeekPokeTester
//import org.scalatest.{FlatSpec, Matchers}
//
//trait AccumulatorChainPins extends AccumulatorChain[FixedPoint] {
//  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
//  val ioMem = mem.map { m =>
//    {
//      val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))
//
//      m :=
//        BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
//        ioMemNode
//
//      val ioMem = InModuleBody { ioMemNode.makeIO() }
//      ioMem
//    }
//  }
//
//  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
//  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
//
//  ioOutNode :=
//    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
//    streamNode :=
//    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
//    // changed to 2
//    //    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 2)) :=
//    ioInNode
//
//  val in = InModuleBody { ioInNode.makeIO() }
//  val out = InModuleBody { ioOutNode.makeIO() }
//}
//
//class AccChainTester(
//  dut:          AccumulatorChain[FixedPoint] with AccumulatorChainPins,
//  beatBytes:    Int = 4,
//  silentFail:   Boolean = false,
//  accAddress:   AddressSet,
//  accQueueBase: BigInt)
//    extends PeekPokeTester(dut.module)
//    with AXI4StreamModel
//    with AXI4MasterModel {
//
//  // Connect AXI4MasterModel to ioMem of DUT
//  override def memAXI: AXI4Bundle = dut.ioMem.get
//  val mod = dut.module
//
//  val accDepthMax = dut.accParams.accDepth
//  val numWinMax = dut.accParams.maxNumWindows
//
//  // Connect AXI4StreamModel to DUT
//  val master = bindMaster(dut.in)
//  val slave = bindSlave(dut.out)
//
//  memWriteWord(accAddress.base, accDepthMax) // set number of fft points
//  memWriteWord(accAddress.base + beatBytes, numWinMax) // set number of accumulated fft windows
//  var dataIn1: Seq[BigInt] = Seq()
//  val dataSet1 = (0 until accDepthMax).map(i => BigInt(i))
//
//  for (i <- 0 until numWinMax) {
//    dataIn1 = dataIn1 ++ dataSet1
//  }
//
//  master.addTransactions(dataIn1.zipWithIndex.map {
//    case (dataSample, idx) =>
//      AXI4StreamTransaction(data = dataSample, last = if (idx == dataIn1.length - 1) true else false)
//  })
//
//  slave.addExpects(dataSet1.map(i => AXI4StreamTransactionExpect(data = Some(i))))
//  stepToCompletion(silentFail = silentFail)
//
//  // run new transaction
//
////    memWriteWord(accAddress.base, accDepthMax/2)           // set number of fft points
////    memWriteWord(accAddress.base + beatBytes, numWinMax/2) // set number of accumulated fft windows
////    var dataIn2 : Seq[BigInt] = Seq()
////    val dataSet2 = (0 until accDepthMax/2).map(i => BigInt(i))
////
////    for (i <- 0 until numWinMax/2) {
////      dataIn2 = dataIn2 ++ dataSet2
////    }
////
////    master.addTransactions(dataIn2.zipWithIndex.map { case (dataSample, idx) => AXI4StreamTransaction(data = dataSample,  last = if (idx == dataIn2.length - 1) true else false) })
////
////    slave.addExpects(dataSet2.map(i => AXI4StreamTransactionExpect(data = Some(i))))
////    stepToCompletion(silentFail = silentFail)
//}
//
//class AccChainSpec extends FlatSpec with Matchers {
//
//  implicit val p: Parameters = Parameters.empty
//
//  val params: AccParams[FixedPoint] = AccParams(
//    proto = FixedPoint(16.W, 10.BP),
//    protoAcc = FixedPoint(64.W, 10.BP),
//    maxNumWindows = 4,
//    accDepth = 8
//  )
//  val accAddress = AddressSet(0x001000, 0xff)
//  val accQueueBase = 0x010000
//  val silentFail = false
//  val beatBytes = 4
//
//  val testModule = LazyModule(new AccumulatorChain(params, accAddress, accQueueBase, 4) with AccumulatorChainPins)
//
//  it should "Test accumulator chain" in {
//    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => testModule.module) { c =>
//      new AccChainTester(
//        dut = testModule,
//        beatBytes = beatBytes,
//        silentFail = silentFail,
//        accAddress = accAddress,
//        accQueueBase = accQueueBase
//      )
//    } should be(true)
//  }
//}
