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
/***************************************************************************************
 * Author: Liang Sen
 * E-mail: liangsen20z@ict.ac.cn
 * Date: 2023-06-19
 ****************************************************************************************/
package xiangshan.backend.regfile

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.experimental.prefix
import chisel3.util._
import difftest._
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import xiangshan.backend.issue.RsIdx
import xiangshan.backend.writeback.{WriteBackSinkNode, WriteBackSinkParam, WriteBackSinkType}
import xiangshan.frontend.Ftq_RF_Components
import xiangshan.vector.HasVectorParameters
import xiangshan._
import xiangshan.backend.execute.exu.ExuType
import xiangshan.vector.vbackend.vregfile.{MoveReq, VectorRfReadPort}
import xs.utils.{DelayN, SignExt, ZeroExt}

class ScalarRfReadPort(implicit p:Parameters) extends XSBundle{
  val addr = Input(UInt(PhyRegIdxWidth.W))
  val isFp = Input(Bool())
  val data = Output(UInt(XLEN.W))
}

object RegFileTop{
  def extractElement(vsrc:UInt, sew:UInt, uopIdx:UInt, VLEN:Int, XLEN:Int): UInt = {
    require(vsrc.getWidth == VLEN)
    val res = WireInit(0.U(XLEN.W))
    val vsrcSplit8  = VecInit(Seq.tabulate(VLEN / 8)(idx => vsrc(idx * 8 + 7,  idx * 8)))
    val vsrcSplit16 = VecInit(Seq.tabulate(VLEN / 16)(idx => vsrc(idx * 16 + 15,  idx * 16)))
    val vsrcSplit32 = VecInit(Seq.tabulate(VLEN / 32)(idx => vsrc(idx * 32 + 31,  idx * 32)))
    val vsrcSplit64 = VecInit(Seq.tabulate(VLEN / 64)(idx => vsrc(idx * 64 + 63,  idx * 64)))
    res := MuxCase(0.U, Seq(
      (sew === 0.U) -> ZeroExt(vsrcSplit8(uopIdx(log2Ceil(VLEN / 8) - 1, 0)), XLEN),
      (sew === 1.U) -> ZeroExt(vsrcSplit16(uopIdx(log2Ceil(VLEN / 16) - 1, 0)), XLEN),
      (sew === 2.U) -> ZeroExt(vsrcSplit32(uopIdx(log2Ceil(VLEN / 32) - 1, 0)), XLEN),
      (sew === 3.U) -> ZeroExt(vsrcSplit64(uopIdx(log2Ceil(VLEN / 64) - 1, 0)), XLEN),
    ))
    res
  }
}

class AddrGen(implicit p:Parameters) extends XSModule{
  val io = IO(new Bundle{
    val base = Input(UInt(XLEN.W))
    val stride = Input(UInt(XLEN.W))
    val offset = Input(UInt(VLEN.W))
    val sew = Input(UInt(2.W))
    val isStride = Input(Bool())
    val uopIdx = Input(UInt(7.W))
    val target = Output(UInt(XLEN.W))
    val imm = Output(UInt(12.W))
  })
  private val rawOffset = RegFileTop.extractElement(io.offset, io.sew, io.uopIdx, VLEN, XLEN)
  private val offset = MuxCase(0.U, Seq(
    (io.sew === 0.U) -> SignExt(rawOffset(7, 0), XLEN),
    (io.sew === 1.U) -> SignExt(rawOffset(15, 0), XLEN),
    (io.sew === 2.U) -> SignExt(rawOffset(31, 0), XLEN),
    (io.sew === 3.U) -> rawOffset(63, 0),
  ))
  private val offsetTarget = io.base + offset

  private val strideOffset = (io.stride * io.uopIdx)(63, 0)
  private val strideTarget = Cat(strideOffset(63,12), 0.U(12.W)) + io.base

  io.target := Mux(io.isStride, strideTarget, offsetTarget)
  io.imm := Mux(io.isStride, 0.U, strideOffset(11, 0))
}

class RegFileTop(extraScalarRfReadPort: Int)(implicit p:Parameters) extends LazyModule with HasXSParameter with HasVectorParameters{
  val issueNode = new RegFileNode
  val writebackNode = new WriteBackSinkNode(WriteBackSinkParam("RegFile Top", WriteBackSinkType.regFile))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val pcReadNum:Int = issueNode.out.count(_._2._2.hasJmp) * 2 + issueNode.out.count(_._2._2.hasLoad) + issueNode.out.count(_._2._2.hasSpecialLoad)
    println("\nRegfile Configuration:")
    println(s"PC read num: $pcReadNum \n")
    println("Regfile Writeback Info:")

    val io = IO(new Bundle{
      val hartId = Input(UInt(64.W))
      val mmuEnable = Input(Bool())
      val pcReadAddr = Output(Vec(pcReadNum, UInt(log2Ceil(FtqSize).W)))
      val pcReadData = Input(Vec(pcReadNum, new Ftq_RF_Components))
      val vectorReads = Vec(loadUnitNum * 2, Flipped(new VectorRfReadPort))
      val extraReads = Vec(extraScalarRfReadPort, new ScalarRfReadPort)
      val vectorRfMoveReq = Output(Vec(loadUnitNum, Valid(new MoveReq)))
      val debug_int_rat = Input(Vec(32, UInt(PhyRegIdxWidth.W)))
      val debug_fp_rat = Input(Vec(32, UInt(PhyRegIdxWidth.W)))
      val redirect = Input(Valid(new Redirect))
    })
    require(issueNode.in.count(_._2._1.isIntRs) <= 1)
    require(issueNode.in.count(_._2._1.isMemRs) <= 1)
    require(issueNode.in.count(_._2._1.isFpRs) <= 1)
    require(writebackNode.in.length == 1)
    require(issueNode.out.count(_._2._2.hasJmp) == 1)

    private val wb = writebackNode.in.flatMap(i => i._1.zip(i._2._1))
    wb.zipWithIndex.foreach({ case ((_, cfg), idx) =>
      println(s"port $idx ${cfg.name} #${cfg.id} write Int: ${cfg.writeIntRf} write Fp: ${cfg.writeFpRf} bypass Int: ${cfg.bypassIntRegfile} bypass Fp: ${cfg.bypassFpRegfile}")
    })
    println("")

    private val fromRs = issueNode.in.flatMap(i => i._1.zip(i._2._2).map(e => (e._1, e._2, i._2._1)))
    private val toExuMap = issueNode.out.map(i => i._2._2 -> (i._1, i._2._2, i._2._1)).toMap

    private val needIntSrc = issueNode.out.filter(i => i._2._2.readIntegerRegfile).map(i => (i._1, i._2._2, i._2._1))
    private val needFpSrc = issueNode.out.filter(i => i._2._2.readFloatingRegfile).map(i => (i._1, i._2._2, i._2._1))

    private val writeIntRfBypass = wb.filter(i => i._2.bypassIntRegfile)
    private val writeIntRf = wb.filter(i => !i._2.bypassIntRegfile && i._2.writeIntRf)
    private val writeFpRfBypass = wb.filter(i => i._2.bypassFpRegfile)
    private val writeFpRf = wb.filter(i => !i._2.bypassFpRegfile && i._2.writeFpRf).map({case(wb, cfg) =>
      if(cfg.exuType == ExuType.mul){
        (Pipe(wb), cfg)
      } else {
        (wb, cfg)
      }
    })

    private val intReadNum = needIntSrc.map(_._2.intSrcNum).sum
    private val fpReadNum = needFpSrc.filterNot(_._2.hasStd).map(_._2.fpSrcNum).sum
    private val stdFpReadNum = needFpSrc.filter(_._2.hasStd).map(_._2.fpSrcNum).sum

    println(s"intReadNum: $intReadNum, fpReadNum: $fpReadNum")

    private val intRf = Module(new GenericRegFile(NRPhyRegs, writeIntRf.length, writeIntRfBypass.length, intReadNum, extraScalarRfReadPort, XLEN, "IntegerRegFile", true))
    private val fpRf = Module(new GenericRegFile(NRPhyRegs, writeFpRf.length, writeFpRfBypass.length, fpReadNum, extraScalarRfReadPort + stdFpReadNum, XLEN, "FloatingRegFile", false))

    private val intWriteBackSinks = intRf.io.write ++ intRf.io.bypassWrite
    private val intWriteBackSources = writeIntRf ++ writeIntRfBypass
    intWriteBackSinks.zip(intWriteBackSources.map(_._1)).foreach({case(sink, source) =>
      sink.en := source.valid && source.bits.uop.ctrl.rfWen && source.bits.uop.pdest =/= 0.U
      sink.addr := source.bits.uop.pdest
      sink.data := source.bits.data
    })

    private val fpWriteBackSinks = fpRf.io.write ++ fpRf.io.bypassWrite
    private val fpWriteBackSources = writeFpRf ++ writeFpRfBypass
    fpWriteBackSinks.zip(fpWriteBackSources.map(_._1)).foreach({ case (sink, source) =>
      sink.en := source.valid && source.bits.uop.ctrl.fpWen
      sink.addr := source.bits.uop.pdest
      sink.data := source.bits.data
    })

    private var intRfReadIdx = 0
    private var fpRfReadIdx = 0
    private var pcReadPortIdx = 0
    private var vecReadPortIdx = 0
    private var vecMoveReqPortIdx = 0
    private var noBypassFpReadIdx = extraScalarRfReadPort
    for(in <- fromRs){
      val out = toExuMap(in._2)
      val rsParam = in._3
      val exuComplexParam = in._2
      val bi = in._1
      val bo = out._1

      prefix(s"${exuComplexParam.name}_${exuComplexParam.id}") {
        val exuInBundle = WireInit(bi.issue.bits)
        exuInBundle.src := DontCare

        if (exuComplexParam.isIntType) {
          val issueBundle = WireInit(bi.issue.bits)
          val srcNum = exuComplexParam.intSrcNum
          for((d, addr) <- issueBundle.src.zip(bi.issue.bits.uop.psrc).take(srcNum)){
            intRf.io.read(intRfReadIdx).addr := addr
            d := intRf.io.read(intRfReadIdx).data
            intRfReadIdx = intRfReadIdx + 1
          }
          if(exuComplexParam.hasJmp){
            io.pcReadAddr(pcReadPortIdx) := bi.issue.bits.uop.cf.ftqPtr.value
            io.pcReadAddr(pcReadPortIdx + 1) := (bi.issue.bits.uop.cf.ftqPtr + 1.U).value
            val instrPc = io.pcReadData(pcReadPortIdx).getPc(bi.issue.bits.uop.cf.ftqOffset)
            val jalrTarget = io.pcReadData(pcReadPortIdx + 1).startAddr
            pcReadPortIdx = pcReadPortIdx + 2
            exuInBundle := ImmExtractor(exuComplexParam, issueBundle, Some(instrPc), Some(jalrTarget), Some(io.mmuEnable))
          } else {
            exuInBundle := ImmExtractor(exuComplexParam, issueBundle)
          }
        } else if(exuComplexParam.isFpType){
          val srcNum = exuComplexParam.fpSrcNum
          for ((d, addr) <- exuInBundle.src.zip(bi.issue.bits.uop.psrc).take(srcNum)) {
            fpRf.io.read(fpRfReadIdx).addr := addr
            d := fpRf.io.read(fpRfReadIdx).data
            fpRfReadIdx = fpRfReadIdx + 1
          }
        } else if (exuComplexParam.isMemType && !exuComplexParam.isSpecialLoad) {
          val issueBundle = WireInit(bi.issue.bits)

          val is2Stage = SrcType.isVec(bi.issue.bits.uop.ctrl.srcType(1)) || SrcType.isReg(bi.issue.bits.uop.ctrl.srcType(1))
          val isUnitStride = (bi.issue.bits.uop.ctrl.fuType === FuType.ldu || bi.issue.bits.uop.ctrl.fuType === FuType.stu) && !is2Stage
          val isStd = bi.issue.bits.uop.ctrl.fuType === FuType.std
          val uopIdx = bi.issue.bits.uop.uopIdx
          val sew = bi.issue.bits.uop.vctrl.eew(0)

          io.vectorReads(vecReadPortIdx).addr := DontCare
          //Mask read
          io.vectorReads(vecReadPortIdx + 1).addr := bi.issue.bits.uop.vm
          val vmVal = io.vectorReads(vecReadPortIdx + 1).data
          val isMaskDisabled = bi.issue.bits.uop.ctrl.vm && !(vmVal(uopIdx).asBool)
          val isTailDisabled = bi.issue.bits.uop.isTail
          val isPrestartDisabled = bi.issue.bits.uop.isPrestart
          //Base address read
          intRf.io.read(intRfReadIdx).addr := bi.issue.bits.uop.psrc(0)
          //Stride read
          intRf.io.read(intRfReadIdx + 1).addr := bi.issue.bits.uop.psrc(1)
          //Scalar STD data read
          fpRf.io.readNoBypass(noBypassFpReadIdx).addr := bi.issue.bits.uop.psrc(0)
          //Move req
          io.vectorRfMoveReq(vecMoveReqPortIdx).valid := bi.issue.bits.uop.ctrl.fuType === FuType.ldu &&
            !bi.hold && bi.issue.valid && bi.issue.bits.uop.ctrl.isVector
          when(isPrestartDisabled){
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.agnostic := false.B
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.enable := false.B
          }.elsewhen(isTailDisabled){
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.agnostic := bi.issue.bits.uop.vCsrInfo.vta(0)
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.enable := false.B
          }.elsewhen(isMaskDisabled){
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.agnostic := bi.issue.bits.uop.vCsrInfo.vma(0)
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.enable := false.B
          }.otherwise{
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.agnostic := false.B
            io.vectorRfMoveReq(vecMoveReqPortIdx).bits.enable := true.B
          }
          io.vectorRfMoveReq(vecMoveReqPortIdx).bits.srcAddr := bi.issue.bits.uop.psrc(2)
          io.vectorRfMoveReq(vecMoveReqPortIdx).bits.dstAddr := bi.issue.bits.uop.pdest
          io.vectorRfMoveReq(vecMoveReqPortIdx).bits.sew := bi.issue.bits.uop.vCsrInfo.vsew
          io.vectorRfMoveReq(vecMoveReqPortIdx).bits.uopIdx := bi.issue.bits.uop.uopIdx

          when(bi.issue.bits.uop.ctrl.isVector){
            when(isStd){
              io.vectorReads(vecReadPortIdx).addr := bi.issue.bits.uop.psrc(2)
              exuInBundle.src(0) := RegFileTop.extractElement(io.vectorReads(vecReadPortIdx).data, sew, uopIdx, VLEN, XLEN)
            }.elsewhen(isUnitStride){
              exuInBundle.src(0) := intRf.io.read(intRfReadIdx).data
              exuInBundle.uop.ctrl.imm := (ZeroExt(uopIdx,12) << sew)(11, 0)
            }.otherwise{
              io.vectorReads(vecReadPortIdx).addr := bi.issue.bits.uop.psrc(1)
              val baseAddrReg = RegEnable(intRf.io.read(intRfReadIdx).data, bi.issue.valid && bi.hold)
              val strideReg = RegEnable(intRf.io.read(intRfReadIdx + 1).data, bi.issue.valid && bi.hold)
              val offsetReg = RegEnable(io.vectorReads(vecReadPortIdx).data, bi.issue.valid && bi.hold)
              val uopReg = RegEnable(bi.issue.bits.uop, bi.issue.valid && bi.hold)
              val addrGen = Module(new AddrGen)
              addrGen.io.base := baseAddrReg
              addrGen.io.stride := strideReg
              addrGen.io.offset := offsetReg
              addrGen.io.sew := uopReg.vCsrInfo.vsew
              addrGen.io.isStride := uopReg.ctrl.srcType(1) === SrcType.reg
              addrGen.io.uopIdx := uopReg.uopIdx
              exuInBundle.src(0) := addrGen.io.target
              exuInBundle.uop.ctrl.imm := addrGen.io.imm
            }
          }.otherwise {
            val intSrcData = intRf.io.read(intRfReadIdx).data
            val fpSrcData = fpRf.io.readNoBypass(noBypassFpReadIdx).data
            issueBundle.src(0) := MuxCase(intSrcData,
              Seq(
                (bi.issue.bits.uop.ctrl.srcType(0) === SrcType.reg, intSrcData),
                (bi.issue.bits.uop.ctrl.srcType(0) === SrcType.fp, fpSrcData)
              )
            )
            exuInBundle := ImmExtractor(exuComplexParam, issueBundle)
          }
          io.pcReadAddr(pcReadPortIdx) := bi.issue.bits.uop.cf.ftqPtr.value
          exuInBundle.uop.cf.pc := io.pcReadData(pcReadPortIdx).getPc(bi.issue.bits.uop.cf.ftqOffset)
          exuInBundle.uop.loadStoreEnable := !(bi.issue.bits.uop.ctrl.isVector && (isMaskDisabled || isTailDisabled || isPrestartDisabled))

          intRfReadIdx = intRfReadIdx + 2
          noBypassFpReadIdx = noBypassFpReadIdx + 1
          vecMoveReqPortIdx = vecMoveReqPortIdx + 1
          vecReadPortIdx = vecReadPortIdx + 2
          pcReadPortIdx = pcReadPortIdx + 1
        } else if (exuComplexParam.isMemType && exuComplexParam.isSpecialLoad) {
          val issueBundle = WireInit(bi.issue.bits)
          io.pcReadAddr(pcReadPortIdx) := bi.issue.bits.uop.cf.ftqPtr.value
          intRf.io.read(intRfReadIdx).addr := bi.issue.bits.uop.psrc(0)
          issueBundle.uop.cf.pc := io.pcReadData(pcReadPortIdx).getPc(bi.issue.bits.uop.cf.ftqOffset)
          issueBundle.src(0) := intRf.io.read(intRfReadIdx).data
          exuInBundle := ImmExtractor(exuComplexParam, issueBundle)
          exuInBundle.uop.loadStoreEnable := true.B
          intRfReadIdx = intRfReadIdx + 1
          pcReadPortIdx = pcReadPortIdx + 1
        } else {
          exuInBundle := DontCare
          require(false, "Unknown Exu Complex Type")
        }

        val issueValidReg = RegInit(false.B)
        val auxValidReg = RegInit(false.B)
        val issueExuInReg = Reg(new ExuInput)
        val rsIdxReg = Reg(new RsIdx)

        val allowPipe = !issueValidReg || bo.issue.ready || (issueValidReg && issueExuInReg.uop.robIdx.needFlush(io.redirect))
        bo.issue.valid := issueValidReg
        bo.issue.bits := issueExuInReg
        bo.issue.bits.uop.loadStoreEnable := issueExuInReg.uop.loadStoreEnable && issueValidReg
        bo.rsIdx := rsIdxReg
        bo.auxValid := auxValidReg
        when(allowPipe) {
          issueValidReg := bi.issue.valid && !bi.hold && !bi.issue.bits.uop.robIdx.needFlush(io.redirect)
          auxValidReg := bi.auxValid && !bi.hold && !bi.issue.bits.uop.robIdx.needFlush(io.redirect)
        }
        when(bi.issue.fire && !bi.hold) {
          issueExuInReg := exuInBundle
          rsIdxReg := bi.rsIdx
        }

        bi.issue.ready := allowPipe
        bi.rsFeedback.feedbackFastLoad := bo.rsFeedback.feedbackFastLoad
        bi.rsFeedback.feedbackSlowLoad := bo.rsFeedback.feedbackSlowLoad
        bi.rsFeedback.feedbackSlowStore := bo.rsFeedback.feedbackSlowStore
        bo.rsFeedback.isFirstIssue := RegNext(bi.rsFeedback.isFirstIssue)
        bo.hold := false.B
      }
    }

    var intRfReadExtraIdx = 0
    var fpRfReadExtraIdx = 0
    for(r <- io.extraReads){
      intRf.io.readNoBypass(intRfReadExtraIdx).addr := r.addr
      fpRf.io.readNoBypass(fpRfReadExtraIdx).addr := r.addr
      r.data := Mux(r.isFp, fpRf.io.readNoBypass(fpRfReadExtraIdx).data, intRf.io.readNoBypass(intRfReadExtraIdx).data)
      intRfReadExtraIdx = intRfReadExtraIdx + 1
      fpRfReadExtraIdx = fpRfReadExtraIdx + 1
    }

    if (env.EnableDifftest || env.AlwaysBasicDiff) {
      val intWriteNum = (intRf.io.write ++ intRf.io.bypassWrite).length
      val debugIntRegfile = Module(new GenericRegFile(NRPhyRegs, intWriteNum, 0, 32, 0, XLEN, "DebugIntegerRegFile", true))
      debugIntRegfile.io.write.zip(intRf.io.write ++ intRf.io.bypassWrite).foreach({ case (a, b) => a := b })
      debugIntRegfile.io.read.zip(io.debug_int_rat).foreach(e => e._1.addr := e._2)

      val fpWriteNum = (fpRf.io.write ++ fpRf.io.bypassWrite).length
      val debugFpRegfile = Module(new GenericRegFile(NRPhyRegs, fpWriteNum, 0, 32, 0, XLEN, "DebugFloatingRegFile", false))
      debugFpRegfile.io.write.zip(fpRf.io.write ++ fpRf.io.bypassWrite).foreach({ case (a, b) => a := b })
      debugFpRegfile.io.read.zip(io.debug_fp_rat).foreach(e => e._1.addr := e._2)

      val difftestArchInt = DifftestModule(new DiffArchIntRegState, delay = 2)
      difftestArchInt.clock := clock
      difftestArchInt.coreid := io.hartId
      difftestArchInt.value := VecInit(debugIntRegfile.io.read.map(_.data))

      val difftestArchFp = DifftestModule(new DiffArchFpRegState, delay = 2)
      difftestArchFp.clock := clock
      difftestArchFp.coreid := io.hartId
      difftestArchFp.value := VecInit(debugFpRegfile.io.read.map(_.data))
    }
  }
}
