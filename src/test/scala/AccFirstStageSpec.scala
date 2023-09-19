// SPDX-License-Identifier: Apache-2.0

package accumulator

import chisel3.{fromDoubleToLiteral => _, fromIntToBinaryPoint => _, _}
import chisel3.util._
import fixedpoint._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._
import scala.util.Random
import scala.math.pow

class AccFirstStageSpec extends AnyFlatSpec with ChiselScalatestTester {

  // add DspReal tests
  val proto = FixedPoint(16.W, 14.BP)
  val protoAcc = FixedPoint(64.W, 14.BP)
  val numWin = 2
  val accDepth = 8
  Random.setSeed(11110L)

  behavior.of("First stage of the accumulator")

  for (accDepthCompile <- Seq(4, 16, 32)) {
    //for (accDepthCompile <- Seq(4)) {
    for (numWinCompile <- Seq(2, 4, 16)) {
      //for (numWinCompile <- Seq(4)) {
      for (accDepthReg <- (1 to log2Ceil(accDepthCompile)).map(depth => pow(2, depth).toInt)) {
        for (numWinReg <- (0 to log2Ceil(numWinCompile)).map(numWin => pow(2, numWin).toInt)) {
          it should f"work for FixedPoint, accumulator depth $accDepthCompile, number of windows is $numWinCompile, accDepthReg value is $accDepthReg, numWinReg is $numWinReg" in {
            val params = AccParams(
              proto = proto,
              protoAcc = protoAcc,
              bitReversal = false,
              maxNumWindows = numWinCompile,
              accDepth = accDepthCompile
            )
            val testSignal =
              Seq.fill(accDepthReg)((Random.nextDouble()) * ((1 << proto.getWidth - proto.binaryPoint.get - 1)))
            /*val output =
              FixedAccumulatorTester(testSignal, params, numWinReg, accDepthReg, backend = "treadle") should be(true)*/
            test(new Accumulator(params))
              .withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation))
              .runPeekPoke(new AccumulatorTester(_) {
                runTest(testSignal, params, numWinReg, accDepthReg)
              })
          }
        }
      }
    }
  }

  for (accDepthCompile <- Seq(4, 16, 32)) {
    for (numWinCompile <- Seq(2, 4, 16)) {
      for (accDepthReg <- (1 to log2Ceil(accDepthCompile)).map(depth => pow(2, depth).toInt)) {
        for (numWinReg <- (1 to log2Ceil(numWinCompile)).map(numWin => pow(2, numWin).toInt)) {
          it should f"work for FixedPoint, accumulator depth $accDepthCompile, number of windows is $numWinCompile, accDepthReg value is $accDepthReg, numWinReg is $numWinReg and included bitReversal parameter" in {
            val params = AccParams(
              proto = proto,
              protoAcc = protoAcc,
              bitReversal = true,
              maxNumWindows = numWinCompile,
              accDepth = accDepthCompile
            )
            val testSignal =
              Seq.fill(accDepthReg)((Random.nextDouble()) * ((1 << proto.getWidth - proto.binaryPoint.get - 1)))
            test(new Accumulator(params))
              .withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation))
              .runPeekPoke(new AccumulatorTester(_) {
                runTestWithBitReversal(testSignal, params, numWinReg, accDepthReg)
              })
            // works good for both backends but treadle is much faster!
            /*val output =
              FixedAccumulatorTester(testSignal, params, numWinReg, accDepthReg, backend = "treadle") should be(true)*/
            //val output = FixedAccumulatorTester(testSignal, params, numWinReg, accDepthReg, backend = "verilator") should be (true)
          }
        }
      }
    }
  }
}
