package accumulator

import breeze.numerics.abs
import chisel3.{Data, SInt, UInt}
import chisel3.util._
import chiseltest.iotesters.PeekPokeTester
import dsptools.misc.PeekPokeDspExtensions
import dsptools.numbers._

class AccumulatorTester[T <: chisel3.Data](c: Accumulator[T]) extends PeekPokeTester(c) with PeekPokeDspExtensions {

  def seqToString(c: Seq[Double]): String =
    "[" + c.mkString(", ") + "]"

///////////////////////////////////////////////////////////////////////////////////////////
////////////////// Functions used only when bitReversal parameter is on ///////////////////
///////////////////////////////////////////////////////////////////////////////////////////
  /**
    * Returns bit reversed index
    */
  def bit_reverse(in: Int, width: Int): Int = {
    import scala.math.pow
    var test = in
    var out = 0
    for (i <- 0 until width) {
      if (test / pow(2, width - i - 1) >= 1) {
        out += pow(2, i).toInt
        test -= pow(2, width - i - 1).toInt
      }
    }
    out
  }

  /**
    * Reordering data
    */
  def bitrevorder_data(testSignal: Seq[Double]): Seq[Double] = {
    val seqLength = testSignal.size
    val new_indices = (0 until seqLength).map(x => bit_reverse(x, log2Up(seqLength)))
    new_indices.map(x => testSignal(x))
  }
  ///////////////////////////////////////////////////////////////////////////////////////////

  def runTest[T <: Data: Real: BinaryRepresentation](
    signal:   Seq[Double],
    params:   AccParams[T],
    numWind:  Int,
    accDepth: Int,
    tol:      Double = 0.5
  ) {
    require(accDepth <= params.accDepth)
    require(
      numWind <= params.maxNumWindows,
      "Number of accumulated fft windows should be less or equal to parametar maxNumWindows"
    )
    require(isPow2(numWind), s"Maximum number of accumulated fft windows shall be power of 2")

    var output = Seq[Double]()
    val signalRef = signal
    var cntOut = 0
    //updatableSubVerbose.withValue(true) {
    // initial config
    //  updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
    //updatableDspVerbose.withValue(false) {
    //  poke(c.io.in.valid, 1)
    poke(c.io.out.ready, 1)

    for (iWin <- (0 until numWind)) {
      for ((in, idx) <- signal.zipWithIndex) {
        poke(c.io.in.valid, 0)
        //      works good with and without gaps
        //      poke(c.io.out.ready, 1)
        step(5)
        poke(c.io.in.valid, 1)

        poke(c.io.in.bits, in)
        if (iWin == (numWind - 1) && idx == (accDepth - 1)) {
          poke(c.io.lastIn, 1)
        }
        step(1)
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
          }
          cntOut += 1
        }
      }
    }
    poke(c.io.lastIn, 0)

    while (cntOut < accDepth) {
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    // updatableSubVerbose.withValue(true) {
    //  updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
    poke(c.io.out.ready, 1)

    for (iWin <- (0 until 2 * numWind)) {
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
        if (iWin == (2 * numWind - 1) && idx == (accDepth - 1)) {
          poke(c.io.lastIn, 1)
        }
        step(1)
        if (cntOut == accDepth) {
          cntOut = 0
        }
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    //updatableSubVerbose.withValue(true) {
    // initial config
    //  updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
    for (iWin <- (0 until 2 * numWind)) {
      for ((in, idx) <- signal.zipWithIndex) {
        poke(c.io.out.ready, 0)
        poke(c.io.in.valid, 0)
        step(4)
        poke(c.io.out.ready, 1)
        poke(c.io.in.valid, 1)

        poke(c.io.in.bits, in)
        if (iWin == (2 * numWind - 1) && idx == (accDepth - 1)) {
          poke(c.io.lastIn, 1)
        }
        step(1)
        if (cntOut == accDepth) {
          cntOut = 0
        }
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    //updatableSubVerbose.withValue(true) {
    //  updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    // }
    //}

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
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
        }
        output = output :+ peek(c.io.out.bits)
        cntOut += 1
      }
      step(1)
    }
    step(2 * params.accDepth)

    //////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////// test accumulator window size switch ////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////

    reset(1)

    cntOut = 0
    //updatableSubVerbose.withValue(true) {
    //  updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 1)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
    poke(c.io.out.ready, 1)
    poke(c.io.in.valid, 1)

    for (iWin <- (0 until 2 * numWind)) {
      for ((in, idx) <- signal.zipWithIndex) {
        poke(c.io.in.bits, in)
        if (iWin == 1) {
          if (idx < accDepth / 2) {
            poke(c.io.accWindowsReg, 1) // to test numWind = 1
            //poke(c.io.accWindowsReg, numWind/2) // to test numWind > 1
          }
        }
        step(1)
      }
    }
    // continue then with numWind
    cntOut = 0
//      updatableSubVerbose.withValue(true) {
//        updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 1) // previous 0

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
//        }
//      }

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
    //step(params.accDepth)
    poke(c.io.out.ready, 1)
//       while (cntOut < accDepth) {
//         if (peek(c.io.out.valid)) {
//           params.proto match {
//             case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
//             case _ =>  fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
//           }
//           output = output :+ peek(c.io.out.bits)
//           cntOut += 1
//         }
//         step(1)
//       }
    step(4 * params.accDepth)
  }
//  }

  def runTestWithBitReversal[T <: Data: Real: BinaryRepresentation](
    signal:   Seq[Double],
    params:   AccParams[T],
    numWind:  Int,
    accDepth: Int,
    tol:      Double = 0.5
  ) = {
    require(accDepth <= params.accDepth)
    require(
      numWind <= params.maxNumWindows,
      "Number of accumulated fft windows should be less or equal to parametar maxNumWindows"
    )
    require(isPow2(numWind), s"Maximum number of accumulated fft windows shall be power of 2")
    require(params.bitReversal)

    var output = Seq[Double]()
    val signalRef = bitrevorder_data(signal)
    var cntOut = 0

    //  updatableSubVerbose.withValue(true) {
    // initial config
    //    updatableDspVerbose.withValue(true) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
    // updatableDspVerbose.withValue(false) {
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
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
          }
          cntOut += 1
        }
      }
    }
    poke(c.io.lastIn, 0)

    while (cntOut < accDepth) {
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    // - 3 -> 1 ! transition from 3 -> 2 can not occur!
    // - 1 -> 2
    // - 2 -> 4
    // - 4 -> 0
    /////////////////////////////////////////////////////

    // config
    //updatableSubVerbose.withValue(true) {
    //updatableDspVerbose.withValue(true) {
    //  updatableDspVerbose.withValue(false) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}

    poke(c.io.out.ready, 1)
    poke(c.io.in.valid, 1)

    var inputIdx = 0

    for (iWin <- (0 until 2 * numWind)) {
      while (inputIdx < accDepth) {
        if (peek(c.io.in.ready) == 1) {
          poke(c.io.in.bits, signal(inputIdx))
          if (iWin == (2 * numWind - 1) && inputIdx == (accDepth - 1)) {
            poke(c.io.lastIn, 1)
          }
          inputIdx = inputIdx + 1
        }
        //  step(1)
        if (cntOut == accDepth) {
          cntOut = 0
        }
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
          }
          cntOut += 1
        }
        step(1)
      }
      if (inputIdx == accDepth) {
        inputIdx = 0
      }
    }
    poke(c.io.lastIn, 0)

    // here reset again cntOut
    while (cntOut < accDepth) {
      if (cntOut == accDepth) {
        cntOut = 0
      }
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    //updatableSubVerbose.withValue(true) {
    // initial config
    //  updatableDspVerbose.withValue(false) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}

    for (iWin <- (0 until 2 * numWind)) {
      while (inputIdx < accDepth) {
        poke(c.io.out.ready, 0)
        poke(c.io.in.valid, 0)
        step(4)
        poke(c.io.out.ready, 1)
        poke(c.io.in.valid, 1)

        if (peek(c.io.in.ready) == 1) {
          poke(c.io.in.bits, signal(inputIdx))
          if (iWin == (2 * numWind - 1) && inputIdx == (accDepth - 1)) {
            poke(c.io.lastIn, 1)
          }
          inputIdx = inputIdx + 1
        }
        //  step(1)
        if (cntOut == accDepth) {
          cntOut = 0
        }
        if (peek(c.io.out.valid) == 1) {
          output = output :+ peek(c.io.out.bits)
          /*params.proto match {
              case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
              case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            }*/
          params.proto match {
            case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
            case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
            case _ =>
              assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
          }
          cntOut += 1
        }
        step(1)
      }
      if (inputIdx == accDepth) {
        inputIdx = 0
      }
    }
    poke(c.io.lastIn, 0)
    while (cntOut < accDepth) {
      if (cntOut == accDepth) {
        cntOut = 0
      }
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
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
    //updatableSubVerbose.withValue(true) {
    //  updatableDspVerbose.withValue(false) {
    poke(c.io.in.valid, 0)
    poke(c.io.out.ready, 0)

    poke(c.io.lastIn, 0)
    poke(c.io.accDepthReg, accDepth)
    poke(c.io.accWindowsReg, numWind)
    //  }
    //}
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
      if (peek(c.io.out.valid) == 1) {
        /*params.proto match {
            case dspR: DspReal => realTolDecPts.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
            case _ => fixTolLSBs.withValue(tol) { expect(c.io.out.bits, signalRef(cntOut)) }
          }*/
        params.proto match {
          case uInt: UInt => expect(c.io.out.bits, signalRef(cntOut))
          case sInt: SInt => expect(c.io.out.bits, signalRef(cntOut))
          case _ =>
            assert(abs(signalRef(cntOut) - peek(c.io.out.bits)) <= tol, "Mismatch!!!")
        }
        output = output :+ peek(c.io.out.bits)
        cntOut += 1
      }
      step(1)
    }
    step(2 * params.accDepth)
    // }
  }
}

//object FixedAccumulatorTester {
//  def apply(
//    signal:   Seq[Double],
//    params:   AccParams[FixedPoint],
//    numWind:  Int,
//    accDepth: Int,
//    tol:      Int = 3,
//    backend:  String = "verilator"
//  ): Boolean = {
//    chisel3.iotesters.Driver.execute(Array("-tbn", backend), () => new Accumulator(params)) { c =>
//      new AccumulatorTester(c) {
//        if (params.bitReversal == true) {
//          runTestWithBitReversal(signal, params, numWind, accDepth, tol)
//        } else {
//          runTest(signal, params, numWind, accDepth, tol)
//        }
//      }
//    }
//  }
//}
