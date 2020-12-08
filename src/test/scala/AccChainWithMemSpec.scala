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
import chisel3.iotesters.Driver
import chisel3.iotesters.PeekPokeTester

import org.scalatest.{FlatSpec, Matchers}

class AccWithMemTester(
  dut: AccWithMem[FixedPoint],
  accAddress: AddressSet,
  memAddress: AddressSet,
  beatBytes: Int
) extends PeekPokeTester(dut.module) with AXI4MasterModel  {

  override def memAXI: AXI4Bundle = dut.ioMem.get.getWrappedValue
  poke(dut.outStream.ready, 1)
  
  step(1)
  memWriteWord(memAddress.base, 0x000001)
  memWriteWord(accAddress.base, 32)
  memWriteWord(accAddress.base + beatBytes, 4)
  step(1)
  step(1000)
}


class AccWithMemSpec extends FlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty

  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 0.BP),
    protoAcc = FixedPoint(32.W, 0.BP),
    accDepth = 32
  )
  // addresses have been chosen
  val accAddress      = AddressSet(0x60000100, 0xFF)
  val accQueueBase    = 0x010000
  val memAddress      = AddressSet(0x60003000, 0xF)
  val protoMem        = FixedPoint(16.W, 0.BP)
  val beatBytes        = 4

    
  it should "test accumulator with simple rom" in {
    val lazyDut = LazyModule(new AccWithMem(params, accAddress, accQueueBase, memAddress, protoMem, beatBytes) )
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new AccWithMemTester(lazyDut, accAddress, memAddress, beatBytes)
    } should be (true)
  }
}

    
    
