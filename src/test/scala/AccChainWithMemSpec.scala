// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3.{fromDoubleToLiteral => _, fromIntToBinaryPoint => _, _}
import fixedpoint._
import chiseltest.ChiselScalatestTester
import chiseltest.iotesters.PeekPokeTester
import chiseltest._
import dsptools.numbers._
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import org.scalatest.flatspec.AnyFlatSpec

class AccWithMemTester(
  dut:        AccWithMem[FixedPoint],
  accAddress: AddressSet,
  memAddress: AddressSet,
  beatBytes:  Int)
    extends PeekPokeTester(dut.module)
    with AXI4MasterModel {

  //override def memAXI: AXI4Bundle = dut.ioMem.get //.getWrappedValue
  override def memAXI: AXI4Bundle = dut.ioMem.get.getWrappedValue
  poke(dut.outStream.ready, 1)

  step(1)
  memWriteWord(memAddress.base, 0x000001)
  memWriteWord(accAddress.base, 32)
  memWriteWord(accAddress.base + beatBytes, 4)
  step(1)
  step(1000)
}

class AccWithMemSpec extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p: Parameters = Parameters.empty

  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 0.BP),
    protoAcc = FixedPoint(32.W, 0.BP),
    accDepth = 32
  )
  // addresses have been chosen
  val accAddress = AddressSet(0x60000100, 0xff)
  val accQueueBase = 0x010000
  val memAddress = AddressSet(0x60003000, 0xf)
  val protoMem = FixedPoint(16.W, 0.BP)
  val beatBytes = 4

  val lazyDut = LazyModule(new AccWithMem(params, accAddress, accQueueBase, memAddress, protoMem, beatBytes))
  it should "test accumulator with simple rom" in {
    test(lazyDut.module)
      .withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation))
      .runPeekPoke(_ => new AccWithMemTester(lazyDut, accAddress, memAddress, beatBytes))
  }
}
