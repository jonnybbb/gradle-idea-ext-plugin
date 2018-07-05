package org.jetbrains.gradle.ext

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.gson.Gson
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.PolymorphicDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.reflect.TypeOf
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.util.GradleVersion

@CompileStatic
class IdeaExtPlugin implements Plugin<Project> {
  void apply(Project p) {
    p.apply plugin: 'idea'
    extend(p)
  }

  def extend(Project project) {
    def ideaModel = project.extensions.findByName('idea') as IdeaModel
    if (!ideaModel) { return }

    if (ideaModel.project) {
      (ideaModel.project as ExtensionAware).extensions.create("settings", ProjectSettings, project)
    }

    (ideaModel.module as ExtensionAware).extensions.create("settings", ModuleSettings, project)
  }
}

@CompileStatic
abstract class AbstractExtensibleSettings {
  TypeOf mapConvertibleType = TypeOf.typeOf(MapConvertible)
  TypeOf iterableType = TypeOf.typeOf(Iterable)

  Map<String, ?> collectExtensionsMap() {
    def result = [:]
    if (this instanceof ExtensionAware) {
      def extContainer = (this as ExtensionAware).extensions
      if (GradleVersion.current() >= GradleVersion.version("4.5")) {
        extContainer.extensionsSchema.each { schema ->
          def name = schema.name
          def typeOfExt = schema.publicType

          def converted = convertToMapOrList(typeOfExt, extContainer.findByName(name))
          if (converted != null) {
            result.put(name, converted)
          }
        }
      } else {
        try {
          def schemaMethod = extContainer.class.getMethod("getSchema")
          Map<String, TypeOf> schema = schemaMethod.invoke(extContainer) as Map<String, TypeOf>
          schema.each { name, typeOfExt ->
            def converted = convertToMapOrList(typeOfExt, extContainer.findByName(name))
            if (converted != null) {
              result.put(name, converted)
            }
          }
        } catch (NoSuchMethodException e) {
          throw new GradleException("Can not collect extensions information in IDEA settings." +
                  " Please, use Gradle 4.2 or later.", e)
        }
      }
    }
    return result
  }

  def convertToMapOrList(TypeOf<?> typeOfExt, def extension) {
    if (extension == null) {
      return null
    }

    if (mapConvertibleType.isAssignableFrom(typeOfExt)) {
      return (extension as MapConvertible).toMap()
    }

    if (iterableType.isAssignableFrom(typeOfExt)) {
      def converted = (extension as Iterable)
              .findAll { it instanceof MapConvertible }
              .collect { (it as MapConvertible).toMap() }
      if (converted.size() > 0) {
        return converted
      } else {
        return null
      }
    }
  }
}

@CompileStatic
class ProjectSettings extends AbstractExtensibleSettings {
  private IdeaCompilerConfiguration compilerConfig
  private GroovyCompilerConfiguration groovyCompilerConfig
  private CopyrightConfiguration copyrightConfig
  private RunConfigurationContainer runConfigurations
  private Project project
  private CodeStyleConfig codeStyle
  private FrameworkDetectionExclusionSettings detectExclusions
  private NamedDomainObjectContainer<Inspection> inspections
  private TaskTriggersConfig taskTriggersConfig
  private ActionDelegationConfig actionDelegationConfig
  private IdeArtifacts artifacts

  private Gson gson = new Gson()

  ProjectSettings(Project project) {
    def runConfigurations = GradleUtils.customPolymorphicContainer(project, DefaultRunConfigurationContainer)

    runConfigurations.registerFactory(Application) { String name -> project.objects.newInstance(Application, name, project) }
    runConfigurations.registerFactory(JUnit) { String name -> project.objects.newInstance(JUnit, name) }
    runConfigurations.registerFactory(Remote) { String name -> project.objects.newInstance(Remote, name) }
    runConfigurations.registerFactory(TestNG) { String name -> project.objects.newInstance(TestNG, name) }

    this.runConfigurations = runConfigurations
    this.project = project
  }

  ActionDelegationConfig getDelegateActions() {
    if (actionDelegationConfig == null) {
      actionDelegationConfig = project.objects.newInstance(ActionDelegationConfig)
    }
    return actionDelegationConfig
  }

  void delegateActions(Action<ActionDelegationConfig> action) {
    action.execute(getDelegateActions())
  }

  TaskTriggersConfig getTaskTriggers() {
    if (taskTriggersConfig == null) {
      taskTriggersConfig = project.objects.newInstance(TaskTriggersConfig)
    }
    return taskTriggersConfig
  }

  void taskTriggers(Action<TaskTriggersConfig> action) {
    action.execute(getTaskTriggers())
  }

  IdeaCompilerConfiguration getCompiler() {
    if (compilerConfig == null) {
      compilerConfig = project.objects.newInstance(IdeaCompilerConfiguration, project)
    }
    return compilerConfig
  }

  void compiler(Action<IdeaCompilerConfiguration> action) {
    action.execute(getCompiler())
  }

  GroovyCompilerConfiguration getGroovyCompiler() {
    if (groovyCompilerConfig == null) {
      groovyCompilerConfig = project.objects.newInstance(GroovyCompilerConfiguration);
    }
    return groovyCompilerConfig
  }

  void groovyCompiler(Action<GroovyCompilerConfiguration> action) {
    action.execute(getGroovyCompiler())
  }

  CodeStyleConfig getCodeStyle() {
    if (codeStyle == null) {
      codeStyle = project.objects.newInstance(CodeStyleConfig)
    }
    return codeStyle
  }

  def codeStyle(Action<CodeStyleConfig> action) {
    action.execute(getCodeStyle())
  }

  NamedDomainObjectContainer<Inspection> getInspections() {
    if (inspections == null) {
      inspections = project.container(Inspection)
    }
    return inspections
  }

  def inspections(Action<NamedDomainObjectContainer<Inspection>> action) {
    action.execute(getInspections())
  }

  CopyrightConfiguration getCopyright() {
    if (copyrightConfig == null) {
      copyrightConfig = project.objects.newInstance(CopyrightConfiguration, project)
    }
    return copyrightConfig
  }

  def copyright(Action<CopyrightConfiguration> action) {
    action.execute(getCopyright())
  }

  RunConfigurationContainer getRunConfigurations() {
    return runConfigurations
  }

  def runConfigurations(Action<RunConfigurationContainer> action) {
    action.execute(runConfigurations)
  }

  def doNotDetectFrameworks(String... ids) {
    if (detectExclusions == null) {
      detectExclusions = project.objects.newInstance(FrameworkDetectionExclusionSettings)
    }
    detectExclusions.excludes.addAll(ids)
  }

  IdeArtifacts getIdeArtifacts() {
    if (artifacts == null) {
      artifacts = project.objects.newInstance(IdeArtifacts, project)
    }
    return artifacts
  }

  def ideArtifacts(Action<IdeArtifacts> action) {
    action.execute(getIdeArtifacts())
  }

  String toString() {
    def map = collectExtensionsMap()

    if (compilerConfig != null) {
      map["compiler"] = compilerConfig.toMap()
    }

    if (groovyCompilerConfig != null) {
      map["groovyCompiler"] = groovyCompilerConfig.toMap()
    }

    if (codeStyle != null) {
      map["codeStyle"] = codeStyle.toMap()
    }

    if (inspections != null) {
      map["inspections"] = inspections.collect { it.toMap() }
    }

    if (copyrightConfig != null) {
      map["copyright"] = copyrightConfig.toMap()
    }

    if (!runConfigurations.isEmpty()) {
      map["runConfigurations"] = runConfigurations.collect { (it as RunConfiguration).toMap() }
    }

    if (detectExclusions != null) {
      map["frameworkDetectionExcludes"] = detectExclusions.excludes
    }

    if (taskTriggersConfig != null) {
      map["taskTriggersConfig"] = taskTriggersConfig.toMap()
    }

    if (actionDelegationConfig != null) {
      map["actionDelegationConfig"] = actionDelegationConfig.toMap()
    }

    if (artifacts != null) {
      map << artifacts.toMap()
    }

    return gson.toJson(map)
  }
}


@CompileStatic
class ActionDelegationConfig implements MapConvertible {
  enum TestRunner { PLATFORM, GRADLE, CHOOSE_PER_TEST }
  boolean delegateBuildRunToGradle = false
  TestRunner testRunner = TestRunner.PLATFORM

  Map<String, ?> toMap() {
    return ["delegateBuildRunToGradle": delegateBuildRunToGradle,  "testRunner": testRunner]
  }
}

@CompileStatic
class TaskTriggersConfig implements MapConvertible {

  ListMultimap<String, Task> phaseMap = ArrayListMultimap.create()

  void beforeSync(Task... tasks) {
    phaseMap.putAll("beforeSync", Arrays.asList(tasks))
  }
  void afterSync(Task... tasks) {
    phaseMap.putAll("afterSync", Arrays.asList(tasks))
  }
  void beforeBuild(Task... tasks) {
    phaseMap.putAll("beforeBuild", Arrays.asList(tasks))
  }
  void afterBuild(Task... tasks) {
    phaseMap.putAll("afterBuild", Arrays.asList(tasks))
  }
  void beforeRebuild(Task... tasks) {
    phaseMap.putAll("beforeRebuild", Arrays.asList(tasks))
  }
  void afterRebuild(Task... tasks) {
    phaseMap.putAll("afterRebuild", Arrays.asList(tasks))
  }

  @Override
  Map<String, ?> toMap() {
    def result = [:]
    phaseMap.keySet().each { String phase ->
      List<Task> tasks = phaseMap.get(phase)
      def taskInfos = tasks.collect { task -> ["taskPath" : task.path, "projectPath" : task.project.rootProject.projectDir.path.replaceAll("\\\\", "/")] }
      result.put(phase, taskInfos)
    }
    return result
  }
}

@CompileStatic
class ModuleSettings extends AbstractExtensibleSettings {
  final PolymorphicDomainObjectContainer<Facet> facets

  ModuleSettings(Project project) {
    def facets = GradleUtils.polymorphicContainer(project, Facet)

    facets.registerFactory(SpringFacet) { String name -> project.objects.newInstance(SpringFacet, name, project) }
    this.facets = facets
  }

  def facets(Action<PolymorphicDomainObjectContainer<Facet>> action) {
    action.execute(facets)
  }

  @Override
  String toString() {
    def map = collectExtensionsMap()
    if (!facets.isEmpty()) {
      map["facets"] = facets.asList().collect { it.toMap() }
    }
    return JsonOutput.toJson(map)
  }
}
