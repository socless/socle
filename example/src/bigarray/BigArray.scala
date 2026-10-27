package bigarray

import chisel3._

/** One small stage: a registered increment. */
class Stage extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  io.out := RegNext(io.in + 1.U, 0.U)
}

/** A wide design for the UI's performance guard (tools/ui-smoke/smoke.mjs,
  * the `perf` design): a grid of [[BigArray.Rows]] x [[BigArray.Cols]]
  * stages in the top module, each row a chain, the rows' ends XOR-ed into the
  * output. The schematic shows its 256 boxes at once; the guard checks that
  * what the pointer does costs the same at this size as on a small design. */
class BigArray extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  val grid = Seq.tabulate(BigArray.Rows, BigArray.Cols)((_, _) => Module(new Stage))
  for ((row, r) <- grid.zipWithIndex) {
    row.head.io.in := io.in + r.U
    row.zip(row.tail).foreach { case (a, b) => b.io.in := a.io.out }
  }
  io.out := grid.map(_.last.io.out).reduce(_ ^ _)
}

object BigArray {
  val Rows = 16
  val Cols = 16
}
