// See LICENSE for license details.

package mini

import circt.stage.ChiselStage
object Main extends App {
  val config = MiniConfig()
  ChiselStage.emitSystemVerilogFile(
    new Tile(
      coreParams = config.core,
      nastiParams = config.nasti,
      cacheParams = config.cache
    ),
    args
  )
}

object Emitter extends App {
  (new chisel3.stage.ChiselStage)
    .emitSystemVerilog(new CoreSoc(new Core(MiniConfig().core)), Array("--target-dir", "test_run_dir/emit"))
}