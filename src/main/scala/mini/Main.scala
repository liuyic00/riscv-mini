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
  val sv = ChiselStage.emitSystemVerilog(
    new CoreSoc(new Core(MiniConfig().core)),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "--verification-flavor=immediate",
      "--lowering-options=disallowPackedArrays,disallowLocalVariables"
    )
    // "-strip-debug-info"
  )

  /** Match assert/assume like:
    *   `assert(...) else $error`
    * and replace it with:
    *   `assert(...); // else $error`
    * to remove assert-else, which is not supported by sby
    */
  def repalceElseError(sv: String): String = {
    """((?:assert|assume)\(.*?\))(\s*)(else\s*\$error)""".r
      .replaceAllIn(sv, m => s"${m.group(1)};${m.group(2)}// ${m.group(3).replace("$", "\\$")}")
  }

  /** Insert `initial assume(reset);` into the top module
    */
  def insertAssumeReset(topModule: String)(sv: String): String = {
    ("""(?s)module """ + topModule + """\(.*?\);""").r.replaceAllIn(sv, m => (m.matched + "\n  initial assume(reset);"))
  }
  val newSv: String = Seq[String => String](
    repalceElseError,
    insertAssumeReset("CoreSoc")
  ).foldLeft(sv) { (x, f) => f(x) }

  scala.util.Using(new java.io.FileWriter("test_run_dir/emit/CoreSoc.sv")) { f =>
    f.write(newSv)
  }
}
