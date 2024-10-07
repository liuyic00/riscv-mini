// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.util.experimental.BoringUtils
import rvspeccore.core.RVConfig
import rvspeccore.checker._

object Const {
  val PC_START = 0x200
  val PC_EVEC = 0x100
}

class DatapathIO(xlen: Int) extends Bundle {
  val host = new HostIO(xlen)
  val icache = Flipped(new CacheIO(xlen, xlen))
  val dcache = Flipped(new CacheIO(xlen, xlen))
  val ctrl = Flipped(new ControlSignals)
}

class FetchExecutePipelineRegister(xlen: Int) extends Bundle {
  val inst = chiselTypeOf(Instructions.NOP)
  val pc = UInt(xlen.W)
}

class ExecuteWritebackPipelineRegister(xlen: Int) extends Bundle {
  val inst = chiselTypeOf(Instructions.NOP)
  val pc = UInt(xlen.W)
  val alu = UInt(xlen.W)
  val csr_in = UInt(xlen.W)
}

class Datapath(val conf: CoreConfig) extends Module {
  val io = IO(new DatapathIO(conf.xlen))
  val csr = Module(new CSR(conf.xlen))
  val regFile = Module(new RegFile(conf.xlen))
  val alu = Module(conf.makeAlu(conf.xlen))
  val immGen = Module(conf.makeImmGen(conf.xlen))
  val brCond = Module(conf.makeBrCond(conf.xlen))

  import Control._

  /** Pipeline State Registers * */

  /** *** Fetch / Execute Registers ****
    */
  val fe_reg = RegInit(
    (new FetchExecutePipelineRegister(conf.xlen)).Lit(
      _.inst -> Instructions.NOP,
      _.pc -> 0.U
    )
  )

  /** *** Execute / Write Back Registers ****
    */
  val ew_reg = RegInit(
    (new ExecuteWritebackPipelineRegister(conf.xlen)).Lit(
      _.inst -> Instructions.NOP,
      _.pc -> 0.U,
      _.alu -> 0.U,
      _.csr_in -> 0.U
    )
  )

  /** **** Control signals ****
    */
  val st_type = Reg(io.ctrl.st_type.cloneType)
  val ld_type = Reg(io.ctrl.ld_type.cloneType)
  val wb_sel = Reg(io.ctrl.wb_sel.cloneType)
  val wb_en = Reg(Bool())
  val csr_cmd = Reg(io.ctrl.csr_cmd.cloneType)
  val illegal = Reg(Bool())
  val pc_check = Reg(Bool())

  /** **** Fetch ****
    */
  val started = RegNext(reset.asBool)
  val stall = !io.icache.resp.valid || !io.dcache.resp.valid
  val pc = RegInit(Const.PC_START.U(conf.xlen.W) - 4.U(conf.xlen.W))
  // Next Program Counter
  val next_pc = MuxCase(
    pc + 4.U,
    IndexedSeq(
      stall -> pc,
      csr.io.expt -> csr.io.evec,
      (io.ctrl.pc_sel === PC_EPC) -> csr.io.epc,
      ((io.ctrl.pc_sel === PC_ALU) || (brCond.io.taken)) -> (alu.io.sum >> 1.U << 1.U),
      (io.ctrl.pc_sel === PC_0) -> pc
    )
  )
  val jump = BoringUtils.addSource(csr.io.expt || RegNext(stall || (io.ctrl.pc_sel === PC_EPC) || (io.ctrl.pc_sel === PC_ALU) || (brCond.io.taken) || (io.ctrl.pc_sel === PC_0), 0.U).asBool,  "Jumpornot")
  BoringUtils.addSource(Mux(csr.io.expt, next_pc, RegNext(next_pc, 0.U)), "rvfiio_pc_jump_data")
//  printf("[PC Calc] stall:%d, expt:%d, pc_sel:%d Taken:%d pc:%x next_pc:%x \n", stall, csr.io.expt, io.ctrl.pc_sel, (brCond.io.taken), pc, next_pc)
  val inst =
    Mux(started || io.ctrl.inst_kill || brCond.io.taken || csr.io.expt, Instructions.NOP, io.icache.resp.bits.data)
//  printf("pc:%x next_pc:%x inst:%x\n", pc, next_pc, inst)
  BoringUtils.addSink(csr.io.expt, "testssdfly")
  pc := next_pc
  io.icache.req.bits.addr := next_pc
  io.icache.req.bits.data := 0.U
  io.icache.req.bits.mask := 0.U
  io.icache.req.valid := !stall
  io.icache.abort := false.B

  // Pipelining
  when(!stall) {
    fe_reg.pc := pc
    fe_reg.inst := inst
  }

  /** **** Execute ****
    */
  io.ctrl.inst := fe_reg.inst

  // regFile read
  val rd_addr = fe_reg.inst(11, 7)
  val rs1_addr = fe_reg.inst(19, 15)
  val rs2_addr = fe_reg.inst(24, 20)
  regFile.io.raddr1 := rs1_addr
  regFile.io.raddr2 := rs2_addr

  // gen immdeates
  immGen.io.inst := fe_reg.inst
  immGen.io.sel := io.ctrl.imm_sel

  // bypass
  val wb_rd_addr = ew_reg.inst(11, 7)
  val rs1hazard = wb_en && rs1_addr.orR && (rs1_addr === wb_rd_addr)
  val rs2hazard = wb_en && rs2_addr.orR && (rs2_addr === wb_rd_addr)
  val rs1 = Mux(wb_sel === WB_ALU && rs1hazard, ew_reg.alu, regFile.io.rdata1)
  val rs2 = Mux(wb_sel === WB_ALU && rs2hazard, ew_reg.alu, regFile.io.rdata2)

  // ALU operations
  alu.io.A := Mux(io.ctrl.A_sel === A_RS1, rs1, fe_reg.pc)
  alu.io.B := Mux(io.ctrl.B_sel === B_RS2, rs2, immGen.io.out)
  alu.io.alu_op := io.ctrl.alu_op

  // Branch condition calc
  brCond.io.rs1 := rs1
  brCond.io.rs2 := rs2
  brCond.io.br_type := io.ctrl.br_type

  // D$ access
  val daddr = Mux(stall, ew_reg.alu, alu.io.sum) >> 2.U << 2.U
  val woffset = (alu.io.sum(1) << 4.U).asUInt | (alu.io.sum(0) << 3.U).asUInt
  io.dcache.req.valid := !stall && (io.ctrl.st_type.orR || io.ctrl.ld_type.orR)
  io.dcache.req.bits.addr := daddr
  io.dcache.req.bits.data := rs2 << woffset
  io.dcache.req.bits.mask := MuxLookup(Mux(stall, st_type, io.ctrl.st_type), "b0000".U)(
    Seq(ST_SW -> "b1111".U, ST_SH -> ("b11".U << alu.io.sum(1, 0)), ST_SB -> ("b1".U << alu.io.sum(1, 0)))
  )

  // Pipelining
  when(reset.asBool || !stall && csr.io.expt) {
    st_type := 0.U
    ld_type := 0.U
    wb_en := false.B
    csr_cmd := 0.U
    illegal := false.B
    pc_check := false.B
  }.elsewhen(!stall && !csr.io.expt) {
    ew_reg.pc := fe_reg.pc
    ew_reg.inst := fe_reg.inst
    ew_reg.alu := alu.io.out
    ew_reg.csr_in := Mux(io.ctrl.imm_sel === IMM_Z, immGen.io.out, rs1)
    st_type := io.ctrl.st_type
    ld_type := io.ctrl.ld_type
    wb_sel := io.ctrl.wb_sel
    wb_en := io.ctrl.wb_en
    csr_cmd := io.ctrl.csr_cmd
    illegal := io.ctrl.illegal
    pc_check := (io.ctrl.pc_sel === PC_ALU) || (brCond.io.taken)
  }

  // Load
  val loffset = (ew_reg.alu(1) << 4.U).asUInt | (ew_reg.alu(0) << 3.U).asUInt
  val lshift = io.dcache.resp.bits.data >> loffset
  val load = MuxLookup(ld_type, io.dcache.resp.bits.data.zext)(
    Seq(
      LD_LH -> lshift(15, 0).asSInt,
      LD_LB -> lshift(7, 0).asSInt,
      LD_LHU -> lshift(15, 0).zext,
      LD_LBU -> lshift(7, 0).zext
    )
  )
//  printf("alu:%x resp:%x loffset:%x shift:%x load:%x\n", ew_reg.alu, io.dcache.resp.bits.data, loffset, lshift, load)
//  val load_mask = MuxLookup(Mux(stall, ld_type, io.ctrl.ld_type), "b0000".U)(
//    Seq(LD_LW -> "b1111".U, LD_LH -> ("b11".U << alu.io.sum(1, 0)), LD_LHU -> ("b11".U << alu.io.sum(1, 0)), LD_LB -> ("b1".U << alu.io.sum(1, 0)), LD_LBU -> ("b1".U << alu.io.sum(1, 0)))
//  )
  val lw_addr = RegNext(alu.io.sum, 0.U)
  val load_mask = MuxLookup(ld_type, "b1111".U)(
    Seq(
      LD_XXX -> "b0000".U,
      LD_LH  -> ("b0011".U << lw_addr(1, 0)),
      LD_LB  -> ("b0001".U << lw_addr(1, 0)),
      LD_LHU -> ("b0011".U << lw_addr(1, 0)),
      LD_LBU -> ("b0001".U << lw_addr(1, 0))
    )
  )
//  printf("AlU:%x %x\n", ew_reg.alu, alu.io.sum)
  BoringUtils.addSource(io.dcache.resp.bits.data, "rvfiio_mem_rdata")
  BoringUtils.addSource(load_mask, "rvfiio_mem_rmask")
//  printf("DCache Access[DataPath]: Req Valid:%d Addr:%x Data:%x Mask:%x load:%x\n", io.dcache.req.valid, io.dcache.req.bits.addr, io.dcache.req.bits.data, io.dcache.req.bits.mask, load)
  val mem_wmask = RegNext(io.dcache.req.bits.mask, 0.U)
  val mem_wdata = RegNext(io.dcache.req.bits.data, 0.U)
  BoringUtils.addSource(mem_wmask, "rvfiio_mem_wmask")
  BoringUtils.addSource(mem_wdata, "rvfiio_mem_wdata")
  // CSR access
  csr.io.stall := stall
  csr.io.in := ew_reg.csr_in
  csr.io.cmd := csr_cmd
  csr.io.inst := ew_reg.inst
  csr.io.pc := ew_reg.pc
  csr.io.addr := ew_reg.alu
  csr.io.illegal := illegal
  csr.io.pc_check := pc_check
  csr.io.ld_type := ld_type
  csr.io.st_type := st_type
  io.host <> csr.io.host

  // Regfile Write
  val regWrite =
    MuxLookup(wb_sel, ew_reg.alu.zext)(
      Seq(WB_MEM -> load, WB_PC4 -> (ew_reg.pc + 4.U).zext, WB_CSR -> csr.io.out.zext)
    ).asUInt

  regFile.io.wen := wb_en && !stall && !csr.io.expt
  regFile.io.waddr := wb_rd_addr
  regFile.io.wdata := regWrite

  // Abort store when there's an excpetion
  io.dcache.abort := csr.io.expt

  // TODO: re-enable through AOP
//  if (p(Trace)) {
//    printf(
//      "PC: %x, INST: %x, REG[%d] <- %x\n",
//      ew_reg.pc,
//      ew_reg.inst,
//      Mux(regFile.io.wen, wb_rd_addr, 0.U),
//      Mux(regFile.io.wen, regFile.io.wdata, 0.U)
//    )
//  }
  val instCommit = Wire(Bool())
  val instCommit_predit = Wire(Bool())
//  printf("CSR Exception:%d\n", csr.io.expt)
  instCommit_predit := (RegNext(RegNext((started || io.ctrl.inst_kill || brCond.io.taken || csr.io.expt), true.B) || csr.io.expt, true.B) || stall || reset.asBool)
  instCommit := !instCommit_predit
  //  when(instCommit_predit){
//    // checker.io.instCommit.valid := false.B
//    instCommit := false.B
//  }otherwise{
//    instCommit := true.B
//    when(!stall && !csr.io.expt){
////      printf("PC: %x Inst: %x\n", ew_reg.pc, ew_reg.inst)
//      // checker.io.instCommit.valid := true.B
//      instCommit := true.B
////      printf(
////        "[ChiselCommitPrint]PC: %x, INST: %x, REG[%d] <- %x\n",
////        ew_reg.pc,
////        ew_reg.inst,
////        Mux(regFile.io.wen, wb_rd_addr, 0.U),
////        Mux(regFile.io.wen, regFile.io.wdata, 0.U)
////      )
//    }.otherwise{
//      // checker.io.instCommit.valid := false.B
//      instCommit := false.B
//    }
//  }
  val instOrder = RegInit(0.U(64.W))

  when(instCommit){
    instOrder := instOrder + 1.U
//    printf("[Debug INST %x RS1 %d RS2 %d %d %d] \n", ew_reg.inst,
//      RegNext(io.ctrl.A_sel === A_RS1, 0.U), RegNext(io.ctrl.B_sel === B_RS2, 0.U),
//      ew_reg.inst(19, 15), ew_reg.inst(24, 20)
//    )
  }

  BoringUtils.addSource(instCommit, "rvfiio_valid")
  BoringUtils.addSource(instOrder, "rvfiio_order")
  BoringUtils.addSource(ew_reg.inst, "rvfiio_insn")
  val flywire_rs1_addr = Wire(UInt(5.W))
  val flywire_rs2_addr = Wire(UInt(5.W))
  flywire_rs1_addr := Mux(RegNext(io.ctrl.A_sel === A_RS1, 0.U).asBool || RegNext(io.ctrl.br_type =/= 0.U, 0.U).asBool, ew_reg.inst(19, 15), 0.U)
  flywire_rs2_addr := Mux(RegNext(io.ctrl.B_sel === B_RS2, 0.U).asBool || RegNext(io.ctrl.st_type =/= 0.U, 0.U).asBool || RegNext(io.ctrl.br_type =/= 0.U, 0.U).asBool, ew_reg.inst(24, 20), 0.U)

//  printf("Load and Store[%x %x]: Inst:%x, %x %x, RS1:%d, RS2:%d\n", RegNext(io.ctrl.ld_type, 0.U), RegNext(io.ctrl.st_type, 0.U), ew_reg.inst, io.ctrl.A_sel, io.ctrl.B_sel, ew_reg.inst(19, 15), ew_reg.inst(24, 20))
  BoringUtils.addSource(flywire_rs1_addr, "rvfiio_rs1_addr")
  BoringUtils.addSource(flywire_rs2_addr, "rvfiio_rs2_addr")
  BoringUtils.addSource(RegNext(rs1, 0.U), "rvfiio_rs1_rdata")
  BoringUtils.addSource(RegNext(rs2, 0.U), "rvfiio_rs2_rdata")
  BoringUtils.addSource(Mux(regFile.io.wen, wb_rd_addr, 0.U), "rvfiio_rd_addr")
  BoringUtils.addSource(regWrite, "rvfiio_rd_wdata")
  BoringUtils.addSource(ew_reg.pc, "rvfiio_pc_rdata")
//  BoringUtils.addSource(RegNext(next_pc, 0.U), "rvfiio_pc_wdata") // 可能会被刷掉，所以是不对的
  BoringUtils.addSource(RegNext(daddr, 0.U), "rvfiio_mem_addr")
  BoringUtils.addSource(csr.io.expt, "rvfiio_trap")
  val rvConfig = RVConfig(
    XLEN = 32,
    extensions = "M",
    initValue = Map(
      "pc"    -> "h0000_0200",
      "mtvec" -> "h0000_01c0"
    ),
    formal = Seq("ArbitraryRegFile")
  )
  val checker = Module(new CheckerWithResult(checkMem = true)(rvConfig))
  checker.io.instCommit.valid := instCommit
  checker.io.instCommit.inst  := ew_reg.inst
  checker.io.instCommit.pc    := ew_reg.pc
  ConnectCheckerResult.setChecker(checker)(32, rvConfig)
  val mem = rvspeccore.checker.ConnectCheckerResult.makeMemSource()(32)
////  load_mask > 0 , then valid

  val load_width = MuxLookup(ld_type, "b0000".U)(
    Seq(
      LD_XXX -> 0.U(log2Ceil(33).W),
      LD_LB  -> 8.U(log2Ceil(33).W),
      LD_LH  -> 16.U(log2Ceil(33).W),
      LD_LW  -> 32.U(log2Ceil(33).W),
      LD_LBU -> 8.U(log2Ceil(33).W),
      LD_LHU -> 16.U(log2Ceil(33).W)
    )
  )
  val store_width = MuxLookup(Mux(stall, st_type, io.ctrl.st_type), "b0000".U)(
    Seq(
      ST_XXX -> 0.U(log2Ceil(33).W),
      ST_SB -> 8.U(log2Ceil(33).W),
      ST_SH -> 16.U(log2Ceil(33).W),
      ST_SW -> 32.U(log2Ceil(33).W)
    )
  )
  val req_addr = RegNext(Mux(stall, ew_reg.alu, alu.io.sum), 0.U)
  mem.read.valid := (load_width > 0.U) && !csr.io.expt
  mem.read.addr  := req_addr
  mem.read.data  := io.dcache.resp.bits.data
  mem.read.memWidth := load_width
//  printf("FlyWireMemDebug[read, expt:%d]:  Req valid:%d, addr:%x, data:%x, memWidth:%x load_mask:%x\n", csr.io.expt, (load_mask > 0.U), RegNext(mem.read.addr, 0.U), mem.read.data, load_mask, load_width)
  printf("FlyWireMemDebug[write valid:%d, expt:%d]:  Width>0:%d, addr:%x, data:%x, memWidth:%x\n", RegNext(store_width > 0.U, false.B) && !csr.io.expt, csr.io.expt, store_width > 0.U, req_addr, mem_wdata, RegNext(store_width, 0.U))
//  RegNext(store_width > 0.U && !csr.io.expt, false.B)
  mem.write.valid := RegNext(store_width > 0.U, false.B) && !csr.io.expt
  mem.write.addr  := req_addr
  mem.write.data  := mem_wdata
  mem.write.memWidth := RegNext(store_width, 0.U)
}
