package net.cardnell.mkver

import cats.implicits._
import com.typesafe.config.ConfigFactory
import net.cardnell.mkver.IncrementAction.IncrementMinor
import zio.Task
import zio.config.ConfigDescriptor.{string, _}
import zio.config._
import zio.config.typesafe.TypesafeConfigSource


case class Format(name: String, format: String)

object Format {
  val formatDesc = (
    string("name").describe("Name of format. e.g. 'MajorMinor'") |@|
      string("format").describe("Format string for this format. Can include other formats. e.g. '{x}.{y}'")
    )(Format.apply, Format.unapply)
}

case class RunConfig(tag: Boolean,
                     tagPrefix: String,
                     tagMessageFormat: String,
                     preReleaseFormat: String,
                     buildMetaDataFormat: String,
                     includeBuildMetaData: Boolean,
                     commitMessageActions: List[CommitMessageAction],
                     whenNoValidCommitMessages: IncrementAction,
                     formats: List[Format],
                     patches: List[PatchConfig])

case class BranchConfigDefaults(tag: Boolean,
                                tagMessageFormat: String,
                                preReleaseFormat: String,
                                buildMetaDataFormat: String,
                                includeBuildMetaData: Boolean,
                                whenNoValidCommitMessages: IncrementAction,
                                formats: List[Format],
                                patches: List[String])

case class BranchConfig(pattern: String,
                        tag: Option[Boolean],
                        tagMessageFormat: Option[String],
                        preReleaseFormat: Option[String],
                        buildMetaDataFormat: Option[String],
                        includeBuildMetaData: Option[Boolean],
                        whenNoValidCommitMessages: Option[IncrementAction],
                        formats: Option[List[Format]],
                        patches: Option[List[String]])

object BranchConfig {
  object Defaults {
    val name = ".*"
    val tag = false
    val tagMessageFormat = "release {Tag}"
    val preReleaseFormat = "RC{PreReleaseNumber}"
    val buildMetaDataFormat = "{Branch}.{ShortHash}"
    val includeBuildMetaData = false
    val whenNoValidCommitMessages = IncrementMinor
    val patches = Nil
    val formats = Nil
  }

  def readPreReleaseFormat(value: String): Either[String, String] =
    if (value.contains("{PreReleaseNumber}") && !value.endsWith("{PreReleaseNumber}")) {
      Left("preReleaseFormat must end with {PreReleaseNumber}")
    } else {
      Right(value)
    }

  val patternDesc = string("pattern").describe("regex to match branch name on")
  val tagDesc = boolean("tag").describe("whether to actually tag this branch when `mkver tag` is called")
  val tagMessageFormatDesc = string("tagMessageFormat").describe("format to be used in the annotated git tag message")
  val preReleaseFormatDesc = string("preReleaseFormat")
    .xmapEither(readPreReleaseFormat, (v: String) => Right(v))
    .describe("format to be used for the pre-release. e.g. alpha, RC-{PreReleaseNumber}, SNAPSHOT")
  val buildMetaDataFormatDesc = string("buildMetaDataFormat").describe("format to be used for the build metadata. e.g. {BranchName}")
  val includeBuildMetaDataDesc = boolean("includeBuildMetaData").describe("whether the tag version includes the build metadata component")
  val whenNoValidCommitMessages = string("whenNoValidCommitMessages")
    .xmapEither(IncrementAction.read, (output: IncrementAction) => Right(output.toString))
    .describe("behaviour if no valid commit messages are found Fail|IncrementMajor|IncrementMinor|IncrementPatch|NoIncrement")
  val formatsDesc = nested("formats")(list(Format.formatDesc)).describe("custom format strings")
  val patchesDesc = list("patches")(string).describe("Patch configs to be applied")

  val branchConfigDefaultsDesc = (
    tagDesc.default(Defaults.tag) |@|
      tagMessageFormatDesc.default(Defaults.tagMessageFormat) |@|
      preReleaseFormatDesc.default(Defaults.preReleaseFormat) |@|
      buildMetaDataFormatDesc.default(Defaults.buildMetaDataFormat) |@|
      includeBuildMetaDataDesc.default(Defaults.includeBuildMetaData) |@|
      whenNoValidCommitMessages.default(Defaults.whenNoValidCommitMessages) |@|
      formatsDesc.default(Defaults.formats) |@|
      patchesDesc.default(Defaults.patches)
    )(BranchConfigDefaults.apply, BranchConfigDefaults.unapply)

  val branchConfigDesc = (
    patternDesc |@|
      tagDesc.optional |@|
      tagMessageFormatDesc.optional |@|
      preReleaseFormatDesc.optional |@|
      buildMetaDataFormatDesc.optional |@|
      includeBuildMetaDataDesc.optional |@|
      whenNoValidCommitMessages.optional |@|
      formatsDesc.optional |@|
      patchesDesc.optional
    )(BranchConfig.apply, BranchConfig.unapply)
}

case class PatchConfig(name: String, filePatterns: List[String], find: String, replace: String)

object PatchConfig {
  val patchConfigDesc = (
    string("name").describe("Name of patch, referenced from branch configs") |@|
      list("filePatterns")(string).describe("Files to apply find and replace in. Supports ** and * glob patterns.") |@|
      string("find").describe("Regex to find in file") |@|
      string("replace").describe("Replacement string. Can include version format strings (see help)")
    )(PatchConfig.apply, PatchConfig.unapply)
}

case class CommitMessageAction(pattern: String, action: IncrementAction)

object CommitMessageAction {
  val commitMessageActionDesc = (
    string("pattern").describe("Regular expression to match a commit message line") |@|
      string("action")
        .xmapEither(IncrementAction.read, (output: IncrementAction) => Right(output.toString))
        .describe("Version Increment behaviour if a commit line matches the regex Fail|IncrementMajor|IncrementMinor|IncrementPatch|NoIncrement")
    )(CommitMessageAction.apply, CommitMessageAction.unapply)
}

case class AppConfig(mode: VersionMode,
                     tagPrefix: Option[String],
                     defaults: Option[BranchConfigDefaults],
                     branches: Option[List[BranchConfig]],
                     patches: Option[List[PatchConfig]],
                     commitMessageActions: Option[List[CommitMessageAction]])

object AppConfig {
  val appConfigDesc = (
    string("mode").xmapEither(VersionMode.read, (output: VersionMode) => Right(output.toString))
      .describe("The Version Mode for this repository")
      .default(VersionMode.SemVer) |@|
      string("tagPrefix").describe("prefix for git tags").optional |@|
      nested("defaults")(BranchConfig.branchConfigDefaultsDesc).optional |@|
      nested("branches")(list(BranchConfig.branchConfigDesc)).optional |@|
      nested("patches")(list(PatchConfig.patchConfigDesc)).optional |@|
      nested("commitMessageActions")(list(CommitMessageAction.commitMessageActionDesc)).optional
    )(AppConfig.apply, AppConfig.unapply)


  val defaultDefaultBranchConfig: BranchConfigDefaults = BranchConfigDefaults(
    BranchConfig.Defaults.tag,
    BranchConfig.Defaults.tagMessageFormat,
    BranchConfig.Defaults.preReleaseFormat,
    BranchConfig.Defaults.buildMetaDataFormat,
    BranchConfig.Defaults.includeBuildMetaData,
    BranchConfig.Defaults.whenNoValidCommitMessages,
    BranchConfig.Defaults.patches,
    BranchConfig.Defaults.formats
  )
  val defaultBranchConfigs: List[BranchConfig] = List(
    BranchConfig("master", Some(true), None, None, None, Some(false), None, None, None)
  )
  val defaultCommitMessageActions: List[CommitMessageAction] = List(
    CommitMessageAction("BREAKING CHANGE", IncrementAction.IncrementMajor),
    CommitMessageAction("major(\\(.+\\))?:", IncrementAction.IncrementMajor),
    CommitMessageAction("minor(\\(.+\\))?:", IncrementAction.IncrementMinor),
    CommitMessageAction("patch(\\(.+\\))?:", IncrementAction.IncrementPatch),
    CommitMessageAction("feat(\\(.+\\))?:", IncrementAction.IncrementMinor),
    CommitMessageAction("fix(\\(.+\\))?:", IncrementAction.IncrementPatch)
  )

  def getRunConfig(configFile: Option[String], currentBranch: String): Task[RunConfig] = {
    for {
      appConfig <- getAppConfig(configFile)
      defaults = appConfig.defaults.getOrElse(defaultDefaultBranchConfig)
      branchConfig = appConfig.branches.getOrElse(defaultBranchConfigs)
        .find { bc => currentBranch.matches(bc.pattern) }
      patchNames = branchConfig.flatMap(_.patches).getOrElse(defaults.patches)
      patchConfigs <- getPatchConfigs(appConfig, patchNames)
    } yield {
      RunConfig(
        tag = branchConfig.flatMap(_.tag).getOrElse(defaults.tag),
        tagPrefix = appConfig.tagPrefix.getOrElse("v"),
        tagMessageFormat = branchConfig.flatMap(_.tagMessageFormat).getOrElse(defaults.tagMessageFormat),
        preReleaseFormat = branchConfig.flatMap(_.preReleaseFormat).getOrElse(defaults.preReleaseFormat),
        buildMetaDataFormat = branchConfig.flatMap(_.buildMetaDataFormat).getOrElse(defaults.buildMetaDataFormat),
        includeBuildMetaData = branchConfig.flatMap(_.includeBuildMetaData).getOrElse(defaults.includeBuildMetaData),
        commitMessageActions = mergeCommitMessageActions(defaultCommitMessageActions, appConfig.commitMessageActions.getOrElse(Nil)),
        whenNoValidCommitMessages = branchConfig.flatMap(_.whenNoValidCommitMessages).getOrElse(defaults.whenNoValidCommitMessages),
        formats = mergeFormats(defaults.formats, branchConfig.flatMap(_.formats).getOrElse(Nil)),
        patches = patchConfigs
      )
    }
  }

  def mergeFormats(startList: List[Format], overrides: List[Format]): List[Format] =
    merge(startList, overrides, (f: Format) => f.name)

  def mergeCommitMessageActions(startList: List[CommitMessageAction], overrides: List[CommitMessageAction]): List[CommitMessageAction] =
    merge(startList, overrides, (cma: CommitMessageAction) => cma.pattern)

  def merge[T](startList: List[T], overrides: List[T], getName: T => String): List[T] = {
    val startMap = startList.map( it => (getName(it), it)).toMap
    val overridesMap = overrides.map( it => (getName(it), it)).toMap
    overridesMap.values.foldLeft(startMap)((a, n) => a.+((getName(n), n))).values.toList.sortBy(getName(_))
  }

  def getPatchConfigs(appConfig: AppConfig, patchNames: List[String]): Task[List[PatchConfig]] = {
    val allPatchConfigs = appConfig.patches.getOrElse(Nil).map(it => (it.name, it)).toMap
    Task.foreach(patchNames) { c =>
      allPatchConfigs.get(c) match {
        case Some(p) => Task.succeed(p)
        case None => Task.fail(MkVerException(s"Can't find patch config named $c"))
      }
    }
  }

  def getAppConfig(configFile: Option[String]): Task[AppConfig] = {
    val file = configFile.map { cf =>
      for {
        path <- Path(cf)
        exists <- Files.exists(path)
        r <- if (exists) Task.some(cf) else Task.fail(MkVerException(s"--config $cf does not exist"))
      } yield r
    }.orElse {
      sys.env.get("GITMKVER_CONFIG").map { cf =>
        for {
          path <- Path(cf)
          exists <- Files.exists(path)
          r <- if (exists) Task.some(cf) else Task.fail(MkVerException(s"GITMKVER_CONFIG $cf does not exist"))
        } yield r
      }
    }.getOrElse {
      for {
        path <- Path("mkver.conf")
        exists <- Files.exists(path)
        r <- if (exists) Task.some("mkver.conf") else Task.none
      } yield r
    }

    file.flatMap { of =>
      of.map { f =>
        TypesafeConfigSource.fromTypesafeConfig(ConfigFactory.parseFile(new java.io.File(f)))
        // TODO Use this?
        //TypesafeConfig.fromHoconFile(new java.io.File(c), AppConfig.appConfigDesc)
      }.getOrElse {
        TypesafeConfigSource.fromTypesafeConfig(ConfigFactory.load("reference.conf"))
      }.fold(l => Task.fail(MkVerException(l)), r => Task.succeed(r))
    }.flatMap { source: ConfigSource =>
      read(AppConfig.appConfigDesc from source) match {
        case Left(value) => Task.fail(MkVerException("Unable to parse config: " + value))
        case Right(result) => Task.succeed(result)
      }
    }
  }
}
