package accumulator

import chisel3._
import chisel3.util._

import dsptools._
import dsptools.numbers._

import chisel3.internal.requireIsChiselType

case class AccParams[T <: Data: Real](
  proto: T,                       // input/output data type
  protoAcc: T,                    // accumulator data type
  maxNumWindows: Int = 65536,     // defines maximum number of accumulated fft windows
  accDepth: Int = 1024,           // number of memory locations inside accumulator (number of points in FFT)
  bitReversal: Boolean = false    // determine whether output data are in natural or in bit reverse order
  //roundingMode : TrimType = RoundHalfUp // trim type after div 2 operation
) {
  requireIsChiselType(proto,  s"($proto) must be chisel type")
  requireIsChiselType(protoAcc,  s"($protoAcc) must be chisel type")
  
  require(isPow2(maxNumWindows), s"Maximum number of accumulated fft windows shall be power of 2")
}
