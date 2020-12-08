// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3._
import chisel3.util._
import chisel3.experimental._

import dsptools._
import dsptools.numbers._
import dspblocks._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

abstract class AccumulatorBlock [T <: Data : Real: BinaryRepresentation, D, U, E, O, B <: Data] (params: AccParams[T], beatBytes: Int) extends LazyModule()(Parameters.empty) with DspBlock[D, U, E, O, B] with HasCSR {

  val masterParams = AXI4StreamMasterParameters(
    name = "AXI4 Stream Accumulator",
    n = 2, // just 2*8 -> 16 bits
    numMasters = 1
  )
  val slaveParams = AXI4StreamSlaveParameters()
  
  val slaveNode  = AXI4StreamSlaveNode(slaveParams)
  val masterNode = AXI4StreamMasterNode(masterParams)
  
  val streamNode = NodeHandle(slaveNode, masterNode)
  
  lazy val module = new LazyModuleImp(this) {
//     val (in, _)  = streamNode.in(0)
//     val (out, _) = streamNode.out(0)
    val (out, edgeOut) = masterNode.out.head
    val (in, edgeIn) = slaveNode.in.head
    
    // Accumulator module
    val acc = Module(new Accumulator(params))

    // Control registers
    val accDepth        = RegInit(params.accDepth.U(log2Ceil(params.accDepth + 1).W))
    val numWin          = RegInit(params.maxNumWindows.U(log2Ceil(params.maxNumWindows + 1).W))

    acc.io.accDepthReg   := accDepth
    acc.io.accWindowsReg := numWin

    // Define register fields
    val fields = Seq(
      // settable registers
      RegField(log2Ceil(params.accDepth + 1), accDepth,
        RegFieldDesc(name = "accDepth", desc = "number of used memory locations inside accumulator")),
      RegField(log2Ceil(params.maxNumWindows + 1), numWin,
        RegFieldDesc(name = "numWin", desc = "number of accumulated fft windows"))
    )

    // Define abstract register map so it can be AXI4, Tilelink, APB, AHB
    regmap(fields.zipWithIndex.map({ case (f, i) => i * beatBytes -> Seq(f)}): _*)
    
    // Connect inputs
    acc.io.in.valid    := in.valid
    // This is question and depends on the block which precede and also depends on pin configurations!!! 
    acc.io.in.bits     := in.bits.data.asTypeOf(params.proto)
    //acc.io.in.bits     := (in.bits.data >> 16).asTypeOf(params.proto)//.asTypeOf(params.proto)
    in.ready           := acc.io.in.ready
    acc.io.lastIn      := in.bits.last
    
    // Connect output
    out.valid        := acc.io.out.valid
    acc.io.out.ready := out.ready
    out.bits.data    := acc.io.out.bits.asUInt
    out.bits.last    := acc.io.lastOut
  }
}

class AXI4AccumulatorBlock[T <: Data : Real: BinaryRepresentation](params: AccParams[T], address: AddressSet, _beatBytes: Int = 4)(implicit p: Parameters) extends AccumulatorBlock[T, AXI4MasterPortParameters, AXI4SlavePortParameters, AXI4EdgeParameters, AXI4EdgeParameters, AXI4Bundle](params, _beatBytes) with AXI4DspBlock with AXI4HasCSR {
  override val mem = Some(AXI4RegisterNode(address = address, beatBytes = _beatBytes))
}

class TLAccumulatorBlock[T <: Data : Real: BinaryRepresentation](val params: AccParams[T], address: AddressSet, beatBytes: Int = 4)(implicit p: Parameters) extends AccumulatorBlock[T, TLClientPortParameters, TLManagerPortParameters, TLEdgeOut, TLEdgeIn, TLBundle](params, beatBytes) with TLDspBlock with TLHasCSR {
  val devname = "TLAccumulatorBlock"
  val devcompat = Seq("accumulator", "radardsp")
  val device = new SimpleDevice(devname, devcompat) {
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping)
    }
  }
  // make diplomatic TL node for regmap
  override val mem = Some(TLRegisterNode(address = Seq(address), device = device, beatBytes = beatBytes))
}

// simple verilog generation test
// object AccBlockApp extends App
// {
//   val params: AccParams[FixedPoint] = AccParams(
//     proto = FixedPoint(16.W, 14.BP),
//     protoAcc = FixedPoint(64.W, 14.BP)
//     // others parameters have default values
//   )
//   implicit val p: Parameters = Parameters.empty
//   val baseAddress = 0x500 
//   
//   val testModule = LazyModule(new AXI4AccumulatorBlock(params, AddressSet(baseAddress + 0x100, 0xFF), _beatBytes = 4) with dspblocks.AXI4StandaloneBlock {
//     override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
//   })
//   
//   chisel3.Driver.execute(args, ()=> testModule.module)
// }
