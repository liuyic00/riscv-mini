package formal

import chisel3._
import chisel3.testers._
import chisel3.util.experimental.loadMemoryFromFileInline
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest.formal._
import mini.MiniConfig
import mini.TestUtils
import mini.Core
import mini.CoreSoc

class CoreTester(core: => Core, benchmark: String, trace: Boolean = false) extends BasicTester {
  // val originalHexFile = os.rel / "tests" / f"$benchmark.hex"
  val resizedHexFile = os.rel / "tests" / "32" / f"$benchmark.hex"
  // TestUtils.resizeHexFile(os.pwd / originalHexFile, os.pwd / resizedHexFile, 32) // we have 32 bits per memory entry

  val dut = Module(core)
  val xlen = dut.conf.xlen
  dut.io.host.fromhost.bits := DontCare
  dut.io.host.fromhost.valid := false.B

  val imem = Mem(1 << 20, UInt(xlen.W))
  loadMemoryFromFileInline(imem, resizedHexFile.toString())
  val dmem = Mem(1 << 20, UInt(xlen.W))
  loadMemoryFromFileInline(dmem, resizedHexFile.toString())

  val cycle = RegInit(0.U(32.W))
  val iaddr = dut.io.icache.req.bits.addr / (xlen / 8).U
  val daddr = dut.io.dcache.req.bits.addr / (xlen / 8).U
  val write = (0 until (xlen / 8)).foldLeft(0.U(xlen.W)) { (write, i) =>
    write |
      (Mux(
        (dut.io.dcache.req.valid && dut.io.dcache.req.bits.mask(i)).asBool,
        dut.io.dcache.req.bits.data,
        dmem(daddr)
      )(8 * (i + 1) - 1, 8 * i) << (8 * i).U).asUInt
  }
  dut.io.icache.resp.valid := !reset.asBool
  dut.io.dcache.resp.valid := !reset.asBool
  dut.io.icache.resp.bits.data := RegNext(imem(iaddr))
  dut.io.dcache.resp.bits.data := RegNext(dmem(daddr))

  when(dut.io.icache.req.valid) {
    if (trace) printf("INST[%x] => %x\n", iaddr * (xlen / 8).U, imem(iaddr))
  }
  when(dut.io.dcache.req.valid) {
    when(dut.io.dcache.req.bits.mask.orR) {
      dmem(daddr) := write
      if (trace) printf("MEM[%x] <= %x\n", daddr * (xlen / 8).U, write)
    }.otherwise {
      if (trace) printf("MEM[%x] => %x\n", daddr * (xlen / 8).U, dmem(daddr))
    }
  }
  cycle := cycle + 1.U
  when(dut.io.host.tohost =/= 0.U) {
    printf("cycles: %d\n", cycle)
    assert((dut.io.host.tohost >> 1.U).asUInt === 0.U, "* tohost: %d *\n", dut.io.host.tohost)
    stop()
  }
}

object DefaultCoreConfig {
  def apply() = MiniConfig().core
}

class RISCVMiniFormalSpec extends AnyFlatSpec with Formal with ChiselScalatestTester {
  behavior of "RISCVMiniFormal_E5"
//   it should "pass simpletest" in {
//     test(new CoreTester(new Core(DefaultCoreConfig()), "rv32ui-p-simple")).runUntilStop(10)
//   }
//   it should "generate Verilog" in {
//     (new chisel3.stage.ChiselStage)
//       .emitSystemVerilog(new Core(DefaultCoreConfig()), Array("--target-dir", "test_run_dir/Elaborate_RVFI"))
//   }
// it should "generate SoCVerilog" in {
//   (new chisel3.stage.ChiselStage)
//     .emitSystemVerilog(new CoreSoc(new Core(DefaultCoreConfig())), Array("--target-dir", "test_run_dir/Elaborate_RVFI"))
// }
  it should "pass verify" in {
    verify(new CoreSoc(new Core(DefaultCoreConfig())), Seq(BoundedCheck(20), BtormcEngineAnnotation))
  }
}