/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

package xiangshan.backend.rob

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}

import difftest._
import utils._
import xs.utils._
import xiangshan._
import xiangshan.frontend.FtqPtr
import xiangshan.backend.execute.exu.{ExuConfig, ExuType}
import xiangshan.backend.writeback._
import xiangshan.vector._

class RobDeqPtrWrapper(implicit p: Parameters) extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new Bundle {
    // for commits/flush
    val state = Input(UInt(2.W))
    val deq_v = Vec(CommitWidth, Input(Bool()))
    val deq_w = Vec(CommitWidth, Input(Bool()))
    val exception_state = Flipped(ValidIO(new RobExceptionInfo))
    // for flush: when exception occurs, reset deqPtrs to range(0, CommitWidth)
    val intrBitSetReg = Input(Bool())
    val hasNoSpecExec = Input(Bool())
    val interrupt_safe = Input(Bool())
    val blockCommit = Input(Bool())
    // output: the CommitWidth deqPtr
    val out       = Vec(CommitWidth, Output(new RobPtr))
    val next_out  = Vec(CommitWidth, Output(new RobPtr))
  })

  val deqPtrVec = RegInit(VecInit((0 until CommitWidth).map(_.U.asTypeOf(new RobPtr))))

  // for exceptions (flushPipe included) and interrupts:
  // only consider the first instruction
  val intrEnable      = io.intrBitSetReg && (!io.hasNoSpecExec) && io.interrupt_safe
  val exceptionEnable = io.deq_w(0) && io.exception_state.valid && io.exception_state.bits.not_commit && (io.exception_state.bits.robIdx === deqPtrVec(0))

  val redirectOutValid = (io.state === 0.U) && io.deq_v(0) && (intrEnable || exceptionEnable)

  // for normal commits: only to consider when there're no exceptions
  // we don't need to consider whether the first instruction has exceptions since it wil trigger exceptions.
  val commit_exception = io.exception_state.valid && !isAfter(io.exception_state.bits.robIdx, deqPtrVec.last)
  val canCommit = VecInit((0 until CommitWidth).map(i => io.deq_v(i) && io.deq_w(i)))
  val normalCommitCnt = PriorityEncoder(canCommit.map(c => !c) :+ true.B)
  // when io.intrBitSetReg or there're possible exceptions in these instructions,
  // only one instruction is allowed to commit
  val allowOnlyOne = commit_exception || io.intrBitSetReg
  val commitCnt = Mux(allowOnlyOne, canCommit(0), normalCommitCnt)

  val commitDeqPtrVec = VecInit(deqPtrVec.map(_ + commitCnt))
  val deqPtrVec_next = Mux((io.state === 0.U) && (!redirectOutValid) && (!io.blockCommit), commitDeqPtrVec, deqPtrVec)

  deqPtrVec := deqPtrVec_next

  io.next_out := deqPtrVec_next
  io.out      := deqPtrVec

  when (io.state === 0.U) {
    XSInfo(io.state === 0.U && commitCnt > 0.U, "retired %d insts\n", commitCnt)
  }
}

class RobEnqPtrWrapper(implicit p: Parameters) extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new Bundle {
    // for input redirect
    val redirect = Input(Valid(new Redirect))
    // for enqueue
    val allowEnqueue = Input(Bool())
    val hasBlockBackward = Input(Bool())
    val enq = Vec(RenameWidth, Input(Bool()))
    val out = Output(Vec(RenameWidth, new RobPtr))
  })

  val enqPtrVec = RegInit(VecInit.tabulate(RenameWidth)(_.U.asTypeOf(new RobPtr)))

  // enqueue
  val canAccept = io.allowEnqueue && !io.hasBlockBackward
  val enqNum = Mux(canAccept, PopCount(io.enq), 0.U)

  for ((ptr, i) <- enqPtrVec.zipWithIndex) {
    when(io.redirect.valid) {
      ptr := Mux(io.redirect.bits.flushItself(), io.redirect.bits.robIdx + i.U, io.redirect.bits.robIdx + (i + 1).U)
    }.otherwise {
      ptr := ptr + enqNum
    }
  }

  io.out := enqPtrVec

}

class Rob(implicit p: Parameters) extends LazyModule with HasXSParameter {
  val wbNodeParam = WriteBackSinkParam(name = "ROB", sinkType = WriteBackSinkType.rob)
  val writebackNode = new WriteBackSinkNode(wbNodeParam)
  lazy val module = new RobImp(this)
}

class RobImp(outer: Rob)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasXSParameter
  with HasVectorParameters
  with HasCircularQueuePtrHelper
  with HasPerfEvents {

  class CSRDataEntry(implicit p: Parameters) extends Bundle {
    val fflags = UInt(5.W)
    val vstart = UInt(7.W)
    val vxsat = Bool()
  }

  require(outer.writebackNode.in.length == 1)
  val writebackInBundles = outer.writebackNode.in.head._1
  val writebackInParams = outer.writebackNode.in.head._2._1
  //writeback: Seq[(param, bundle)]
  val writebackIn = writebackInParams zip writebackInBundles
  val numWbPorts = writebackIn.length

  val io = IO(new Bundle() {
    //for multi core debug
    val hartId = Input(UInt(8.W))
    val redirect = Input(Valid(new Redirect))
    val enq = new RobEnqIO
    val exception = ValidIO(new ExceptionInfo)
    val mmuEnable = Input(Bool())
    val commits = new RobCommitIO
    val lsq = new RobLsqIO
    val robDeqPtr = Output(new RobPtr)
    val csr = new RobCSRIO
    val robFull = Output(Bool())
    val cpu_halt = Output(Bool())
    val wfi_enable = Input(Bool())
    val wbFromMergeBuffer = Vec(VectorMergeWbWidth, Flipped(ValidIO(new ExuOutput)))
  })

  val wbWithFFlag = writebackIn.filter(wb => wb._1.writeFFlags)
  val wbWithException = writebackIn.filter(_._1.hasException)

  //params exclude
  val wbPorts = writebackIn.map(_._2)

  println(s"Rob     : size: $RobSize, numWbPorts: $numWbPorts, commitwidth: $CommitWidth")
  println(s"fflags  : ${wbWithFFlag.map(_._1.name)}")
  println(s"exception from exu: ${wbWithException.map(_._1.name)}")
  writebackIn.zipWithIndex.foreach({
    case((p ,_), i) =>
      println(s"Writeback Port #$i from ${p.name}")
  })

  /*
    rob entry -> {
      -------for func--------
      valid,
      writebacked,
      flagBkup,
      interrupt_safe,
      entryData,
      csrData = {fflags, vstart, vxsat}
      -------for debug-------
      debug_microOp
      debug_exuData
      debug_exuDebug
      -------for perf--------
      microOpPerfInfo
    }
  */

  //************************entry data structure define************************
  /**
  * Data Modules
  *
  * CommitDataModule: data from rename
  * (1) read: commits/walk/exception
  * (2) write: enqueue
  *
  * WritebackData: data from writeback
  * (1) read: commits/walk/exception
  * (2) write: write back from exe units
  */
  // instvalid field
  val valid = RegInit(VecInit(Seq.fill(RobSize)(false.B)))
  // writeback status
  val writebacked = Mem(RobSize, Bool())
  // data for redirect, exception, etc.
  val flagBkup = Mem(RobSize, Bool())
  // some instructions are not allowed to trigger interrupts
  // They have side effects on the states of the processor before they write back
  val interrupt_safe = RegInit(VecInit(Seq.fill(RobSize)(true.B)))
  val entryDataModule = Module(new SyncDataModuleTemplate(
    new RobEntryData, RobSize, CommitWidth, RenameWidth, "Rob", concatData = true
  ))

  val fflags_wb = wbWithFFlag.map(_._2)
  val fflagsWbNums  = fflags_wb.length
  val csrDataModule = Module(new SyncDataModuleTemplate(
    new CSRDataEntry, RobSize, CommitWidth, fflagsWbNums + VectorMergeWbWidth, "csrEntry"
  ))

  val entryDataRead = entryDataModule.io.rdata
  val csrDataRead   = csrDataModule.io.rdata

  //************************data for debug************************
  // Warn: debug_* prefix should not exist in generated verilog.
  val debug_microOp   = Mem(RobSize, new MicroOp)
  val debug_exuData   = Reg(Vec(RobSize, UInt(XLEN.W)))
  val debug_exuDebug  = Reg(Vec(RobSize, new DebugBundle))

  //************************for perf counter************************
  val microOpPerfInfo = Mem(RobSize, new Bundle {
    val isMove = Bool()
    val loadWaitBit = Bool()
  })

  //************************exception generation************************
  val exceptionGen = Module(new ExceptionGen(wbWithException.length))
  val exceptionDataRead = exceptionGen.io.state

  // pointers
  // For enqueue ptr, we don't duplicate it since only enqueue needs it.
  val enqPtrVec   = Wire(Vec(RenameWidth, new RobPtr))
  val enqPtr = enqPtrVec.head

  val deqPtrVec   = Wire(Vec(CommitWidth, new RobPtr))
  val deqPtr = deqPtrVec(0)

  val walkPtrVec  = Reg(Vec(CommitWidth, new RobPtr))
  val walkPtr = walkPtrVec(0)

  val allowEnqueue = RegInit(true.B)
  val isEmpty = (enqPtr === deqPtr)

  io.robDeqPtr := deqPtr

  /**
    * states of Rob
    */
  val s_idle :: s_walk :: s_extrawalk :: Nil = Enum(3)
  val state = RegInit(s_idle)

  /**
    * ************************Enqueue (from rename)************************
    */

  // special cases
  val hasBlockBackward  = RegInit(false.B)
  val hasNoSpecExec     = RegInit(false.B)
//  val doingSvinval = RegInit(false.B)
  // When blockBackward instruction leaves Rob (commit or walk), hasBlockBackward should be set to false.B
  // To reduce registers usage, for hasBlockBackward cases, we allow enqueue after ROB is empty.
  when (isEmpty) {
    hasBlockBackward:= false.B
  }
  // When any instruction commits, hasNoSpecExec should be set to false.B
  when ((io.commits.hasWalkInstr && state =/= s_extrawalk) || io.commits.hasCommitInstr) {
    hasNoSpecExec:= false.B
  }

  // The wait-for-interrupt (WFI) instruction waits in the ROB until an interrupt might need servicing.
  // io.csr.wfiEvent will be asserted if the WFI can resume execution, and we change the state to s_wfi_idle.
  // It does not affect how interrupts are serviced. Note that WFI is noSpecExec and it does not trigger interrupts.
  val hasWFI = RegInit(false.B)
  // WFI Timeout: 2^20 = 1M cycles
  val wfi_cycles = RegInit(0.U(20.W))
  val wfi_timeout = wfi_cycles.andR

  when (hasWFI) {
    wfi_cycles := wfi_cycles + 1.U
  }.elsewhen (!hasWFI && RegNext(hasWFI)) {
    wfi_cycles := 0.U
  }

  when (!io.wfi_enable) {
    hasWFI := false.B
  }

  when (RegNext(RegNext(io.csr.wfiEvent)) || wfi_timeout) {
    hasWFI := false.B
  }

  io.cpu_halt := hasWFI

  //************************ptr move************************
  val allocatePtrVec = VecInit((0 until RenameWidth).map(
    i => enqPtrVec(PopCount(io.enq.needAlloc.take(i)))
  ))
  io.enq.canAccept := allowEnqueue && !hasBlockBackward
  io.enq.resp      := allocatePtrVec
  val canEnqueue = VecInit(io.enq.req.map(_.valid && io.enq.canAccept))
  val timer = GTimer()
  for (i <- 0 until RenameWidth) {
    // we don't check whether io.redirect is valid here since redirect has higher priority
    when (canEnqueue(i)) {
      val enqUop = io.enq.req(i).bits
      val enqIndex = allocatePtrVec(i).value
      // store uop in data module and debug_microOp Vec
      // TODO: debug logic need to update
      debug_microOp(enqIndex) := enqUop
      debug_microOp(enqIndex).debugInfo.dispatchTime  := timer
      debug_microOp(enqIndex).debugInfo.enqRsTime     := timer
      debug_microOp(enqIndex).debugInfo.selectTime    := timer
      debug_microOp(enqIndex).debugInfo.issueTime     := timer
      debug_microOp(enqIndex).debugInfo.writebackTime := timer
      microOpPerfInfo(enqIndex) := Cat(enqUop.ctrl.isMove, enqUop.cf.loadWaitBit).asTypeOf(microOpPerfInfo.t)
      // for func
      when (enqUop.ctrl.blockBackward) {
        hasBlockBackward := true.B
      }
      when (enqUop.ctrl.noSpecExec) {
        hasNoSpecExec := true.B
      }
      val enqHasTriggerCanFire  = io.enq.req(i).bits.cf.trigger.getFrontendCanFire
      val enqHasException       = ExceptionNO.selectFrontend(enqUop.cf.exceptionVec).asUInt.orR
      when (enqUop.ctrl.isWFI && !enqHasException && !enqHasTriggerCanFire) {
        hasWFI := true.B
      }
    }
  }
  val enqNum = Mux(io.enq.canAccept, PopCount(io.enq.req.map(_.valid)), 0.U)
  io.enq.isEmpty := RegNext(isEmpty && !VecInit(io.enq.req.map(_.valid)).asUInt.orR)

  /**
    * ************************Writeback (from wbNet and MergeBuffer)************************
    */
  // diplomacy port wb and
  // writeback logic set numWbPorts writebacked to true
  for ((cfg, wb) <- writebackIn) {
    when (wb.valid) {
      val wbIdx = wb.bits.uop.robIdx.value
      val wbHasException = ExceptionNO.selectByExu(wb.bits.uop.cf.exceptionVec, cfg).asUInt.orR
      val wbHasTriggerCanFire = if (cfg.trigger) wb.bits.uop.cf.trigger.getBackendCanFire else false.B
      val block_wb = wbHasException || wbHasTriggerCanFire
      writebacked(wbIdx) := !block_wb
    }
  }

  for (wb <- wbPorts) {
    when (wb.valid) {
      val wbIdx = wb.bits.uop.robIdx.value
      debug_exuData(wbIdx) := wb.bits.data
      debug_exuDebug(wbIdx) := wb.bits.debug
      debug_microOp(wbIdx).debugInfo.enqRsTime := wb.bits.uop.debugInfo.enqRsTime
      debug_microOp(wbIdx).debugInfo.selectTime := wb.bits.uop.debugInfo.selectTime
      debug_microOp(wbIdx).debugInfo.issueTime := wb.bits.uop.debugInfo.issueTime
      debug_microOp(wbIdx).debugInfo.writebackTime := wb.bits.uop.debugInfo.writebackTime

      val debug_Uop = debug_microOp(wbIdx)
      XSInfo(true.B,
        p"writebacked pc 0x${Hexadecimal(debug_Uop.cf.pc)} wen ${debug_Uop.ctrl.rfWen} " +
        p"data 0x${Hexadecimal(wb.bits.data)} ldst ${debug_Uop.ctrl.ldest} pdst ${debug_Uop.pdest} " +
        p"skip ${wb.bits.debug.isMMIO} robIdx: ${wb.bits.uop.robIdx}\n"
      )
    }
  }
  val writebackNumFromEXU = PopCount(wbPorts.map(_.valid))
  XSInfo(writebackNumFromEXU =/= 0.U, "exu writebacked %d insts\n", writebackNumFromEXU)

  // From MergeBuffer writeback, vmem/vpBlock/valu/vdiv/vfp/vmac
  //TODO:
  for(wb <- io.wbFromMergeBuffer) {
    when(wb.valid) {
      val wbIdx = wb.bits.uop.robIdx.value
      //val wbHasException = ExceptionNO.selectByExu(wb.bits.uop.cf.exceptionVec, cfg).asUInt.orR
      //val wbHasTriggerCanFire = if (cfg.trigger) wb.bits.uop.cf.trigger.getBackendCanFire else false.B
      //val block_wb = wbHasException || wbHasTriggerCanFire
      writebacked(wbIdx) := true.B
    }
  }

  for(wb <- io.wbFromMergeBuffer) {
    when(wb.valid) {
      val wbIdx = wb.bits.uop.robIdx.value
      debug_exuData(wbIdx) := wb.bits.data
      debug_exuDebug(wbIdx) := wb.bits.debug
      debug_microOp(wbIdx).debugInfo.enqRsTime := wb.bits.uop.debugInfo.enqRsTime
      debug_microOp(wbIdx).debugInfo.selectTime := wb.bits.uop.debugInfo.selectTime
      debug_microOp(wbIdx).debugInfo.issueTime := wb.bits.uop.debugInfo.issueTime
      debug_microOp(wbIdx).debugInfo.writebackTime := wb.bits.uop.debugInfo.writebackTime

      val debug_Uop = debug_microOp(wbIdx)
      XSInfo(true.B,
        p"writebacked pc 0x${Hexadecimal(debug_Uop.cf.pc)} wen ${debug_Uop.ctrl.rfWen} " +
        p"data 0x${Hexadecimal(wb.bits.data)} ldst ${debug_Uop.ctrl.ldest} pdst ${debug_Uop.pdest} " +
        p"skip ${wb.bits.debug.isMMIO} robIdx: ${wb.bits.uop.robIdx}\n"
      )
    }
  }
  val writebackNumFromMerge = PopCount(io.wbFromMergeBuffer.map(_.valid))
  XSInfo(writebackNumFromMerge =/= 0.U, "MergeBuffer writebacked %d insts\n", writebackNumFromMerge)

  /**
    * ************************RedirectOut: Interrupt and Exceptions************************
    */
  val deqEntryData = entryDataRead(0)
  val debug_deqUop = debug_microOp(deqPtr.value)

  val intrBitSetReg = RegNext(io.csr.intrBitSet)
  val intrEnable    = intrBitSetReg && !hasNoSpecExec && interrupt_safe(deqPtr.value)
  val deqHasException = exceptionDataRead.valid && (exceptionDataRead.bits.robIdx === deqPtr)
  val exceptionEnable = writebacked(deqPtr.value) && deqHasException

  XSDebug(deqHasException && exceptionDataRead.bits.singleStep,                 "Debug Mode: Deq has singlestep exception\n")
  XSDebug(deqHasException && exceptionDataRead.bits.trigger.getFrontendCanFire, "Debug Mode: Deq has frontend trigger exception\n")
  XSDebug(deqHasException && exceptionDataRead.bits.trigger.getBackendCanFire,  "Debug Mode: Deq has backend trigger exception\n")

  val exceptionHappen = (state === s_idle) && valid(deqPtr.value) && (intrEnable || exceptionEnable)
  val redirectDelay1  = Pipe(io.redirect)
  val exceptionWaitingRedirect = RegInit(false.B)

  when(io.exception.valid) {
    exceptionWaitingRedirect := true.B
  }.elsewhen(redirectDelay1.valid && redirectDelay1.bits.isException) {
    exceptionWaitingRedirect := false.B
  }

  io.exception.valid := RegNext(exceptionHappen, false.B) && !exceptionWaitingRedirect
  io.exception.bits.uop := RegEnable(debug_deqUop, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.uop.ctrl.commitType := RegEnable(deqEntryData.commitType, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.uop.cf.exceptionVec := RegEnable(exceptionDataRead.bits.exceptionVec, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.uop.ctrl.singleStep := RegEnable(exceptionDataRead.bits.singleStep, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.uop.cf.crossPageIPFFix := RegEnable(exceptionDataRead.bits.crossPageIPFFix, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.isInterrupt := RegEnable(intrEnable, exceptionHappen && !exceptionWaitingRedirect)
  io.exception.bits.uop.cf.trigger := RegEnable(exceptionDataRead.bits.trigger, exceptionHappen && !exceptionWaitingRedirect)

  /**
    * ************************Commits (and walk)************************
    * They share the same width.
    */


  // extra space is used when rob has no enough space, but mispredict recovery needs such info to walk regmap
  require(RenameWidth <= CommitWidth)
  val extraSpaceForMPR  = Reg(Vec(RenameWidth, new RobCommitInfo))
  val usedSpaceForMPR   = Reg(Vec(RenameWidth, Bool()))
  when (io.enq.needAlloc.asUInt.orR && io.redirect.valid) {
    usedSpaceForMPR := io.enq.needAlloc
    extraSpaceForMPR.zip(entryDataModule.io.wdata).foreach(
      d => d._1.connectEntryData(d._2)
    )
    extraSpaceForMPR.zip(io.enq.req.map(_.bits)).foreach {
      case (wdata, req) => wdata.pc := req.cf.pc
    }
    XSDebug("rob full, switched to s_extrawalk. needExtraSpaceForMPR: %b\n", io.enq.needAlloc.asUInt)
  }

  // wiring to csr
  val (wflags, fpWen) = (0 until CommitWidth).map(
    i => {
      val v = io.commits.commitValid(i)
      val info = io.commits.info(i)
      (v & info.wflags, v & info.fpWen)
    }
  ).unzip

  val wvcsr = (0 until CommitWidth).map(
    i => {
      val v = io.commits.commitValid(i)
      val info = io.commits.info(i)
      v & info.wvcsr
    }
  )

  val fflags  = Wire(ValidIO(UInt(5.W)))
  val vxsat   = Wire(ValidIO(Bool()))

  fflags.valid  := io.commits.isCommit && VecInit(wflags).asUInt.orR
  fflags.bits   := wflags.zip(csrDataRead).map({
    case (w, f) => Mux(w, f.fflags, 0.U)
  }).reduce(_|_)

  vxsat.valid := io.commits.isCommit && VecInit(wvcsr).asUInt.orR
  vxsat.bits := wvcsr.zip(csrDataRead).map({
    case (w, c) => Mux(w, c.vxsat, 0.U)
  }).reduce(_|_)

  val dirty_fs = io.commits.isCommit && VecInit(fpWen).asUInt.orR
  val blockCommit = hasWFI || exceptionWaitingRedirect

  // Commit
  io.commits.isWalk   := (state =/= s_idle)
  io.commits.isCommit := (state === s_idle) && (!blockCommit)
  val walkCounter = Reg(UInt(log2Up(RobSize + 1).W))
  val shouldWalkVec = VecInit((0 until CommitWidth).map(
    i => (i.U < walkCounter)
  ))
  val walkFinished = walkCounter <= CommitWidth.U
  val walk_v    = VecInit(walkPtrVec.map(ptr => valid(ptr.value)))
  val commit_v  = VecInit(deqPtrVec.map(ptr => valid(ptr.value)))
  val commit_w  = VecInit(deqPtrVec.map(ptr => writebacked(ptr.value)))
  val commit_exception = exceptionDataRead.valid && !isAfter(exceptionDataRead.bits.robIdx, deqPtrVec.last)
  val commit_block = VecInit((0 until CommitWidth).map(i => !commit_w(i)))
  val allowOnlyOneCommit = commit_exception || intrBitSetReg
  // for instructions that may block others, we don't allow them to commit
  for (i <- 0 until CommitWidth) {
    // when intrBitSetReg, allow only one instruction to commit at each clock cycle
    val isBlocked = if (i != 0) {
        Cat(commit_block.take(i)).orR || allowOnlyOneCommit
      } else {
        intrEnable || deqHasException
      }
    io.commits.commitValid(i) := commit_v(i) && commit_w(i) && !isBlocked
    io.commits.walkValid(i)   := shouldWalkVec(i)
    io.commits.info(i).pc     := debug_microOp(deqPtrVec(i).value).cf.pc
    io.commits.info(i).connectEntryData(entryDataRead(i))
    io.commits.robIdx(i) := deqPtrVec(i).value

    when (io.commits.isWalk && state === s_walk && shouldWalkVec(i)) {
      XSError(!walk_v(i), s"why not $i???\n")
    }
    when (state === s_extrawalk) {
      if (i < RenameWidth) {
        io.commits.walkValid(i) := usedSpaceForMPR(RenameWidth - i - 1)
        io.commits.info(i)      := extraSpaceForMPR(RenameWidth - i - 1)
      } else {
        io.commits.walkValid(i) := false.B
      }
    }

    XSInfo(io.commits.isCommit && io.commits.commitValid(i),
      "retired pc %x wen %d ldest %d pdest %x old_pdest %x data %x fflags: %b\n",
      debug_microOp(deqPtrVec(i).value).cf.pc,
      io.commits.info(i).rfWen,
      io.commits.info(i).ldest,
      io.commits.info(i).pdest,
      io.commits.info(i).old_pdest,
      debug_exuData(deqPtrVec(i).value),
      csrDataRead(i).fflags
    )
    XSInfo(state === s_walk && io.commits.walkValid(i), "walked pc %x wen %d ldst %d data %x\n",
      debug_microOp(walkPtrVec(i).value).cf.pc,
      io.commits.info(i).rfWen,
      io.commits.info(i).ldest,
      debug_exuData(walkPtrVec(i).value)
    )
    XSInfo(state === s_extrawalk && io.commits.walkValid(i), "use extra space walked wen %d ldst %d\n",
      io.commits.info(i).rfWen,
      io.commits.info(i).ldest
    )
  }
  if (env.EnableDifftest) {
    io.commits.info.map(info => dontTouch(info.pc))
  }

  // ************************CSR************************
  // sync fflags/dirty_fs to csr
  io.csr.fflags   := Pipe(fflags)
  io.csr.dirty_fs := RegNext(dirty_fs, false.B)
  io.csr.vxsat    := Pipe(vxsat)
  io.csr.vstart.valid := exceptionGen.io.out.valid
  io.csr.vstart.bits  := csrDataRead(0).vstart

  //************************MemBlock************************
  // commit load/store to lsq
  val ldCommitVec = VecInit((0 until CommitWidth).map(
    i => io.commits.commitValid(i) && io.commits.info(i).commitType === CommitType.LOAD
  ))
  val stCommitVec = VecInit((0 until CommitWidth).map(
    i => io.commits.commitValid(i) && io.commits.info(i).commitType === CommitType.STORE
  ))
  io.lsq.lcommit := RegNext(Mux(io.commits.isCommit, PopCount(ldCommitVec), 0.U))
  io.lsq.scommit := RegNext(Mux(io.commits.isCommit, PopCount(stCommitVec), 0.U))
  // indicate a pending load or store
  io.lsq.pendingld := RegNext(io.commits.isCommit && io.commits.info(0).commitType === CommitType.LOAD && valid(deqPtr.value))
  io.lsq.pendingst := RegNext(io.commits.isCommit && io.commits.info(0).commitType === CommitType.STORE && valid(deqPtr.value))
  io.lsq.commit := RegNext(io.commits.isCommit && io.commits.commitValid(0))

  /**
    * state changes
    * (1) exceptions: when exception occurs, cancels all and switch to s_idle
    * (2) redirect: switch to s_walk or s_extrawalk (depends on whether there're pending instructions in dispatch1)
    * (3) walk: when walking comes to the end, switch to s_walk
    * (4) s_extrawalk to s_walk
    */
  // state === s_idle: don't change when walk_no_need
  // state === s_walk: don't change when walk_no_need && walkFinished
  // state === s_extrawalk: always continue to walk (because it's not possible for walk_no_need)
  val zeroWalkDistance = ((enqPtr - 1.U) === io.redirect.bits.robIdx) && (!io.redirect.bits.flushItself())
  val noNeedToWalk = zeroWalkDistance && ((state === s_idle) || (state === s_walk && walkFinished))
  // update the state depending on whether there is a redirect
  val state_next = Mux(io.redirect.valid,
    Mux(io.enq.needAlloc.asUInt.orR,
      s_extrawalk,
      Mux(noNeedToWalk, s_idle, s_walk)
    ),
    Mux(state === s_walk && walkFinished,
      s_idle,
      Mux(state === s_extrawalk,
        // if no more walk, switch to s_idle
        Mux(walkCounter === 0.U, s_idle, s_walk),
        state
      )
    )
  )
  state := state_next

  XSPerfAccumulate("s_idle_to_idle",            state === s_idle && state_next === s_idle)
  XSPerfAccumulate("s_idle_to_walk",            state === s_idle && state_next === s_walk)
  XSPerfAccumulate("s_idle_to_extrawalk",       state === s_idle && state_next === s_extrawalk)
  XSPerfAccumulate("s_walk_to_idle",            state === s_walk && state_next === s_idle)
  XSPerfAccumulate("s_walk_to_walk",            state === s_walk && state_next === s_walk)
  XSPerfAccumulate("s_walk_to_extrawalk",       state === s_walk && state_next === s_extrawalk)
  XSPerfAccumulate("s_extrawalk_to_idle",       state === s_extrawalk && state_next === s_idle)
  XSPerfAccumulate("s_extrawalk_to_walk",       state === s_extrawalk && state_next === s_walk)
  XSPerfAccumulate("s_extrawalk_to_extrawalk",  state === s_extrawalk && state_next === s_extrawalk)
  XSPerfAccumulate("redirect_bypass_to_idle",   io.redirect.valid && !io.enq.needAlloc.asUInt.orR && noNeedToWalk)
  XSPerfAccumulate("extra_walk_bypass_to_idle", !io.redirect.valid && state === s_extrawalk && walkCounter === 0.U)

  /**
    * pointers and counters
    */
  val deqPtrGenModule = Module(new RobDeqPtrWrapper)
  val deqPtrVec_next = deqPtrGenModule.io.next_out
  deqPtrGenModule.io.state := state
  deqPtrGenModule.io.deq_v := commit_v
  deqPtrGenModule.io.deq_w := commit_w
  deqPtrGenModule.io.exception_state  := exceptionDataRead
  deqPtrGenModule.io.intrBitSetReg    := intrBitSetReg
  deqPtrGenModule.io.hasNoSpecExec    := hasNoSpecExec
  deqPtrGenModule.io.interrupt_safe   := interrupt_safe(deqPtr.value)
  deqPtrGenModule.io.blockCommit      := blockCommit
  deqPtrVec := deqPtrGenModule.io.out

  val enqPtrGenModule = Module(new RobEnqPtrWrapper)
  enqPtrGenModule.io.redirect := io.redirect
  enqPtrGenModule.io.allowEnqueue     := allowEnqueue
  enqPtrGenModule.io.hasBlockBackward := hasBlockBackward
  enqPtrGenModule.io.enq := VecInit(io.enq.req.map(_.valid))
  enqPtrVec := enqPtrGenModule.io.out

  val thisCycleWalkCount = Mux(walkFinished, walkCounter, CommitWidth.U)
  // next walkPtrVec:
  // (1) redirect occurs: update according to state
  // (2) walk: move backwards
  val walkPtrVec_next = Mux(io.redirect.valid && state =/= s_extrawalk,
    Mux(state === s_walk,
      VecInit(walkPtrVec.map(_ - thisCycleWalkCount)),
      VecInit((0 until CommitWidth).map(i => enqPtr - (i+1).U))
    ),
    Mux(state === s_walk, VecInit(walkPtrVec.map(_ - CommitWidth.U)), walkPtrVec)
  )
  walkPtrVec := walkPtrVec_next

  val numValidEntries = distanceBetween(enqPtr, deqPtr)
  val commitCnt = PopCount(io.commits.commitValid)

  allowEnqueue := (numValidEntries + enqNum) <= (RobSize - RenameWidth).U

  val currentWalkPtr = Mux(state === s_walk || state === s_extrawalk, walkPtr, enqPtr - 1.U)
  val redirectWalkDistance = distanceBetween(currentWalkPtr, io.redirect.bits.robIdx)
  when (io.redirect.valid) {
    walkCounter := Mux(state === s_walk,
      // NOTE: +& is used here because:
      // When rob is full and the head instruction causes an exception,
      // the redirect robIdx is the deqPtr. In this case, currentWalkPtr is
      // enqPtr - 1.U and redirectWalkDistance is RobSize - 1.
      // Since exceptions flush the instruction itself, flushItSelf is true.B.
      // Previously we use `+` to count the walk distance and it causes overflows
      // when RobSize is power of 2. We change it to `+&` to allow walkCounter to be RobSize.
      // The width of walkCounter also needs to be changed.
      redirectWalkDistance - (thisCycleWalkCount - io.redirect.bits.flushItself()),
      redirectWalkDistance + io.redirect.bits.flushItself()
    )
    XSError(state === s_walk && thisCycleWalkCount < io.redirect.bits.flushItself(),
      p"walk distance error ($thisCycleWalkCount < ${io.redirect.bits.flushItself()}\n")
  }.elsewhen (state === s_walk) {
    walkCounter := walkCounter - thisCycleWalkCount
    XSInfo(p"rolling back: $enqPtr $deqPtr walk $walkPtr walkcnt $walkCounter\n")
  }


  /**
    * States
    * We put all the stage bits changes here.
    *
    * ----------------------------------------
    * |All events: (1) enqueue (rename);     |
    * |            (2) writeback;            |
    * |            (3) cancel;               |
    * |            (4) dequeue (commit);     |
    * ----------------------------------------
    *
    * ----------------------------------------
    * |All states: (1) valid;                |
    * |            (2) writebacked;          |
    * |            (3) flagBkup              |
    * ----------------------------------------
    */
  val commitReadAddr = Mux(state === s_idle, VecInit(deqPtrVec.map(_.value)), VecInit(walkPtrVec.map(_.value)))

  // enqueue logic writes 6 valid
  for (i <- 0 until RenameWidth) {
    when (canEnqueue(i) && !io.redirect.valid) {
      valid(allocatePtrVec(i).value) := true.B
    }
  }
  // dequeue/walk logic writes 6 valid, dequeue and walk will not happen at the same time
  for (i <- 0 until CommitWidth) {
    val commitValid = io.commits.isCommit && io.commits.commitValid(i)
    val walkValid = io.commits.isWalk && io.commits.walkValid(i) && state =/= s_extrawalk
    when (commitValid || walkValid) {
      valid(commitReadAddr(i)) := false.B
    }
  }

  // status field: writebacked
  // enqueue logic set 6 writebacked to false
  for (i <- 0 until RenameWidth) {
    when (canEnqueue(i)) {
      val enqHasException = ExceptionNO.selectFrontend(io.enq.req(i).bits.cf.exceptionVec).asUInt.orR
      val enqHasTriggerCanFire = io.enq.req(i).bits.cf.trigger.getFrontendCanFire
      val enqIsWritebacked = io.enq.req(i).bits.eliminatedMove
      writebacked(allocatePtrVec(i).value) := enqIsWritebacked && !enqHasException && !enqHasTriggerCanFire
      //val isStu = io.enq.req(i).bits.ctrl.fuType === FuType.stu
      //store_data_writebacked(allocatePtrVec(i).value) := !isStu
    }
  }
  when (exceptionGen.io.out.valid) {
    val wbIdx = exceptionGen.io.out.bits.robIdx.value
    writebacked(wbIdx) := true.B
    //store_data_writebacked(wbIdx) := true.B
  }

  // flagBkup
  // enqueue logic set 6 flagBkup at most
  for (i <- 0 until RenameWidth) {
    when (canEnqueue(i)) {
      flagBkup(allocatePtrVec(i).value) := allocatePtrVec(i).flag
    }
  }

  // interrupt_safe
  for (i <- 0 until RenameWidth) {
    // We RegNext the updates for better timing.
    // Note that instructions won't change the system's states in this cycle.
    when (RegNext(canEnqueue(i))) {
      // For now, we allow non-load-store instructions to trigger interrupts
      // For MMIO instructions, they should not trigger interrupts since they may
      // be sent to lower level before it writes back.
      // However, we cannot determine whether a load/store instruction is MMIO.
      // Thus, we don't allow load/store instructions to trigger an interrupt.
      // TODO: support non-MMIO load-store instructions to trigger interrupts
      val allow_interrupts = !CommitType.isLoadStore(io.enq.req(i).bits.ctrl.commitType)
      interrupt_safe(RegNext(allocatePtrVec(i).value)) := RegNext(allow_interrupts)
    }
  }

  /*
  * read and write of data modules
  *
  * Read:
      if(commit)           -->  rob.entry.read(deqPtrVec_next)
      else, state is walk, -->  rob.entry.read(walkPtrVec_next)
  */
  private val commitReadAddr_next = Mux(state_next === s_idle,
    VecInit(deqPtrVec_next.map(_.value)),
    VecInit(walkPtrVec_next.map(_.value))
  )
  entryDataModule.io.raddr  := commitReadAddr_next
  // for((crp, value) <- io.commits.robIdx.zip(commitReadAddr_next)) {
  //   crp := value
  // }

  entryDataModule.io.wen    := canEnqueue
  entryDataModule.io.waddr  := allocatePtrVec.map(_.value)
  entryDataModule.io.wdata.zip(io.enq.req.map(_.bits)).foreach {
    case (wdata, req) =>
      wdata.ldest       := req.ctrl.ldest
      wdata.rfWen       := req.ctrl.rfWen
      wdata.fpWen       := req.ctrl.fpWen
      wdata.wflags      := req.ctrl.fpu.wflags
      wdata.commitType  := req.ctrl.commitType
      wdata.pdest       := req.pdest
      wdata.old_pdest   := req.old_pdest
      wdata.ftqIdx      := req.cf.ftqPtr
      wdata.ftqOffset   := req.cf.ftqOffset
      wdata.vecWen      := req.ctrl.isVector
      wdata.wvcsr       := false.B
      wdata.vtypeWb     := req.ctrl.isVtype
      wdata.isVector    := req.ctrl.isVector && !req.ctrl.isVtype
  }

  for(i <- 0 until fflagsWbNums) {
    csrDataModule.io.wen(i)           := fflags_wb(i).valid
    csrDataModule.io.waddr(i)         := fflags_wb(i).bits.uop.robIdx.value
    csrDataModule.io.wdata(i).fflags  := fflags_wb(i).bits.fflags
    csrDataModule.io.wdata(i).vstart  := 0.U
    csrDataModule.io.wdata(i).vxsat   := 0.U
  }
  for(i <- 0 until VectorMergeWbWidth) {
    csrDataModule.io.wen(fflagsWbNums + i)           := io.wbFromMergeBuffer(i).valid
    csrDataModule.io.waddr(fflagsWbNums + i)         := io.wbFromMergeBuffer(i).bits.uop.robIdx.value
    csrDataModule.io.wdata(fflagsWbNums + i).fflags  := 0.U
    csrDataModule.io.wdata(fflagsWbNums + i).vstart  := io.wbFromMergeBuffer(i).bits.uop.uopIdx
    csrDataModule.io.wdata(fflagsWbNums + i).vxsat   := io.wbFromMergeBuffer(i).bits.vxsat
  }
  csrDataModule.io.raddr := VecInit(deqPtrVec_next.map(_.value))

  /*
  * Exception
  *
  * writeback connect io.wb---
  *                           |
  *                           ---> ExceptionGen ---> (writebacked(io.out.XXX.robIdx) := true.B)
  *                           |
  * rename connect io.enq-----
  *
  */
  exceptionGen.io.redirect <> io.redirect
  for (i <- 0 until RenameWidth) {
    exceptionGen.io.enq(i).valid := canEnqueue(i)
    exceptionGen.io.enq(i).bits.robIdx := io.enq.req(i).bits.robIdx
    exceptionGen.io.enq(i).bits.exceptionVec := ExceptionNO.selectFrontend(io.enq.req(i).bits.cf.exceptionVec)
    XSError(canEnqueue(i) && io.enq.req(i).bits.ctrl.replayInst, "enq should not set replayInst")
    exceptionGen.io.enq(i).bits.singleStep      := io.enq.req(i).bits.ctrl.singleStep
    exceptionGen.io.enq(i).bits.crossPageIPFFix := io.enq.req(i).bits.cf.crossPageIPFFix
    exceptionGen.io.enq(i).bits.trigger.clear() // Don't care frontend timing and chain, backend hit and canFire
    exceptionGen.io.enq(i).bits.trigger.frontendHit := io.enq.req(i).bits.cf.trigger.frontendHit
    exceptionGen.io.enq(i).bits.trigger.frontendCanFire := io.enq.req(i).bits.cf.trigger.frontendCanFire
  }

  println(s"ExceptionGen:")
  for ((((config, wb), exc_wb), i) <- wbWithException.zip(exceptionGen.io.wb).zipWithIndex) {
    exc_wb.valid                := wb.valid
    exc_wb.bits.robIdx          := wb.bits.uop.robIdx
    exc_wb.bits.exceptionVec    := ExceptionNO.selectByExu(wb.bits.uop.cf.exceptionVec, config)
    exc_wb.bits.singleStep      := false.B
    exc_wb.bits.crossPageIPFFix := false.B
    // TODO: make trigger configurable
    exc_wb.bits.trigger.clear() // Don't care frontend timing, chain, hit and canFire
    exc_wb.bits.trigger.backendHit := Mux(config.trigger.B, wb.bits.uop.cf.trigger.backendHit,
      0.U.asTypeOf(chiselTypeOf(exc_wb.bits.trigger.backendHit)))
    exc_wb.bits.trigger.backendCanFire := Mux(config.trigger.B, wb.bits.uop.cf.trigger.backendCanFire,
      0.U.asTypeOf(chiselTypeOf(exc_wb.bits.trigger.backendCanFire)))
    println(s"  [$i] ${config.name}: exception ${config.exceptionOut}")
  }

  val instrCntReg   = RegInit(0.U(64.W))
  val fuseCommitCnt = PopCount(io.commits.commitValid.zip(io.commits.info).map{ case (v, i) => RegNext(v && CommitType.isFused(i.commitType)) })
  val trueCommitCnt = RegNext(commitCnt) +& fuseCommitCnt
  val retireCounter = Mux(RegNext(io.commits.isCommit), trueCommitCnt, 0.U)
  val instrCnt = instrCntReg + retireCounter
  instrCntReg := instrCnt
  io.csr.perfinfo.retiredInstr := retireCounter
  io.robFull := !allowEnqueue

  /**
    * debug info
    */
  XSDebug(p"enqPtr ${enqPtr} deqPtr ${deqPtr}\n")
  XSDebug("")
  for(i <- 0 until RobSize){
    XSDebug(false, !valid(i), "-")
    XSDebug(false, valid(i) && writebacked(i), "w")
    XSDebug(false, valid(i) && !writebacked(i), "v")
  }
  XSDebug(false, true.B, "\n")

  for(i <- 0 until RobSize) {
    if(i % 4 == 0) XSDebug("")
    XSDebug(false, true.B, "%x ", debug_microOp(i).cf.pc)
    XSDebug(false, !valid(i), "- ")
    XSDebug(false, valid(i) && writebacked(i), "w ")
    XSDebug(false, valid(i) && !writebacked(i), "v ")
    if(i % 4 == 3) XSDebug(false, true.B, "\n")
  }

  def ifCommit(counter: UInt): UInt = Mux(io.commits.isCommit, counter, 0.U)
  def ifCommitReg(counter: UInt): UInt = Mux(RegNext(io.commits.isCommit), counter, 0.U)

  val commitDebugUop = deqPtrVec.map(_.value).map(debug_microOp(_))
  XSPerfAccumulate("clock_cycle", 1.U)
  QueuePerf(RobSize, PopCount((0 until RobSize).map(valid(_))), !allowEnqueue)
  XSPerfAccumulate("commitUop",   ifCommit(commitCnt))
  XSPerfAccumulate("commitInstr", ifCommitReg(trueCommitCnt))
  val commitIsMove = commitDebugUop.map(_.ctrl.isMove)
  XSPerfAccumulate("commitInstrMove", ifCommit(PopCount(io.commits.commitValid.zip(commitIsMove).map{ case (v, m) => v && m })))
  val commitMoveElim = commitDebugUop.map(_.debugInfo.eliminatedMove)
  XSPerfAccumulate("commitInstrMoveElim", ifCommit(PopCount(io.commits.commitValid zip commitMoveElim map { case (v, e) => v && e })))
  XSPerfAccumulate("commitInstrFused",    ifCommitReg(fuseCommitCnt))
  val commitIsLoad    = io.commits.info.map(_.commitType).map(_ === CommitType.LOAD)
  val commitLoadValid = io.commits.commitValid.zip(commitIsLoad).map{ case (v, t) => v && t }
  XSPerfAccumulate("commitInstrLoad", ifCommit(PopCount(commitLoadValid)))
  val commitIsBranch    = io.commits.info.map(_.commitType).map(_ === CommitType.BRANCH)
  val commitBranchValid = io.commits.commitValid.zip(commitIsBranch).map{ case (v, t) => v && t }
  XSPerfAccumulate("commitInstrBranch", ifCommit(PopCount(commitBranchValid)))
  val commitLoadWaitBit = commitDebugUop.map(_.cf.loadWaitBit)
  XSPerfAccumulate("commitInstrLoadWait", ifCommit(PopCount(commitLoadValid.zip(commitLoadWaitBit).map{ case (v, w) => v && w })))
  val commitIsStore = io.commits.info.map(_.commitType).map(_ === CommitType.STORE)
  XSPerfAccumulate("commitInstrStore",  ifCommit(PopCount(io.commits.commitValid.zip(commitIsStore).map{ case (v, t) => v && t })))
  XSPerfAccumulate("writeback",         PopCount((0 until RobSize).map(i => valid(i) && writebacked(i))))
  // XSPerfAccumulate("enqInstr", PopCount(io.dp1Req.map(_.fire)))
  // XSPerfAccumulate("d2rVnR", PopCount(io.dp1Req.map(p => p.valid && !p.ready)))
  XSPerfAccumulate("walkInstr", Mux(io.commits.isWalk, PopCount(io.commits.walkValid), 0.U))
  XSPerfAccumulate("walkCycle", state === s_walk || state === s_extrawalk)
  val deqNotWritebacked = valid(deqPtr.value) && !writebacked(deqPtr.value)
  val deqUopCommitType  = io.commits.info(0).commitType
  XSPerfAccumulate("waitNormalCycle", deqNotWritebacked && deqUopCommitType === CommitType.NORMAL)
  XSPerfAccumulate("waitBranchCycle", deqNotWritebacked && deqUopCommitType === CommitType.BRANCH)
  XSPerfAccumulate("waitLoadCycle",   deqNotWritebacked && deqUopCommitType === CommitType.LOAD)
  XSPerfAccumulate("waitStoreCycle",  deqNotWritebacked && deqUopCommitType === CommitType.STORE)
  XSPerfAccumulate("robHeadPC",       io.commits.info(0).pc)
  val dispatchLatency = commitDebugUop.map(uop => uop.debugInfo.dispatchTime - uop.debugInfo.renameTime)
  val enqRsLatency    = commitDebugUop.map(uop => uop.debugInfo.enqRsTime - uop.debugInfo.dispatchTime)
  val selectLatency   = commitDebugUop.map(uop => uop.debugInfo.selectTime - uop.debugInfo.enqRsTime)
  val issueLatency    = commitDebugUop.map(uop => uop.debugInfo.issueTime - uop.debugInfo.selectTime)
  val executeLatency  = commitDebugUop.map(uop => uop.debugInfo.writebackTime - uop.debugInfo.issueTime)
  val rsFuLatency     = commitDebugUop.map(uop => uop.debugInfo.writebackTime - uop.debugInfo.enqRsTime)
  val commitLatency   = commitDebugUop.map(uop => timer - uop.debugInfo.writebackTime)
  def latencySum(cond: Seq[Bool], latency: Seq[UInt]): UInt = {
    cond.zip(latency).map(x => Mux(x._1, x._2, 0.U)).reduce(_ +& _)
  }
  for (fuType <- FuType.functionNameMap.keys) {
    val fuName = FuType.functionNameMap(fuType)
    val commitIsFuType = io.commits.commitValid.zip(commitDebugUop).map(x => x._1 && x._2.ctrl.fuType === fuType.U )
    XSPerfAccumulate(s"${fuName}_instr_cnt",        ifCommit(PopCount(commitIsFuType)))
    XSPerfAccumulate(s"${fuName}_latency_dispatch", ifCommit(latencySum(commitIsFuType, dispatchLatency)))
    XSPerfAccumulate(s"${fuName}_latency_enq_rs",   ifCommit(latencySum(commitIsFuType, enqRsLatency)))
    XSPerfAccumulate(s"${fuName}_latency_select",   ifCommit(latencySum(commitIsFuType, selectLatency)))
    XSPerfAccumulate(s"${fuName}_latency_issue",    ifCommit(latencySum(commitIsFuType, issueLatency)))
    XSPerfAccumulate(s"${fuName}_latency_execute",  ifCommit(latencySum(commitIsFuType, executeLatency)))
    XSPerfAccumulate(s"${fuName}_latency_enq_rs_execute", ifCommit(latencySum(commitIsFuType, rsFuLatency)))
    XSPerfAccumulate(s"${fuName}_latency_commit",         ifCommit(latencySum(commitIsFuType, commitLatency)))
    if (fuType == FuType.fmac.litValue) {
      val commitIsFma = commitIsFuType.zip(commitDebugUop).map(x => x._1 && x._2.ctrl.fpu.ren3 )
      XSPerfAccumulate(s"${fuName}_instr_cnt_fma",              ifCommit(PopCount(commitIsFma)))
      XSPerfAccumulate(s"${fuName}_latency_enq_rs_execute_fma", ifCommit(latencySum(commitIsFma, rsFuLatency)))
      XSPerfAccumulate(s"${fuName}_latency_execute_fma",        ifCommit(latencySum(commitIsFma, executeLatency)))
    }
  }

  //difftest signals
  val firstValidCommit = (deqPtr + PriorityMux(io.commits.commitValid, VecInit(List.tabulate(CommitWidth)(_.U(log2Up(CommitWidth).W))))).value

  val wdata = Wire(Vec(CommitWidth, UInt(XLEN.W)))
  val wpc = Wire(Vec(CommitWidth, UInt(XLEN.W)))

  for(i <- 0 until CommitWidth) {
    val idx = deqPtrVec(i).value
    wdata(i) := debug_exuData(idx)
    wpc(i) := SignExt(commitDebugUop(i).cf.pc, XLEN)
  }

  if (env.EnableDifftest) {
    for (i <- 0 until CommitWidth) {
      val difftest = Module(new DifftestInstrCommit)
      difftest.io.clock    := clock
      difftest.io.coreid   := io.hartId
      difftest.io.index    := i.U

      val ptr = deqPtrVec(i).value
      val uop = commitDebugUop(i)
      val exuOut = debug_exuDebug(ptr)
      val exuData = debug_exuData(ptr)
      difftest.io.valid    := RegNext(RegNext(RegNext(io.commits.commitValid(i) && io.commits.isCommit)))
      difftest.io.pc       := RegNext(RegNext(RegNext(Mux(io.mmuEnable, SignExt(uop.cf.pc, XLEN), ZeroExt(uop.cf.pc, XLEN)))))
      difftest.io.instr    := RegNext(RegNext(RegNext(uop.cf.instr)))
      difftest.io.special  := RegNext(RegNext(RegNext(CommitType.isFused(io.commits.info(i).commitType))))
      // when committing an eliminated move instruction,
      // we must make sure that skip is properly set to false (output from EXU is random value)
      difftest.io.skip     := RegNext(RegNext(RegNext(Mux(uop.eliminatedMove, false.B, exuOut.isMMIO || exuOut.isPerfCnt))))
      difftest.io.isRVC    := RegNext(RegNext(RegNext(uop.cf.pd.isRVC)))
      difftest.io.rfwen    := RegNext(RegNext(RegNext(io.commits.commitValid(i) && io.commits.info(i).rfWen && io.commits.info(i).ldest =/= 0.U)))
      difftest.io.fpwen    := RegNext(RegNext(RegNext(io.commits.commitValid(i) && io.commits.info(i).fpWen)))
      difftest.io.wpdest   := RegNext(RegNext(RegNext(io.commits.info(i).pdest)))
      difftest.io.wdest    := RegNext(RegNext(RegNext(io.commits.info(i).ldest)))

      // runahead commit hint
      val runahead_commit = Module(new DifftestRunaheadCommitEvent)
      runahead_commit.io.clock := clock
      runahead_commit.io.coreid := io.hartId
      runahead_commit.io.index := i.U
      runahead_commit.io.valid := difftest.io.valid &&
        (commitBranchValid(i) || commitIsStore(i))
      // TODO: is branch or store
      runahead_commit.io.pc    := difftest.io.pc
    }
  }
  else if (env.AlwaysBasicDiff) {
    // These are the structures used by difftest only and should be optimized after synthesis.
    val dt_eliminatedMove = Mem(RobSize, Bool())
    val dt_isRVC = Mem(RobSize, Bool())
    val dt_exuDebug = Reg(Vec(RobSize, new DebugBundle))
    for (i <- 0 until RenameWidth) {
      when (canEnqueue(i)) {
        dt_eliminatedMove(allocatePtrVec(i).value) := io.enq.req(i).bits.eliminatedMove
        dt_isRVC(allocatePtrVec(i).value) := io.enq.req(i).bits.cf.pd.isRVC
      }
    }
    for (wb <- wbPorts) {
      when (wb.valid) {
        val wbIdx = wb.bits.uop.robIdx.value
        dt_exuDebug(wbIdx) := wb.bits.debug
      }
    }
    // Always instantiate basic difftest modules.
    for (i <- 0 until CommitWidth) {
      val commitInfo = io.commits.info(i)
      val ptr = deqPtrVec(i).value
      val exuOut = dt_exuDebug(ptr)
      val eliminatedMove = dt_eliminatedMove(ptr)
      val isRVC = dt_isRVC(ptr)

      val difftest = Module(new DifftestBasicInstrCommit)
      difftest.io.clock   := clock
      difftest.io.coreid  := io.hartId
      difftest.io.index   := i.U
      difftest.io.valid   := RegNext(RegNext(RegNext(io.commits.commitValid(i) && io.commits.isCommit)))
      difftest.io.special := RegNext(RegNext(RegNext(CommitType.isFused(commitInfo.commitType))))
      difftest.io.skip    := RegNext(RegNext(RegNext(Mux(eliminatedMove, false.B, exuOut.isMMIO || exuOut.isPerfCnt))))
      difftest.io.isRVC   := RegNext(RegNext(RegNext(isRVC)))
      difftest.io.rfwen   := RegNext(RegNext(RegNext(io.commits.commitValid(i) && commitInfo.rfWen && commitInfo.ldest =/= 0.U)))
      difftest.io.fpwen   := RegNext(RegNext(RegNext(io.commits.commitValid(i) && commitInfo.fpWen)))
      difftest.io.wpdest  := RegNext(RegNext(RegNext(commitInfo.pdest)))
      difftest.io.wdest   := RegNext(RegNext(RegNext(commitInfo.ldest)))
    }
  }

  if (env.EnableDifftest) {
    for (i <- 0 until CommitWidth) {
      val difftest = Module(new DifftestLoadEvent)
      difftest.io.clock  := clock
      difftest.io.coreid := io.hartId
      difftest.io.index  := i.U

      val ptr = deqPtrVec(i).value
      val uop = commitDebugUop(i)
      val exuOut = debug_exuDebug(ptr)
      difftest.io.valid  := RegNext(RegNext(RegNext(io.commits.commitValid(i) && io.commits.isCommit)))
      difftest.io.paddr  := RegNext(RegNext(RegNext(exuOut.paddr)))
      difftest.io.opType := RegNext(RegNext(RegNext(uop.ctrl.fuOpType)))
      difftest.io.fuType := RegNext(RegNext(RegNext(uop.ctrl.fuType)))
    }
  }

  // Always instantiate basic difftest modules.
  if (env.EnableDifftest) {
    val dt_isXSTrap = Mem(RobSize, Bool())
    for (i <- 0 until RenameWidth) {
      when (canEnqueue(i)) {
        dt_isXSTrap(allocatePtrVec(i).value) := io.enq.req(i).bits.ctrl.isXSTrap
      }
    }
    val trapVec = io.commits.commitValid.zip(deqPtrVec).map{ case (v, d) => io.commits.isCommit && v && dt_isXSTrap(d.value) }
    val hitTrap = trapVec.reduce(_||_)
    val trapCode = PriorityMux(wdata.zip(trapVec).map(x => x._2 -> x._1))
    val trapPC = SignExt(PriorityMux(wpc.zip(trapVec).map(x => x._2 ->x._1)), XLEN)
    val difftest = Module(new DifftestTrapEvent)
    difftest.io.clock    := clock
    difftest.io.coreid   := io.hartId
    difftest.io.valid    := hitTrap
    difftest.io.code     := trapCode
    difftest.io.pc       := trapPC
    difftest.io.cycleCnt := timer
    difftest.io.instrCnt := instrCnt
    difftest.io.hasWFI   := hasWFI
  }
  else if (env.AlwaysBasicDiff) {
    val dt_isXSTrap = Mem(RobSize, Bool())
    for (i <- 0 until RenameWidth) {
      when (canEnqueue(i)) {
        dt_isXSTrap(allocatePtrVec(i).value) := io.enq.req(i).bits.ctrl.isXSTrap
      }
    }
    val trapVec = io.commits.commitValid.zip(deqPtrVec).map{ case (v, d) => io.commits.isCommit && v && dt_isXSTrap(d.value) }
    val hitTrap = trapVec.reduce(_||_)
    val difftest = Module(new DifftestBasicTrapEvent)
    difftest.io.clock    := clock
    difftest.io.coreid   := io.hartId
    difftest.io.valid    := hitTrap
    difftest.io.cycleCnt := timer
    difftest.io.instrCnt := instrCnt
  }

  val microOpPerfInfoAtDeqPtr = deqPtrVec.map(_.value).map(microOpPerfInfo(_))
  val commitIsMoveImpl = microOpPerfInfoAtDeqPtr.map(_.isMove)
  val commitLoadWaitBitImpl = microOpPerfInfoAtDeqPtr.map(_.loadWaitBit)

  val validEntriesBanks = (0 until (RobSize + 63) / 64).map(i => RegNext(PopCount(valid.drop(i * 64).take(64))))
  val validEntries = RegNext(ParallelOperation(validEntriesBanks, (a: UInt, b: UInt) => a +& b))
  val commitMoveVec = VecInit(io.commits.commitValid.zip(commitIsMoveImpl).map{ case (v, m) => v && m })
  val commitLoadVec = VecInit(commitLoadValid)
  val commitBranchVec = VecInit(commitBranchValid)
  val commitLoadWaitVec = VecInit(commitLoadValid.zip(commitLoadWaitBitImpl).map{ case (v, w) => v && w })
  val commitStoreVec = VecInit(io.commits.commitValid.zip(commitIsStore).map{ case (v, t) => v && t })
  val perfEvents = Seq(
    ("rob_interrupt_num      ", io.exception.valid && intrEnable                                       ),
    ("rob_exception_num      ", io.exception.valid && exceptionEnable                                  ),
    ("rob_commitUop          ", ifCommit(commitCnt)                                                   ),
    ("rob_commitInstr        ", ifCommitReg(trueCommitCnt)                                            ),
    ("rob_commitInstrMove    ", ifCommitReg(PopCount(RegNext(commitMoveVec)))                         ),
    ("rob_commitInstrFused   ", ifCommitReg(fuseCommitCnt)                                            ),
    ("rob_commitInstrLoad    ", ifCommitReg(PopCount(RegNext(commitLoadVec)))                         ),
    ("rob_commitInstrBranch  ", ifCommitReg(PopCount(RegNext(commitBranchVec)))                       ),
    ("rob_commitInstrLoadWait", ifCommitReg(PopCount(RegNext(commitLoadWaitVec)))                     ),
    ("rob_commitInstrStore   ", ifCommitReg(PopCount(RegNext(commitStoreVec)))                        ),
    ("rob_walkInstr          ", Mux(io.commits.isWalk, PopCount(io.commits.walkValid), 0.U)           ),
    ("rob_walkCycle          ", (state === s_walk || state === s_extrawalk)                           ),
    ("rob_1_4_valid          ", validEntries <= (RobSize / 4).U                                       ),
    ("rob_2_4_valid          ", validEntries >  (RobSize / 4).U && validEntries <= (RobSize / 2).U    ),
    ("rob_3_4_valid          ", validEntries >  (RobSize / 2).U && validEntries <= (RobSize * 3 / 4).U),
    ("rob_4_4_valid          ", validEntries >  (RobSize * 3 / 4).U                                   ),
  )
  generatePerfEvent()
}
