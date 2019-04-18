package aoplib.redundancy

import aoplib.AnnotationHelpers
import chisel3.Data
import chisel3.aop.{Aspect, Concern, ConcernTransform}
import chisel3.experimental.RawModule
import firrtl.{AnnotationSeq, CircuitForm, CircuitState, HighForm, LowFirrtlOptimization, LowForm, MALE, MidForm, Namespace, RenameMap, ResolveAndCheck, ResolvedAnnotationPaths, Transform, WRef}
import firrtl.annotations.{Annotation, ReferenceTarget}
import scala.collection.mutable
import scala.reflect.runtime.universe.TypeTag

case class RedundancyAspect[DUT <: RawModule, M <: RawModule](selectRoot: DUT => M,
                                                              selectSignals: M => Seq[Data]
                                                             )(implicit tag: TypeTag[DUT]) extends Aspect[DUT, M](selectRoot) {
  override def toAnnotation(dut: DUT): AnnotationSeq = {
    val m = selectRoot(dut)
    val signals = selectSignals(m)
    Seq(RedundancyRegisters(signals.map(_.toTarget)))
  }
}

case class RedundancyRegisters(regs: Seq[ReferenceTarget]) extends Annotation {
  override def update(renames: RenameMap): Seq[Annotation] = {
    Seq(RedundancyRegisters(AnnotationHelpers.renameMany(regs, renames)))
  }
}

abstract class RedundancyConcern[T <: RawModule, R <: RedundancyAspect[T, _]](implicit tag: TypeTag[T]) extends Concern[T, R] {
  def aspects: Seq[R]
  override def additionalTransformClasses: Seq[Class[_ <: Transform]] = Seq(classOf[RedundancyTransform])
}

class RedundancyTransform extends Transform with ResolvedAnnotationPaths {
  import firrtl.ir._
  import firrtl.Mappers._
  override def inputForm: CircuitForm = MidForm
  override def outputForm: CircuitForm = HighForm

  override val annotationClasses: Traversable[Class[_]] = Seq(classOf[RedundancyRegisters])

  case class RegInfo(red0: String, red1: String, output: String, tpe: Option[Type] = None)

  override def execute(state: CircuitState): CircuitState = {
    val redundantRegs = state.annotations.flatMap {
      case r: RedundancyRegisters => r.regs
      case other => Nil
    }

    val regModuleMap = mutable.HashMap[String, Set[String]]()
    redundantRegs.foreach { rt =>
      assert(rt.path == Nil && rt.component == Nil,
        s"Cannot have a register reference target with a component or a path: $rt")
      regModuleMap(rt.module) = regModuleMap.getOrElse(rt.module, Set.empty[String]) + rt.ref
    }

    val newModules = state.circuit.modules.map {
      case m: Module if regModuleMap.contains(m.name) =>
        val regMap = mutable.HashMap[String, RegInfo]()
        val ns = Namespace(m)
        regModuleMap(m.name).foreach { r =>
          val red0 = ns.newName(r)
          val red1 = ns.newName(r)
          regMap(r) = RegInfo(red0, red1, ns.newName(r))
        }
        val ret = m map tripleRegs(regMap)// map addMuxing(regMap)
        println(ret.serialize)
        ret
      case other => other
    }

    val newState = state.copy(
      circuit = state.circuit.copy(modules = newModules),
      annotations = state.annotations.filterNot(_.isInstanceOf[RedundancyRegisters])
    )

    new ResolveAndCheck().execute(newState)
  }

  private def tripleRegs(regMap: mutable.Map[String, RegInfo])(s: Statement): Statement = {
    s match {
      case d: DefRegister if regMap.contains(d.name) =>
        regMap(d.name) = regMap(d.name).copy(tpe = Some(d.tpe))
        val info = regMap(d.name)
        val wire = DefWire(NoInfo, info.output, info.tpe.get)

        // Hack to change reset value to name of this register
        regMap(d.name) = info.copy(output = info.red0)
        val reg0 = d.copy(name = info.red0) map changeOutput(regMap)
        regMap(d.name) = info.copy(output = info.red1)
        val reg1 = d.copy(name = info.red1) map changeOutput(regMap)
        //Change back
        regMap(d.name) = info.copy(output = info.output)

        val r0 = WRef(d)
        val r1 = WRef(info.red0)
        val r2 = WRef(info.red1)
        val o = WRef(info.output)
        val assignment = Seq(
          Conditionally(NoInfo, cond(r0, r1, r2), Connect(NoInfo, o, r0),
            Conditionally(NoInfo, cond(r1, r2, r0), Connect(NoInfo, o, r1),
              Conditionally(NoInfo, cond(r2, r0, r1), Connect(NoInfo, o, r2), Connect(NoInfo, o, r0))
            )
          )
        )
        Block(Seq(d, reg0, reg1, wire) ++ assignment)
      case con@Connect(_, w@WRef(reg, _, _, _), expr) if regMap.contains(reg) =>
        val c = con.copy(expr = changeOutput(regMap)(expr))
        Block(Seq(
          c,
          c.copy(loc = w.copy(name = regMap(reg).red0)),
          c.copy(loc = w.copy(name = regMap(reg).red1))
        ))
      case other => other map changeOutput(regMap) map tripleRegs(regMap)
    }
  }

  private def changeOutput(regMap: collection.Map[String, RegInfo])(expr: Expression): Expression = expr match {
    case w@WRef(reg, _, _, MALE) if regMap.contains(reg) => w.copy(name = regMap(reg).output)
    case e => e map changeOutput(regMap)
  }

  def cond(agree1: WRef, agree2: WRef, disagree: WRef): Expression = {
    import firrtl.PrimOps._
    val u1 = UIntType(IntWidth(1))
    val a = DoPrim(Eq, Seq(agree1, agree2), Nil, u1)
    val d = DoPrim(Neq, Seq(agree1, disagree), Nil, u1)
    DoPrim(And, Seq(a, d), Nil, u1)
  }

  private def addMuxing(regMap: collection.Map[String, RegInfo])(s: Statement): Statement = {
    val wireDefs = regMap.map { case (reg, info) =>
      DefWire(NoInfo, info.output, info.tpe.get)
    }
    val assignments = regMap.map { case (reg, info) =>
      val r0 = WRef(reg)
      val r1 = WRef(info.red0)
      val r2 = WRef(info.red1)
      val o = WRef(info.output)
      val assignment = Block(Seq(
        Conditionally(NoInfo, cond(r0, r1, r2), Connect(NoInfo, o, r0),
          Conditionally(NoInfo, cond(r1, r2, r0), Connect(NoInfo, o, r1),
            Conditionally(NoInfo, cond(r2, r0, r1), Connect(NoInfo, o, r2), Connect(NoInfo, o, r0))
          )
        )
      ))
      val mod = Module(
        NoInfo,
        "Blah",
        Seq(reg, info.red0, info.red1).map(n => Port(NoInfo, n, Input, info.tpe.get)) :+ Port(NoInfo, info.output, Output, info.tpe.get),
        assignment
      )
      //compile(mod, HighForm, MidForm).body
      mod.body
    }

    Block(Seq(Block(wireDefs.toList), s, Block(assignments.toList)))
  }

  //def compile(m: Module, inputForm: CircuitForm, outputForm: CircuitForm): Module = {
  //  CompilerFirrtl.lower(Circuit(NoInfo, Seq(m), m.name), inputForm, outputForm, Nil, Nil).modules.head.asInstanceOf[Module]
  //}
}


/*
object CompilerFirrtl {
  import firrtl.ir.Circuit

  def lower(c: Circuit, inForm: CircuitForm, outForm: CircuitForm, annotations: AnnotationSeq, additionalXForms: Seq[Transform]): Circuit = {
    object MyCompiler extends firrtl.Compiler {
      import firrtl._
      override def inputForm = inForm
      override def outputForm = outForm
      override def emitter: Emitter = new LowFirrtlEmitter
      override def transforms: Seq[Transform] = {
        CompilerUtils.getLoweringTransforms(inputForm, outputForm) ++ additionalXForms
      }
      def lower(c: ir.Circuit, annotations: AnnotationSeq): ir.Circuit = {
        val compileResult = compileAndEmit(firrtl.CircuitState(c, inForm, annotations))
        compileResult.circuit
      }
    }
    MyCompiler.lower(c, annotations)
  }

}
*/
