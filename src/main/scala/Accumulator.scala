// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import dsptools._
import dsptools.numbers._

import chisel3.experimental.FixedPoint
import chisel3.internal.requireIsChiselType

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
  val state = RegInit(State.sIdle)
  val statePrev = RegInit(State.sIdle)
  statePrev := state
  val last = RegInit(false.B)

  
  switch (state) {
    //0
    is (State.sIdle) {
      when (io.in.fire()) {
        state := State.sInitStore
      }
      last := false.B
    }
    //1
    is (State.sInitStore) {
      when (cntAcc === (io.accDepthReg - 1.U) && io.in.fire() && io.lastIn) {
        state := State.sLoad
      }
      .elsewhen (cntAcc === (io.accDepthReg - 1.U) && io.in.fire) {
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
      .elsewhen (cntWindows === (io.accWindowsReg - 1.U) && io.in.fire && cntAcc === (io.accDepthReg - 1.U)) {
        state := State.sLoadAndStore
      }
    }
    // this state should take care also about init state
    //3
    is (State.sLoadAndStore) {
      when (io.in.fire() && io.lastIn) {
        state := State.sLoad
      }
     .elsewhen (cntLoad === (io.accDepthReg - 1.U) && io.out.fire() && cntAcc < (io.accDepthReg - 1.U)) {
       state := State.sInitStore
     }
     .elsewhen (cntLoad === (io.accDepthReg - 1.U) && io.out.fire() && io.in.fire() && cntAcc === (io.accDepthReg - 1.U)) {
        state := State.sStoreAndAcc
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
  val isPrevStoreAndAcc = (statePrev === State.sStoreAndAcc) && (state === State.sLoadAndStore)
  
  dontTouch(isTransit)
  val lastSampleInWinStore = cntAcc === (io.accDepthReg - 1.U) && io.in.fire()
  val lastSampleInWinLoad  = cntLoad === (io.accDepthReg - 1.U) && io.out.fire()
  
  when ((storingInitStates || isTransit) && ~isPrevStoreAndAcc) {
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
  
  when (cntAcc === (io.accDepthReg - 1.U) && io.in.fire()) {
    cntWindows := cntWindows + 1.U
  }
  
  when (cntWindows === (io.accWindowsReg - 1.U) && lastSampleInWinStore)  {
    cntWindows := 0.U
  }
  when (lastSampleInWinStore) {
    cntAcc := 0.U
  }
  when (lastSampleInWinLoad) {
    cntLoad := 0.U
  }
  
  val readAddress = Mux(loadingStates, cntLoad, cntAcc)
  dontTouch(readAddress)
  
  val addressReadTmp = Wire(cntLoad.cloneType)
  
  when (last && io.out.ready) {
    addressReadTmp := cntLoad + 1.U
  }
  .otherwise {
    addressReadTmp := readAddress
  }
  dontTouch(addressReadTmp)
  
  val rstProtoAcc = Wire(params.protoAcc)
  rstProtoAcc := Real[T].fromDouble(0.0)
  readVal := mem.read(addressReadTmp)
  val outData = Wire(params.proto)
  
  //outData := readVal.div2(Log2(io.accWindowsReg)) - div2 can not accept chisel type for shift
  outData := BinaryRepresentation[T].shr(readVal, Log2(io.accWindowsReg))
  
  val rstProto = Wire(params.proto)
  rstProto := Real[T].fromDouble(0.0)
  
  when (writeEn) {
    mem.write(RegNext(cntAcc), writeVal)
  }
  
  io.out.valid := RegNext(loadingStates) && state =/= State.sIdle
  io.lastOut   := (RegNext(cntLoad) === (io.accDepthReg - 1.U) &&  io.out.fire()) && state === State.sLoad
  io.out.bits  := outData 
  io.in.ready  := (~loadingStates) || io.out.ready && state =/= State.sLoad
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
