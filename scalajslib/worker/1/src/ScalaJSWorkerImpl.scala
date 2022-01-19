package mill
package scalajslib
package worker

import mill.api.{Result, internal}
import mill.scalajslib.api.{ESFeatures, ESVersion, ModuleKind, _}
import org.scalajs.ir.ScalaJSVersions
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap
import org.scalajs.jsenv.{Input, JSEnv, RunConfig}
import org.scalajs.linker.interface.{ESFeatures => ScalaJSESFeatures, ESVersion => ScalaJSESVersion, ModuleKind => ScalaJSModuleKind, _}
import org.scalajs.linker.{PathIRContainer, PathIRFile, PathOutputDirectory, StandardImpl}
import org.scalajs.logging.ScalaConsoleLogger
import org.scalajs.testing.adapter.{TestAdapter, TestAdapterInitializer => TAI}

import java.io.File
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.ref.WeakReference

@internal
class ScalaJSWorkerImpl extends mill.scalajslib.api.ScalaJSWorkerApi {
  private case class LinkerInput(
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      dest: File
  )

  private object ScalaJSLinker {
    private val cache = mutable.Map.empty[LinkerInput, WeakReference[Linker]]
    def reuseOrCreate(input: LinkerInput): Linker = cache.get(input) match {
      case Some(WeakReference(linker)) => linker
      case _ =>
        val newLinker = createLinker(input)
        cache.update(input, WeakReference(newLinker))
        newLinker
    }
    private def minorIsGreaterThan(number: Int) = ScalaJSVersions.binaryEmitted match {
      case s"1.$n" if n.toIntOption.exists(_ < number) => false
      case _ => true
    }
    private def createLinker(input: LinkerInput): Linker = {
      val semantics = input.fullOpt match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      def withESVersion_1_5_minus(esFeatures: ScalaJSESFeatures): ScalaJSESFeatures = {
        val useECMAScript2015: Boolean = input.esFeatures.esVersion match {
          case ESVersion.ES5_1 => false
          case ESVersion.ES2015 => true
          case v => throw new Exception(
              s"ESVersion $v is not supported with Scala.js < 1.6. Either update Scala.js or use one of ESVersion.ES5_1 or ESVersion.ES2015"
            )
        }
        esFeatures.withUseECMAScript2015(useECMAScript2015)
      }
      def withESVersion_1_6_plus(esFeatures: ScalaJSESFeatures): ScalaJSESFeatures = {
        val scalaJSESVersion: ScalaJSESVersion = input.esFeatures.esVersion match {
          case ESVersion.ES5_1 => ScalaJSESVersion.ES5_1
          case ESVersion.ES2015 => ScalaJSESVersion.ES2015
          case ESVersion.ES2016 => ScalaJSESVersion.ES2016
          case ESVersion.ES2017 => ScalaJSESVersion.ES2017
          case ESVersion.ES2018 => ScalaJSESVersion.ES2018
          case ESVersion.ES2019 => ScalaJSESVersion.ES2019
          case ESVersion.ES2020 => ScalaJSESVersion.ES2020
          case ESVersion.ES2021 => ScalaJSESVersion.ES2021
        }
        esFeatures.withESVersion(scalaJSESVersion)
      }
      var scalaJSESFeatures: ScalaJSESFeatures = ScalaJSESFeatures.Defaults
        .withAllowBigIntsForLongs(input.esFeatures.allowBigIntsForLongs)

      if (minorIsGreaterThan(3)) {
        scalaJSESFeatures = scalaJSESFeatures
          .withAvoidClasses(input.esFeatures.avoidClasses)
          .withAvoidLetsAndConsts(input.esFeatures.avoidLetsAndConsts)
      }
      scalaJSESFeatures =
        if (minorIsGreaterThan(6)) withESVersion_1_6_plus(scalaJSESFeatures)
        else withESVersion_1_5_minus(scalaJSESFeatures)

      val useClosure = input.fullOpt && input.moduleKind != ModuleKind.ESModule
      val config = StandardConfig()
        .withOptimizer(input.fullOpt)
        .withClosureCompilerIfAvailable(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withESFeatures(scalaJSESFeatures)
      StandardImpl.linker(config)
    }
  }

  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  ): Result[LinkedModules] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val linker =
      ScalaJSLinker.reuseOrCreate(LinkerInput(fullOpt, moduleKind, esFeatures, dest))
    val cache = StandardImpl.irFileCache().newCache
    val sourceIRsFuture = Future.sequence(sources.toSeq.map(f => PathIRFile(f.toPath)))
    val irContainersPairs = PathIRContainer.fromClasspath(libraries.map(_.toPath()))
    val libraryIRsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))
    val outDir = PathOutputDirectory(dest.toPath)
    val logger = new ScalaConsoleLogger
    val mainInitializer = Option(main).map { cls =>
      ModuleInitializer.mainMethodWithArgs(cls, "main")
    }
    val testInitializer =
      if (testBridgeInit)
        Some(ModuleInitializer.mainMethod(TAI.ModuleClassName, TAI.MainMethodName))
      else None
    val moduleInitializers = mainInitializer.toList ::: testInitializer.toList

    val resultFuture = (for {
      sourceIRs <- sourceIRsFuture
      libraryIRs <- libraryIRsFuture
      report <- linker.link(sourceIRs ++ libraryIRs, moduleInitializers, outDir, logger)
    } yield {
      Result.Success(
        LinkedModules(
          report
            .publicModules
            .map(m => m.moduleID -> dest.toPath.resolve(m.jsFileName).toFile)
            .toMap,
          moduleKind))
    }).recover {
      case e: org.scalajs.linker.interface.LinkingException =>
        Result.Failure(e.getMessage)
    }

    Await.result(resultFuture, Duration.Inf)
  }

  def run(config: JsEnvConfig, linkedModules: LinkedModules): Unit = {
    val env = jsEnv(config)
    val input = jsEnvInput(linkedModules)
    val runConfig = RunConfig().withLogger(new ScalaConsoleLogger)
    Run.runInterruptible(env, input, runConfig)
  }

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      linkedModules: LinkedModules,
      moduleKind: ModuleKind
  ): (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val input = jsEnvInput(linkedModules)
    val tconfig = TestAdapter.Config().withLogger(new ScalaConsoleLogger)

    val adapter = new TestAdapter(env, input, tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }

  def jsEnv(config: JsEnvConfig): JSEnv = config match {
    case config: JsEnvConfig.NodeJs =>
      /* In Mill, `config.sourceMap = true` means that `source-map-support`
       * should be used *if available*, as it is what was used to mean in
       * Scala.js 0.6.x. Scala.js 1.x has 3 states: enable, enable-if-available
       * and disable. The former (enable) *fails* if it cannot load the
       * `source-map-support` module. We must therefore adapt the boolean to
       * one of the two last states.
       */
      new org.scalajs.jsenv.nodejs.NodeJSEnv(
        org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
          .withSourceMap(if (config.sourceMap) SourceMap.EnableIfAvailable else SourceMap.Disable)
      )

    case config: JsEnvConfig.JsDom =>
      new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
        org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
      )
    case config: JsEnvConfig.Phantom =>
      new org.scalajs.jsenv.phantomjs.PhantomJSEnv(
        org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
      )
  }

  def jsEnvInput(linkedModules: LinkedModules): Seq[Input] = {
    val input = linkedModules.moduleKind match {
      case ModuleKind.NoModule => Input.Script.apply _
      case ModuleKind.CommonJSModule => Input.CommonJSModule.apply _
      case ModuleKind.ESModule => Input.ESModule.apply _
    }
    linkedModules.modules.values.toSeq.map(file => input(file.toPath))
  }
}
