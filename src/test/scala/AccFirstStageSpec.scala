package accumulator

import chisel3._
import chisel3.util._
import chisel3.experimental._

import dsptools.DspTester
import dsptools.numbers._

import org.scalatest.{FlatSpec, Matchers}
import scala.util.{Random}
import scala.math.pow

class AccumulatorSpec extends FlatSpec with Matchers {

  def seqToString(c: Seq[Double]): String =
    "[" + c.mkString(", ") + "]"

  def runTest[T <: Data : Real : BinaryRepresentation](signal: Seq[Double], params: AccParams[T], numWind: Int, accDepth: Int, tol: Int = 3): Seq[Double] = {
    require(accDepth <= params.accDepth)
    require(numWind <= params.maxNumWindows, "Number of accumulated fft windows should be less or equal to parametar maxNumWindows")
    require(isPow2(numWind), s"Maximum number of accumulated fft windows shall be power of 2")
    
    var output = Seq[Double]()
    var cntOut = 0
    dsptools.Driver.execute(() => new Accumulator(params), Array("-tbn", "verilator", "-dtinv")) {
      c => new DspTester(c) {
        updatableSubVerbose.withValue(true) {
          // initial config
          updatableDspVerbose.withValue(true) {
            poke(c.io.in.valid, 0)
            poke(c.io.out.ready, 0)

            poke(c.io.lastIn, 0)
            poke(c.io.accDepthReg, accDepth)
            poke(c.io.accWindowsReg, numWind)
          }
        }
        
      //  poke(c.io.in.valid, 1)
        poke(c.io.out.ready, 1)
        
        for (iWin <- (0 until numWind)) {
          for ((in, idx) <- signal.zipWithIndex) {
            poke(c.io.in.valid, 0)
      //      poke(c.io.out.ready, 1)
      // works good with and without gaps
            step(5)
            poke(c.io.in.valid, 1)
            
            poke(c.io.in.bits, in)
            if (iWin == (numWind - 1) && idx == (accDepth - 1)) {
              poke(c.io.lastIn, 1)
            }
            step(1)
            if (peek(c.io.out.valid)) {
              output = output :+ peek(c.io.out.bits)
              params.proto match {
                case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
                case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              }
              cntOut += 1
            }
          }
        }
        poke(c.io.lastIn, 0)
        //step(params.accDepth * 3)
        
        while (cntOut < accDepth) {
          if (peek(c.io.out.valid)) {
            params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
            }
            output = output :+ peek(c.io.out.bits)
            cntOut += 1
          }
          step(1)
        }
        cntOut = 0
        reset(1)
        /////////////////////////////////////////////////////
        // check lastIn assertion after 2*numWind
        // check transitions - no gaps
        // - 0 -> 1
        // - 1 -> 2
        // - 2 -> 3
        // - 3 -> 2 !
        // - 2 -> 2
        // - 4 -> 0
        // check transitions - with gaps
        // - 0 -> 1
        // - 1 -> 2
        // - 2 -> 3
        // - 3 -> 1
        // - 1 -> 2
        // - 2 -> 4
        // - 4 -> 0
        /////////////////////////////////////////////////////
        
        // config
        updatableSubVerbose.withValue(true) {
          updatableDspVerbose.withValue(true) {
            poke(c.io.in.valid, 0)
            poke(c.io.out.ready, 0)

            poke(c.io.lastIn, 0)
            poke(c.io.accDepthReg, accDepth)
            poke(c.io.accWindowsReg, numWind)
          }
        }
        
        poke(c.io.out.ready, 1)
        
        for (iWin <- (0 until 2*numWind)) {
          for ((in, idx) <- signal.zipWithIndex) {
          // works good with and without gaps!
          // for testing stream with gaps uncomment code below
//             poke(c.io.in.valid, 0)
//             // poke(c.io.out.ready, 1)
//             ////////////////////////////////////////////////////////////////
//             ///// This check here is neccessary when gaps are included /////
//             /////////// ////////////////////////////////////////////////////
//             
//             for (delayTime <- 0 to 5) {
//               step(1)
//               if (peek(c.io.out.valid)) {
//                 output = output :+ peek(c.io.out.bits)
//                 params.proto match {
//                   case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
//                   case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
//                 }
//                 cntOut += 1
//               }
//             }
            
            ////////////////////////////////////////////////////////////////
            poke(c.io.in.valid, 1)
            
            poke(c.io.in.bits, in)
            if (iWin == (2*numWind - 1) && idx == (accDepth - 1)) {
              poke(c.io.lastIn, 1)
            }
            step(1)
            if (cntOut == accDepth) {
              cntOut = 0
            }
            if (peek(c.io.out.valid)) {
              output = output :+ peek(c.io.out.bits)
              params.proto match {
                case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
                case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              }
              cntOut += 1
            }
          }
        }
        poke(c.io.lastIn, 0)
        //step(params.accDepth * 3)
        
        // here reset again cntOut
        while (cntOut < accDepth) {
          if (cntOut == accDepth) {
            cntOut = 0
          }
          if (peek(c.io.out.valid)) {
            params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
            }
            output = output :+ peek(c.io.out.bits)
            cntOut += 1
          }
          step(1)
        }
        //////////////////////////////////////////////////////////
        //////////// test valid out ready signal with gaps ///////
        //////////////////////////////////////////////////////////
        reset(1)
        cntOut = 0
        updatableSubVerbose.withValue(true) {
          // initial config
          updatableDspVerbose.withValue(true) {
            poke(c.io.in.valid, 0)
            poke(c.io.out.ready, 0)

            poke(c.io.lastIn, 0)
            poke(c.io.accDepthReg, accDepth)
            poke(c.io.accWindowsReg, numWind)
          }
        }
        for (iWin <- (0 until 2*numWind)) {
          for ((in, idx) <- signal.zipWithIndex) {
            poke(c.io.out.ready, 0)
            poke(c.io.in.valid, 0)
            step(4)
            poke(c.io.out.ready, 1)
            poke(c.io.in.valid, 1)
            
            poke(c.io.in.bits, in)
            if (iWin == (2*numWind - 1) && idx == (accDepth - 1)) {
              poke(c.io.lastIn, 1)
            }
            step(1)
            if (cntOut == accDepth) {
              cntOut = 0
            }
            if (peek(c.io.out.valid)) {
              output = output :+ peek(c.io.out.bits)
              params.proto match {
                case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
                case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              }
              cntOut += 1
            }
          }
        }
        poke(c.io.lastIn, 0)
        while (cntOut < accDepth) {
          if (cntOut == accDepth) {
            cntOut = 0
          }
          if (peek(c.io.out.valid)) {
            params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
            }
            output = output :+ peek(c.io.out.bits)
            cntOut += 1
          }
          step(1)
        }

        ////////////////////////////////////////////////////////
        ////// test inital storing!                       //////
        ////// store data until valid out becomes active  //////
        ////////////////////////////////////////////////////////
        
        reset(1)
        cntOut = 0
        updatableSubVerbose.withValue(true) {
          updatableDspVerbose.withValue(true) {
            poke(c.io.in.valid, 0)
            poke(c.io.out.ready, 0)

            poke(c.io.lastIn, 0)
            poke(c.io.accDepthReg, accDepth)
            poke(c.io.accWindowsReg, numWind)
          }
        }
        
        poke(c.io.in.valid, 1)
        // check initial storing - enable data stream even though output side is not ready to accept data
        //step(params.accDepth)
        for (iWin <- (0 until numWind)) {
          for ((in, idx) <- signal.zipWithIndex) {
            poke(c.io.in.bits, in)
            if (iWin == (numWind - 1) && idx == (accDepth - 1)) {
              poke(c.io.lastIn, 1)
            }
            step(1)
          }
        }
        poke(c.io.lastIn, 0)
        step(params.accDepth)
        poke(c.io.out.ready, 1)
        while (cntOut < accDepth) {
          if (peek(c.io.out.valid)) {
            params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
              case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signal(cntOut)) }
            }
            output = output :+ peek(c.io.out.bits)
            cntOut += 1
          }
          step(1)
        }
        step(2*params.accDepth)
      }
    }
    output
  }
  // add DspReal tests
  val proto  = FixedPoint(16.W, 14.BP)
  val protoAcc = FixedPoint(64.W, 14.BP)
  val numWin = 2
  val accDepth = 8
  Random.setSeed(11110L)
  // checked only for small test vectors
  val accParams = AccParams(proto = proto, protoAcc = protoAcc, maxNumWindows = 4, accDepth = 16)
  
  behavior of "First stage of the accumulator"
  
  // test signal only positive values
  it should f"generate simple test signal and execute runTest function" in {
    val testSignal = Seq.fill(accDepth)((Random.nextDouble()) * ((1 << proto.getWidth - proto.binaryPoint.get-1)))
    val output = runTest(testSignal, accParams, numWin, accDepth, 2)

    println(s"Input is:")
    println(seqToString(testSignal))//println(testSignal.toString)
    println(s"Output (length ${output.length}) is:")
    println(seqToString(output))// should be (true)
  }
  
  for (accDepthCompile <- Seq(4, 16, 32)) {
    for (numWinCompile <- Seq(2, 4, 16)) {
      for (accDepthReg <- (1 to log2Ceil(accDepthCompile)).map(depth => pow(2,depth).toInt)) {
        for (numWinReg <- (1 to log2Ceil(numWinCompile)).map(numWin => pow(2,numWin).toInt)) {
          it should f"work for FixedPoint, accumulator depth $accDepthCompile, number of windows is $numWinCompile, accDepthReg value is $accDepthReg, numWinReg is $numWinReg" in {
            val params = AccParams(
              proto = proto,
              protoAcc = protoAcc,
              maxNumWindows = numWinCompile,
              accDepth = accDepthCompile
            )
            val testSignal = Seq.fill(accDepthReg)((Random.nextDouble()) * ((1 << proto.getWidth - proto.binaryPoint.get-1)))
            val output = runTest(testSignal, params, numWinReg, accDepthReg) //should be (true)
          }
        }
      }
    }
  }
}
