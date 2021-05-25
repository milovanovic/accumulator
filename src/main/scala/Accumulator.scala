// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import dsptools._
import dsptools.numbers._

import chisel3.experimental.FixedPoint
import chisel3.internal.requireIsChiselType

import scala.math._

class AccIO[T <: Data: Real] (params: AccParams[T]) extends Bundle {
  val in = Flipped(Decoupled(params.proto))
  val lastIn = Input(Bool())
  
  val accDepthReg = Input(UInt(log2Ceil(params.accDepth + 1).W))
  val accWindowsReg = Input(UInt(log2Ceil(params.maxNumWindows + 1).W))
  
  val out = Decoupled(params.proto)
  val lastOut = Output(Bool())

  override def cloneType: this.type = AccIO(params).asInstanceOf[this.type]
}

object AccIO {
  def apply[T <: Data : Real](params: AccParams[T]): AccIO[T] = new AccIO(params)
}

class Accumulator [T <: Data: Real: BinaryRepresentation] (val params: AccParams[T]) extends Module {
  val io =  IO(AccIO(params))
  
  val mem = SyncReadMem(params.accDepth, params.protoAcc)

  val readEn = io.in.fire()
  val writeEn = RegNext(io.in.fire(), false.B)
  val inDelayed = RegNext(io.in.bits)
  val cntWindows = RegInit(0.U(log2Ceil(params.maxNumWindows).W))
  val cntLoad = RegInit(0.U(log2Ceil(params.accDepth).W))
  val cntAcc = RegInit(0.U(log2Ceil(params.accDepth).W))
  val readVal = Wire(params.protoAcc)
  val writeVal = Wire(params.protoAcc)
  dontTouch(writeVal)

  object State extends ChiselEnum {
    val sIdle, sInitStore, sStoreAndAcc, sLoadAndStore, sLoad = Value
  }
  
  val accWindowsReg = RegInit(params.maxNumWindows.U(log2Ceil(params.maxNumWindows + 1).W))
  val accDepthReg = RegInit(params.accDepth.U(log2Ceil(params.accDepth + 1).W))
  
  val state = RegInit(State.sIdle)
  val statePrev = RegInit(State.sIdle)
  statePrev := state
  val last = RegInit(false.B)

  
  switch (state) {
    //0
    is (State.sIdle) {
      when (io.in.fire()) {
        state := State.sInitStore
        // here define registers
        accWindowsReg := io.accWindowsReg
        accDepthReg := io.accDepthReg
      }
      last := false.B
    }
    //1
    is (State.sInitStore) {
    
      when (cntAcc === (accDepthReg - 1.U) && io.in.fire() && io.lastIn) {
        state := State.sLoad
      }
      .elsewhen (cntAcc === (accDepthReg - 1.U) && io.in.fire) {
        state := State.sStoreAndAcc
      }
    }
    //2
    is (State.sStoreAndAcc) {
      when (io.in.fire() && io.lastIn) {
        state := State.sLoad
        when (~io.out.ready) {
          last := true.B
        }
      }
      .elsewhen (cntWindows === (accWindowsReg - 1.U) && io.in.fire && cntAcc === (accDepthReg - 1.U)) {
        state := State.sLoadAndStore
      }
    }
    //3
    is (State.sLoadAndStore) {
      when (io.in.fire() && io.lastIn) {
        state := State.sLoad
      }
      .elsewhen (cntLoad === (accDepthReg - 1.U) && io.out.fire() && cntAcc < (accDepthReg - 1.U)) {
        state := State.sInitStore
        accWindowsReg := io.accWindowsReg
        accDepthReg := io.accDepthReg
      }
      .elsewhen (cntLoad === (accDepthReg - 1.U) && io.out.fire() && io.in.fire() && cntAcc === (accDepthReg - 1.U)) {
        state := State.sStoreAndAcc
        // this transition is never going to happen if bitReversal is included
      }
    }
    //4
    is (State.sLoad) {
      when (io.lastOut) {
        state := State.sIdle
      }
    }
  }
  val loadingStates = (state === State.sLoadAndStore) || (state === State.sLoad)
  val storingInitStates = (state === State.sInitStore) || (state === State.sLoadAndStore)
  val isTransit = (state === State.sStoreAndAcc && statePrev === State.sInitStore) || (statePrev === State.sLoadAndStore && state === State.sInitStore) || (statePrev === State.sLoadAndStore && state === State.sStoreAndAcc)
  val isOnlyOneFrame = (state === State.sLoad && statePrev === State.sInitStore)
  val doNotScale = RegInit(false.B)
  val isPrevStoreAndAcc = (statePrev === State.sStoreAndAcc) && (state === State.sLoadAndStore)
  
  dontTouch(isTransit)
  val lastSampleInWinStore = cntAcc === (accDepthReg - 1.U) && io.in.fire()
  val lastSampleInWinLoad  = cntLoad === (accDepthReg - 1.U) && io.out.fire()
  
  when(isOnlyOneFrame) {
    doNotScale := true.B
  }
  when (state === State.sIdle) {
    doNotScale := false.B
  }
  
  when ((storingInitStates || isTransit) && ~isPrevStoreAndAcc || isOnlyOneFrame) {
    writeVal := inDelayed
  }
  .otherwise {
    writeVal := inDelayed + readVal
  }
  when (io.in.fire()) {
    cntAcc := cntAcc + 1.U
  }
  
  when (loadingStates && io.out.ready) {
    cntLoad := cntLoad + 1.U
  }
  val cntLoadTmp = RegInit(0.U)
  when (loadingStates && io.out.ready) {
    cntLoad := cntLoad + 1.U
  }
  
  when (cntAcc === (accDepthReg - 1.U) && io.in.fire()) {
    cntWindows := cntWindows + 1.U
  }
  
  when (cntWindows === (accWindowsReg - 1.U) && lastSampleInWinStore)  {
    cntWindows := 0.U
  }
  when (lastSampleInWinStore) {
    cntAcc := 0.U
  }
  when (lastSampleInWinLoad) {
    cntLoad := 0.U
  }
  ////////////////////////// Logic for bit reversal ///////////////////////////////////////////
  
  val log2Size = log2Up(params.accDepth)                   // nummber of stages in the FFT case
  val subSizes = (1 to log2Size).map(d => pow(2, d).toInt) // all possible cases of the FFT stages (assumed is that run time configurability is included)
  val subSizesWire = subSizes.map(e => e.U)
  val bools = subSizesWire.map(e => e === accDepthReg)   // create conditions
  val loadAddressBeforeBitReverse = Mux(last && io.out.ready, cntLoad + 1, cntLoad)
  val cases = bools.zip(1 to log2Size).map { case (bool, numBits) =>
    bool -> { Reverse(loadAddressBeforeBitReverse(numBits-1, 0)) }
  }
  val loadAddress = if (params.bitReversal) MuxCase(0.U(log2Size.W), cases) else loadAddressBeforeBitReverse
  
  /////////////////////////////////////////////////////////////////////////////////////////////
  val readAddress = Mux(loadingStates, loadAddress, cntAcc)
  dontTouch(readAddress)
  
  val rstProtoAcc = Wire(params.protoAcc)
  rstProtoAcc := Real[T].fromDouble(0.0)
  readVal := mem.read(readAddress)
  
  val outData = Wire(params.proto)
  
  //outData := readVal.div2(Log2(io.accWindowsReg)) - div2 can not accept chisel type for shift
  //outData := BinaryRepresentation[T].shr(readVal, Log2(io.accWindowsReg))
  outData := Mux(doNotScale, readVal, BinaryRepresentation[T].shr(readVal, Log2(accWindowsReg)))
  
  val rstProto = Wire(params.proto)
  rstProto := Real[T].fromDouble(0.0)
  
  when (writeEn) {
    mem.write(RegNext(cntAcc), writeVal)
  }
  
  io.out.valid := RegNext(loadingStates) && state =/= State.sIdle
  io.lastOut   := (RegNext(loadAddressBeforeBitReverse) === (accDepthReg - 1.U) &&  io.out.fire()) && state === State.sLoad 
  //(RegNext(cntLoad) === (io.accDepthReg - 1.U) &&  io.out.fire()) && state === State.sLoad
  io.out.bits  := outData 
  
  if (!params.bitReversal) {
    io.in.ready  := (~loadingStates) || io.out.ready && state =/= State.sLoad
  }
  else {
    io.in.ready  := (~loadingStates)
  }
}

object AccApp extends App
{
  val params: AccParams[FixedPoint] = AccParams(
    proto = FixedPoint(16.W, 14.BP),
    protoAcc = FixedPoint(64.W, 14.BP)
    // others parameters have default values
  )
  chisel3.Driver.execute(args,()=>new Accumulator(params))
}
