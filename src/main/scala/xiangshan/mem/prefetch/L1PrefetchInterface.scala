package xiangshan.mem.prefetch

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utility._
import xiangshan._
import xiangshan.cache._

class L1PrefetchReq (implicit p: Parameters) extends XSBundle with HasDCacheParameters{
  val paddr = UInt(PAddrBits.W)
  val alias = UInt(2.W)
  val confidence = UInt(1.W)
  val is_store = Bool()

  // only index bit is used, do not use tag
  def getVaddr(): UInt = {
    //Cat(alias, paddr(DCacheSameVPAddrLength-1, 0))//FIXME: seems not right
    Cat(alias,paddr)
  }

  // when l1 cache prefetch req arrives at load unit:
  // if (confidence == 1) 
  //   override load unit 2 load req
  // else if (load unit 1/2 is available)
  //   send prefetch req
  // else
  //   report prefetch !ready
}

class L1PrefetchHint (implicit p: Parameters) extends XSBundle with HasDCacheParameters{
  val loadbusy = Bool()
  val missqbusy = Bool()
}

class L1PrefetchFuzzer(implicit p: Parameters) extends DCacheModule{
  val io = IO(new Bundle() {
    // prefetch req interface
    val req = Decoupled(new L1PrefetchReq())
    // for fuzzer address gen
    val vaddr = Input(UInt(VAddrBits.W))
    val paddr = Input(UInt(PAddrBits.W))
  })

  // prefetch req queue is not provided, prefetcher must maintain its
  // own prefetch req queue.
  val rand_offset = LFSR64(seed=Some(123L))(5,0) << 6
  val rand_addr_select = LFSR64(seed=Some(567L))(3,0) === 0.U

  // use valid vaddr and paddr
  val rand_vaddr = DelayN(io.vaddr, 2)
  val rand_paddr = DelayN(io.paddr, 2)

  io.req.bits.paddr := 0x80000000L.U + rand_offset
  io.req.bits.alias := io.req.bits.paddr(13,12)
  io.req.bits.confidence := LFSR64(seed=Some(789L))(4,0) === 0.U
  io.req.bits.is_store := LFSR64(seed=Some(890L))(4,0) === 0.U
  io.req.valid := LFSR64(seed=Some(901L))(4,0) === 1.U
}