// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util.Valid
import chisel3.util.experimental.BoringUtils
import rvspeccore.checker.RVI
import rvspeccore.core.RVConfig
import rvspeccore.checker._

class RVFIIO extends Bundle {
  val valid = Output(Bool())
  val order = Output(UInt(64.W))
  val insn = Output(UInt(32.W))
  val trap = Output(Bool())
  val halt = Output(Bool())
  val intr = Output(Bool())
  val mode = Output(UInt(2.W))
  val ixl = Output(UInt(2.W))
  val rs1_addr = Output(UInt(5.W))
  val rs2_addr = Output(UInt(5.W))
  val rs1_rdata = Output(UInt(32.W))
  val rs2_rdata = Output(UInt(32.W))
  val rd_addr = Output(UInt(5.W))
  val rd_wdata = Output(UInt(32.W))
  val pc_rdata = Output(UInt(32.W))
  val pc_wdata = Output(UInt(32.W))
  val mem_addr = Output(UInt(32.W))
  val mem_rmask = Output(UInt(4.W))
  val mem_wmask = Output(UInt(4.W))
  val mem_rdata = Output(UInt(32.W))
  val mem_wdata = Output(UInt(32.W))
  // val csr_mcycle_rmask = Output(UInt(64.W))
  // val csr_mcycle_wmask = Output(UInt(64.W))
  // val csr_mcycle_rdata = Output(UInt(64.W))
  // val csr_mcycle_wdata = Output(UInt(64.W))
  // val csr_minstret_rmask = Output(UInt(64.W))
  // val csr_minstret_wmask = Output(UInt(64.W))
  // val csr_minstret_rdata = Output(UInt(64.W))
  // val csr_minstret_wdata = Output(UInt(64.W))
}

class CoreSoc(core: => Core)extends Module {
  val rvfi   = IO(new RVFIIO)
  val dut = Module(core)
  val xlen = dut.conf.xlen
  dut.io.host.fromhost.bits := DontCare
  dut.io.host.fromhost.valid := false.B
  rvfi <> dut.rvfi
  val imem = Mem(1 << 4, UInt(xlen.W))
  val dmem = Mem(1 << 4, UInt(xlen.W))
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
  when(dut.io.dcache.req.valid) {
    when(dut.io.dcache.req.bits.mask.orR) {
      dmem(daddr) := write
    }
  }
  cycle := cycle + 1.U
  val someAssume = Wire(Bool())
  someAssume := DontCare
  BoringUtils.addSink(someAssume, "someassumeid")
  assume(someAssume)
}
case class CoreConfig(
  xlen:       Int,
  makeAlu:    Int => Alu = new AluSimple(_),
  makeBrCond: Int => BrCond = new BrCondSimple(_),
  makeImmGen: Int => ImmGen = new ImmGenWire(_))

class HostIO(xlen: Int) extends Bundle {
  val fromhost = Flipped(Valid(UInt(xlen.W)))
  val tohost = Output(UInt(xlen.W))
}

class CoreIO(xlen: Int) extends Bundle {
  val host = new HostIO(xlen)
  val icache = Flipped(new CacheIO(xlen, xlen))
  val dcache = Flipped(new CacheIO(xlen, xlen))
}

class Core(val conf: CoreConfig) extends Module {
  val io = IO(new CoreIO(conf.xlen))
  val dpath = Module(new Datapath(conf))
  val ctrl = Module(new Control)
  val rvfi   = IO(new RVFIIO)

  io.host <> dpath.io.host
  dpath.io.icache <> io.icache
  dpath.io.dcache <> io.dcache
  dpath.io.ctrl <> ctrl.io

  // connect
  val rvfi_con = Wire(new RVFIIO)
  rvfi_con := DontCare
  val rvtemp_insn = Wire(UInt(32.W))
  rvtemp_insn := DontCare
  BoringUtils.addSink(rvfi_con.valid, "rvfiio_valid")
  BoringUtils.addSink(rvfi_con.order, "rvfiio_order")
  BoringUtils.addSink(rvtemp_insn, "rvfiio_insn")
  when(rvfi.valid){
    rvfi_con.insn := rvtemp_insn
  }otherwise{
    rvfi.insn := 0.U
  }

//  rvfi_con.trap := false.B
  BoringUtils.addSink(rvfi_con.trap, "rvfiio_trap")
  rvfi_con.halt := false.B
  rvfi_con.intr := false.B
  rvfi_con.mode := 3.U
  rvfi_con.ixl  := 1.U
  val addr_setting_zero_rs1 = Wire(UInt(5.W))
  val addr_setting_zero_rs2 = Wire(UInt(5.W))
  val addr_setting_zero_rd  = Wire(UInt(5.W))
  val rs1_rdata_ssd = Wire(UInt(32.W))
  val rs2_rdata_ssd = Wire(UInt(32.W))
  val rd_wdata_ssd  = Wire(UInt(32.W))
  addr_setting_zero_rs1 := DontCare
  addr_setting_zero_rs2 := DontCare
  addr_setting_zero_rd  := DontCare
  rs1_rdata_ssd := DontCare
  rs2_rdata_ssd := DontCare
  rd_wdata_ssd  := DontCare
  BoringUtils.addSink(addr_setting_zero_rs1, "rvfiio_rs1_addr")
  BoringUtils.addSink(addr_setting_zero_rs2, "rvfiio_rs2_addr")
  BoringUtils.addSink(addr_setting_zero_rd, "rvfiio_rd_addr")
  BoringUtils.addSink(rs1_rdata_ssd, "rvfiio_rs1_rdata")
  BoringUtils.addSink(rs2_rdata_ssd, "rvfiio_rs2_rdata")
  BoringUtils.addSink(rd_wdata_ssd, "rvfiio_rd_wdata")

  rvfi_con.rs1_addr := addr_setting_zero_rs1
  rvfi_con.rs2_addr := addr_setting_zero_rs2
  rvfi_con.rd_addr  := addr_setting_zero_rd
  when(addr_setting_zero_rs1 === 0.U){
    rvfi_con.rs1_rdata := 0.U
  }.otherwise{
    rvfi_con.rs1_rdata := rs1_rdata_ssd
  }
  when(addr_setting_zero_rs2 === 0.U){
    rvfi_con.rs2_rdata := 0.U
  }.otherwise{
    rvfi_con.rs2_rdata := rs2_rdata_ssd
  }

  when(addr_setting_zero_rd === 0.U){
    rvfi_con.rd_wdata := 0.U
  }.otherwise{
    rvfi_con.rd_wdata := rd_wdata_ssd
  }
  // BoringUtils.addSink(rvfi.rs1_addr, "rvfiio_rs1_addr")
  // BoringUtils.addSink(rvfi.rs2_addr, "rvfiio_rs2_addr")
  BoringUtils.addSink(rvfi_con.rd_addr, "rvfiio_rd_addr")
  // BoringUtils.addSink(rvfi.rd_wdata, "rvfiio_rd_wdata")
  BoringUtils.addSink(rvfi_con.pc_rdata, "rvfiio_pc_rdata")
//  BoringUtils.addSink(rvfi_con.pc_wdata, "rvfiio_pc_wdata")
  BoringUtils.addSink(rvfi_con.mem_addr, "rvfiio_mem_addr")
//  printf("DCache Access[Core]: Valid:%d Addr:%x Data:%x Mask:%x\n", io.dcache.req.valid, io.dcache.req.bits.addr, io.dcache.resp.bits.data, io.dcache.req.bits.mask)
  // mem_rmask is all 1
//  when(io.dcache.req.valid){
//    rvfi_con.mem_rmask := io.dcache.req.bits.mask
//    rvfi_con.mem_wmask := io.dcache.req.bits.mask
//    rvfi_con.mem_rdata := io.dcache.resp.bits.data
//    rvfi_con.mem_wdata := io.dcache.req.bits.data
//  }.otherwise{
//    rvfi_con.mem_rmask := 0.U
//    rvfi_con.mem_wmask := 0.U
//    rvfi_con.mem_rdata := 0.U
//    rvfi_con.mem_wdata := 0.U
  BoringUtils.addSink(rvfi_con.mem_rmask, "rvfiio_mem_rmask")
  BoringUtils.addSink(rvfi_con.mem_wmask, "rvfiio_mem_wmask")
  BoringUtils.addSink(rvfi_con.mem_rdata, "rvfiio_mem_rdata")
  BoringUtils.addSink(rvfi_con.mem_wdata, "rvfiio_mem_wdata")
  val JumpNot = Wire(Bool())
  JumpNot := DontCare
  BoringUtils.addSink(JumpNot, "Jumpornot")
  val pc_wdata_test = Wire(UInt(32.W))
  pc_wdata_test := DontCare
  BoringUtils.addSink(pc_wdata_test, "rvfiio_pc_jump_data")
//  printf("[Jump]%d %x\n", JumpNot, pc_wdata_test)
//  }
  rvfi := rvfi_con
  when(JumpNot){
    rvfi.pc_wdata := pc_wdata_test
  }.otherwise{
    rvfi.pc_wdata := rvfi.pc_rdata + 4.U
  }
//  printf("RESP:%x\n", rvfi_con.mem_rdata)
  val tmpAssume = !rvfi.valid || (
    RVI.regImm(rvfi.insn)(conf.xlen)
      || RVI.regReg(rvfi.insn)(conf.xlen)
      || RVI.control(rvfi.insn)(conf.xlen)
      || RVI.loadStore(rvfi.insn)(conf.xlen)
//      || RVI.other(rvfi.insn)(conf.xlen)
  )
  BoringUtils.addSource(WireInit(tmpAssume && (rvfi.trap === false.B)), "someassumeid")
  when(rvfi.valid){
//    assume(
//      RVI.regImm(rvfi.insn)(conf.xlen),
//    )
    printf(
      "[RVFI Print%x][trapnext:%x][Expt:%x]Mem_rmask:%x, ADDR:%x Core: valid=%d order=%x insn=%x Jump:%d JumpTarget: %x rd_addr=%d rd_data=%x rs1_addr=%d rs1_data=%x rs2_addr=%d rs2_data=%x PCr=%x, PCw=%x\n",
//      "[RVFI Print]Core: valid=%d order=%x insn=%x pc_rdata:%x pc_wdata:%x\n",
//      rvfi_con.mem_rmask, rvfi_con.mem_wmask, rvfi_con.mem_rdata, rvfi_con.mem_wdata,
      rvfi_con.mem_rdata,
      RegNext(rvfi.trap, 0.U),
      rvfi.trap,
      rvfi.mem_rmask, rvfi.mem_addr,
      rvfi.valid, rvfi.order, rvfi.insn, JumpNot, rvfi.pc_wdata,
      rvfi.rd_addr, rvfi.rd_wdata,
      rvfi.rs1_addr, rvfi.rs1_rdata,
      rvfi.rs2_addr, rvfi.rs2_rdata,
      rvfi.pc_rdata, rvfi.pc_wdata
    )
    // printf(
    //   "Core: valid=%d order=%d insn=%x \n",
    //   rvfi_con.valid, rvfi_con.order, 123.U
    // )
  }
}
