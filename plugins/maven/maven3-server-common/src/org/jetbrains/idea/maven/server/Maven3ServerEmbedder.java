// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.server;

import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtilRt;
import com.intellij.util.text.VersionComparatorUtil;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.DefaultMaven;
import org.apache.maven.Maven;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.archetype.catalog.Archetype;
import org.apache.maven.archetype.catalog.ArchetypeCatalog;
import org.apache.maven.archetype.common.ArchetypeArtifactManager;
import org.apache.maven.archetype.exception.UnknownArchetype;
import org.apache.maven.archetype.metadata.ArchetypeDescriptor;
import org.apache.maven.archetype.metadata.RequiredProperty;
import org.apache.maven.archetype.source.ArchetypeDataSource;
import org.apache.maven.archetype.source.ArchetypeDataSourceException;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.ResolutionListener;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.project.*;
import org.apache.maven.session.scope.internal.SessionScope;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.RepositorySystemSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.model.MavenArchetype;
import org.jetbrains.idea.maven.model.MavenModel;
import org.jetbrains.idea.maven.model.MavenRemoteRepository;
import org.jetbrains.idea.maven.server.embedder.CustomMaven3ModelInterpolator2;
import org.jetbrains.idea.maven.server.embedder.MavenExecutionResult;
import org.jetbrains.idea.maven.server.security.MavenToken;

import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.maven.archetype.source.CatalogArchetypeDataSource.ARCHETYPE_CATALOG_PROPERTY;
import static org.apache.maven.archetype.source.RemoteCatalogArchetypeDataSource.REPOSITORY_PROPERTY;
import static org.jetbrains.idea.maven.server.MavenModelConverter.convertRemoteRepositories;

/**
 * @author Vladislav.Soroka
 */
public abstract class Maven3ServerEmbedder extends MavenRemoteObject implements MavenServerEmbedder {

  public interface RunnableThrownRemote {
    void run() throws RemoteException;
  }

  public final static boolean USE_MVN2_COMPATIBLE_DEPENDENCY_RESOLVING = System.getProperty("idea.maven3.use.compat.resolver") != null;
  private final static String MAVEN_VERSION = System.getProperty(MAVEN_EMBEDDER_VERSION);
  private static final Pattern PROPERTY_PATTERN = Pattern.compile("\"-D([\\S&&[^=]]+)(?:=([^\"]+))?\"|-D([\\S&&[^=]]+)(?:=(\\S+))?");
  protected final MavenServerSettings myServerSettings;
  protected final Map<String, LongRunningTask> myLongRunningTasks = new ConcurrentHashMap<>();

  protected Maven3ServerEmbedder(MavenServerSettings settings) {
    myServerSettings = settings;
    initLogging(myServerSettings);
  }

  private static void initLogging(MavenServerSettings settings) {
    try {
      final Level rootLoggerLevel = toJavaUtilLoggingLevel(settings.getLoggingLevel());
      Logger.getLogger("").setLevel(rootLoggerLevel);
      if (rootLoggerLevel.intValue() < Level.SEVERE.intValue()) {
        Logger.getLogger("org.apache.maven.wagon.providers.http.httpclient.wire").setLevel(Level.SEVERE);
        Logger.getLogger("org.apache.http.wire").setLevel(Level.SEVERE);
      }
    }
    catch (Throwable ignore) {
    }
  }

  private static Level toJavaUtilLoggingLevel(int level) {
    switch (level) {
      case MavenServerConsole.LEVEL_DEBUG:
        return Level.ALL;
      case MavenServerConsole.LEVEL_ERROR:
        return Level.SEVERE;
      case MavenServerConsole.LEVEL_FATAL:
        return Level.SEVERE;
      case MavenServerConsole.LEVEL_DISABLED:
        return Level.OFF;
      case MavenServerConsole.LEVEL_INFO:
        return Level.INFO;
      case MavenServerConsole.LEVEL_WARN:
        return Level.WARNING;
    }
    return Level.INFO;
  }

  protected abstract ArtifactRepository getLocalRepository();

  @NotNull
  protected List<ProjectBuildingResult> getProjectBuildingResults(@NotNull MavenExecutionRequest request, @NotNull Collection<File> files) {
    final ProjectBuilder builder = getComponent(ProjectBuilder.class);

    ModelInterpolator modelInterpolator = getComponent(ModelInterpolator.class);

    String savedLocalRepository = null;
    if (modelInterpolator instanceof CustomMaven3ModelInterpolator2) {
      CustomMaven3ModelInterpolator2 customMaven3ModelInterpolator2 = (CustomMaven3ModelInterpolator2)modelInterpolator;
      savedLocalRepository = customMaven3ModelInterpolator2.getLocalRepository();
      customMaven3ModelInterpolator2.setLocalRepository(request.getLocalRepositoryPath().getAbsolutePath());
    }


    List<ProjectBuildingResult> buildingResults = new ArrayList<ProjectBuildingResult>();

    final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();
    projectBuildingRequest.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
    projectBuildingRequest.setResolveDependencies(false);

    try {
      if (files.size() == 1) {
        buildSinglePom(builder, buildingResults, projectBuildingRequest, files.iterator().next());
      }
      else {
        try {
          buildingResults = builder.build(new ArrayList<File>(files), false, projectBuildingRequest);
        }
        catch (ProjectBuildingException e) {
          for (ProjectBuildingResult result : e.getResults()) {
            if (result.getProject() != null) {
              buildingResults.add(result);
            }
            else {
              buildSinglePom(builder, buildingResults, projectBuildingRequest, result.getPomFile());
            }
          }
        }
      }
    }
    finally {
      if (modelInterpolator instanceof CustomMaven3ModelInterpolator2 && savedLocalRepository != null) {
        ((CustomMaven3ModelInterpolator2)modelInterpolator).setLocalRepository(savedLocalRepository);
      }
    }
    return buildingResults;
  }

  private void buildSinglePom(ProjectBuilder builder,
                              List<ProjectBuildingResult> buildingResults,
                              ProjectBuildingRequest projectBuildingRequest,
                              File pomFile) {
    try {
      ProjectBuildingResult build = builder.build(pomFile, projectBuildingRequest);
      buildingResults.add(build);
    }
    catch (ProjectBuildingException e) {
      handleProjectBuildingException(buildingResults, e);
    }
  }

  protected void handleProjectBuildingException(List<ProjectBuildingResult> buildingResults, ProjectBuildingException e) {
    List<ProjectBuildingResult> results = e.getResults();
    if (results != null && !results.isEmpty()) {
      buildingResults.addAll(results);
    }
    else {
      Throwable cause = e.getCause();
      List<ModelProblem> problems = null;
      if (cause instanceof ModelBuildingException) {
        problems = ((ModelBuildingException)cause).getProblems();
      }
      buildingResults.add(new MyProjectBuildingResult(null, e.getPomFile(), null, problems, null));
    }
  }

  private static class MyProjectBuildingResult implements ProjectBuildingResult {

    private final String myProjectId;
    private final File myPomFile;
    private final MavenProject myMavenProject;
    private final List<ModelProblem> myProblems;
    private final DependencyResolutionResult myDependencyResolutionResult;

    MyProjectBuildingResult(String projectId,
                            File pomFile,
                            MavenProject mavenProject,
                            List<ModelProblem> problems,
                            DependencyResolutionResult dependencyResolutionResult) {
      myProjectId = projectId;
      myPomFile = pomFile;
      myMavenProject = mavenProject;
      myProblems = problems;
      myDependencyResolutionResult = dependencyResolutionResult;
    }

    @Override
    public String getProjectId() {
      return myProjectId;
    }

    @Override
    public File getPomFile() {
      return myPomFile;
    }

    @Override
    public MavenProject getProject() {
      return myMavenProject;
    }

    @Override
    public List<ModelProblem> getProblems() {
      return myProblems;
    }

    @Override
    public DependencyResolutionResult getDependencyResolutionResult() {
      return myDependencyResolutionResult;
    }
  }

  protected MavenExecutionResult resolveMvn2CompatResult(MavenProject project,
                                                         List<Exception> exceptions,
                                                         List<ResolutionListener> listeners,
                                                         ArtifactRepository localRepository) {
    ArtifactResolutionRequest resolutionRequest = new ArtifactResolutionRequest();
    resolutionRequest.setArtifactDependencies(project.getDependencyArtifacts());
    resolutionRequest.setArtifact(project.getArtifact());
    resolutionRequest.setManagedVersionMap(project.getManagedVersionMap());
    resolutionRequest.setLocalRepository(localRepository);
    resolutionRequest.setRemoteRepositories(project.getRemoteArtifactRepositories());
    resolutionRequest.setListeners(listeners);

    resolutionRequest.setResolveRoot(false);
    resolutionRequest.setResolveTransitively(true);

    ArtifactResolver resolver = getComponent(ArtifactResolver.class);
    ArtifactResolutionResult result = resolver.resolve(resolutionRequest);

    project.setArtifacts(result.getArtifacts());
    return new MavenExecutionResult(project, exceptions);
  }

  protected void addMvn2CompatResults(MavenProject project,
                                      List<Exception> exceptions,
                                      List<ResolutionListener> listeners,
                                      ArtifactRepository localRepository,
                                      Collection<MavenExecutionResult> executionResults) {
    executionResults.add(resolveMvn2CompatResult(project, exceptions, listeners, localRepository));
  }

  @Override
  @Nullable
  public MavenModel readModel(File file, MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    return null;
  }

  public static Map<String, String> getMavenAndJvmConfigProperties(File workingDir) {
    if (workingDir == null) {
      return Collections.emptyMap();
    }
    File baseDir = MavenServerUtil.findMavenBasedir(workingDir);

    Map<String, String> result = new HashMap<String, String>();
    readConfigFiles(baseDir, result);
    return result.isEmpty() ? Collections.emptyMap() : result;
  }

  static void readConfigFiles(File baseDir, Map<String, String> result) {
    readConfigFile(baseDir, File.separator + ".mvn" + File.separator + "jvm.config", result, "");
    readConfigFile(baseDir, File.separator + ".mvn" + File.separator + "maven.config", result, "true");
  }

  private static void readConfigFile(File baseDir, String relativePath, Map<String, String> result, String valueIfMissing) {
    File configFile = new File(baseDir, relativePath);

    if (configFile.exists() && configFile.isFile()) {
      try {
        String text = FileUtilRt.loadFile(configFile, "UTF-8");
        Matcher matcher = PROPERTY_PATTERN.matcher(text);
        while (matcher.find()) {
          if (matcher.group(1) != null) {
            result.put(matcher.group(1), StringUtilRt.notNullize(matcher.group(2), valueIfMissing));
          }
          else {
            result.put(matcher.group(3), StringUtilRt.notNullize(matcher.group(4), valueIfMissing));
          }
        }
      }
      catch (IOException ignore) {
      }
    }
  }

  @NotNull
  protected abstract List<ArtifactRepository> convertRepositories(List<MavenRemoteRepository> repositories) throws RemoteException;

  @NotNull
  protected List<ArtifactRepository> map2ArtifactRepositories(List<MavenRemoteRepository> repositories) throws RemoteException {
    PlexusContainer container = getContainer();
    List<ArtifactRepository> result = new ArrayList<ArtifactRepository>();
    for (MavenRemoteRepository each : repositories) {
      try {
        ArtifactRepositoryFactory factory = getComponent(ArtifactRepositoryFactory.class);
        result.add(ProjectUtils.buildArtifactRepository(MavenModelConverter.toNativeRepository(each), factory, container));
      }
      catch (InvalidRepositoryException e) {
        Maven3ServerGlobals.getLogger().warn(e);
      }
    }
    return result;
  }

  @Nullable
  public String getMavenVersion() {
    return MAVEN_VERSION;
  }

  public abstract <T> T getComponent(Class<T> clazz, String roleHint);

  public abstract <T> T getComponent(Class<T> clazz);

  protected void executeWithMavenSession(MavenExecutionRequest request, final Runnable runnable) throws RemoteException {
    executeWithMavenSession(request, new RunnableThrownRemote() {
      @Override
      public void run() throws RemoteException {
        runnable.run();
      }
    });
  }

  protected void executeWithMavenSession(MavenExecutionRequest request, RunnableThrownRemote runnable) throws RemoteException {

    if (VersionComparatorUtil.compare(getMavenVersion(), "3.2.5") >= 0) {
      executeWithSessionScope(request, runnable);
    }
    else {
      executeWithMavenSessionLegacy(request, runnable);
    }
  }

  protected void executeWithMavenSessionLegacy(MavenExecutionRequest request, final Runnable runnable) throws RemoteException {
    executeWithMavenSessionLegacy(request, new RunnableThrownRemote() {
      @Override
      public void run() throws RemoteException {
        runnable.run();
      }
    });
  }


  protected void executeWithMavenSessionLegacy(MavenExecutionRequest request, RunnableThrownRemote runnable) throws RemoteException {
    DefaultMaven maven = (DefaultMaven)getComponent(Maven.class);
    MavenSession mavenSession = createMavenSession(request, maven);
    LegacySupport legacySupport = getComponent(LegacySupport.class);
    MavenSession oldSession = legacySupport.getSession();
    legacySupport.setSession(mavenSession);
    // adapted from {@link DefaultMaven#doExecute(MavenExecutionRequest)}
    notifyAfterSessionStart(mavenSession);
    try {
      runnable.run();
    }
    finally {
      legacySupport.setSession(oldSession);
    }
  }

  @NotNull
  private MavenSession createMavenSession(MavenExecutionRequest request, DefaultMaven maven) {
    RepositorySystemSession repositorySession = maven.newRepositorySession(request);
    request.getProjectBuildingRequest().setRepositorySession(repositorySession);
    return new MavenSession(getContainer(), repositorySession, request, new DefaultMavenExecutionResult());
  }

  private void notifyAfterSessionStart(MavenSession mavenSession) {
    try {
      for (AbstractMavenLifecycleParticipant listener : getLifecycleParticipants(Collections.emptyList())) {
        listener.afterSessionStart(mavenSession);
      }
    }
    catch (MavenExecutionException e) {
      throw new RuntimeException(e);
    }
  }


  protected void executeWithSessionScope(MavenExecutionRequest request, RunnableThrownRemote runnable) throws RemoteException {
    DefaultMaven maven = (DefaultMaven)getComponent(Maven.class);
    SessionScope sessionScope = getComponent(SessionScope.class);
    sessionScope.enter();

    try {
      MavenSession mavenSession = createMavenSession(request, maven);
      sessionScope.seed(MavenSession.class, mavenSession);
      LegacySupport legacySupport = getComponent(LegacySupport.class);
      MavenSession oldSession = legacySupport.getSession();
      legacySupport.setSession(mavenSession);

      notifyAfterSessionStart(mavenSession);
      // adapted from {@link DefaultMaven#doExecute(MavenExecutionRequest)}
      try {
        runnable.run();
      }
      finally {
        legacySupport.setSession(oldSession);
      }
    }
    finally {
      sessionScope.exit();
    }
  }

  @NotNull
  protected abstract PlexusContainer getContainer();

  public abstract MavenExecutionRequest createRequest(File file,
                                                      List<String> activeProfiles,
                                                      List<String> inactiveProfiles,
                                                      String goal)
    throws RemoteException;

  protected static void warn(String message, Throwable e) {
    Maven3ServerGlobals.getLogger().warn(new RuntimeException(message, e));
  }

  @Override
  public Set<MavenRemoteRepository> resolveRepositories(@NotNull Collection<MavenRemoteRepository> repositories, MavenToken token)
    throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      return new HashSet<MavenRemoteRepository>(
        convertRemoteRepositories(convertRepositories(new ArrayList<MavenRemoteRepository>(repositories))));
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
  }

  @Override
  public Collection<MavenArchetype> getArchetypes(MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      ArchetypeDataSource source = getComponent(ArchetypeDataSource.class, "internal-catalog");
      ArchetypeCatalog archetypeCatalog = source.getArchetypeCatalog(new Properties());
      return getArchetypes(archetypeCatalog);
    }
    catch (Exception e) {
      Maven3ServerGlobals.getLogger().warn(e);
    }
    return Collections.emptyList();
  }

  @Override
  public Collection<MavenArchetype> getLocalArchetypes(MavenToken token, @NotNull String path) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      ArchetypeDataSource source = getComponent(ArchetypeDataSource.class, "catalog");
      Properties properties = new Properties();
      properties.put(ARCHETYPE_CATALOG_PROPERTY, path);
      ArchetypeCatalog archetypeCatalog = source.getArchetypeCatalog(properties);
      return getArchetypes(archetypeCatalog);
    }
    catch (Exception e) {
      Maven3ServerGlobals.getLogger().warn(e);
    }
    return Collections.emptyList();
  }

  @Override
  public Collection<MavenArchetype> getRemoteArchetypes(MavenToken token, @NotNull String url) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      ArchetypeDataSource source = getComponent(ArchetypeDataSource.class, "remote-catalog");
      Properties properties = new Properties();
      properties.put(REPOSITORY_PROPERTY, url);
      ArchetypeCatalog archetypeCatalog = source.getArchetypeCatalog(properties);
      return getArchetypes(archetypeCatalog);
    }
    catch (ArchetypeDataSourceException e) {
      Maven3ServerGlobals.getLogger().warn(e);
    }
    return Collections.emptyList();
  }

  @Nullable
  @Override
  public Map<String, String> resolveAndGetArchetypeDescriptor(@NotNull final String groupId, @NotNull final String artifactId,
                                                              @NotNull final String version,
                                                              @NotNull List<MavenRemoteRepository> repositories,
                                                              @Nullable final String url, MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      final MavenExecutionRequest request = createRequest(null, null, null, null);
      List<ArtifactRepository> artifactRepositories = map2ArtifactRepositories(repositories);
      for (ArtifactRepository repository : artifactRepositories) {
        request.addRemoteRepository(repository);
      }

      final Map<String, String> result = new HashMap<String, String>();
      final AtomicBoolean unknownArchetypeError = new AtomicBoolean(false);
      executeWithMavenSession(request, (Runnable)() -> {
        MavenArtifactRepository artifactRepository = null;
        if (url != null) {
          artifactRepository = new MavenArtifactRepository();
          artifactRepository.setId("archetype");
          artifactRepository.setUrl(url);
          artifactRepository.setLayout(new DefaultRepositoryLayout());
        }

        List<ArtifactRepository> remoteRepositories = request.getRemoteRepositories();

        ArchetypeArtifactManager archetypeArtifactManager = getComponent(ArchetypeArtifactManager.class);
        ArchetypeDescriptor descriptor = null;
        try {
          descriptor = archetypeArtifactManager.getFileSetArchetypeDescriptor(
            groupId, artifactId, version, artifactRepository,
            getLocalRepository(), remoteRepositories);
        }
        catch (UnknownArchetype e) {
          unknownArchetypeError.set(true);
        }
        if (descriptor != null && descriptor.getRequiredProperties() != null) {
          for (RequiredProperty property : descriptor.getRequiredProperties()) {
            result.put(property.getKey(), property.getDefaultValue() != null ? property.getDefaultValue() : "");
          }
        }
      });
      return unknownArchetypeError.get() ? null : result;
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
  }

  @NotNull
  private static ArrayList<MavenArchetype> getArchetypes(ArchetypeCatalog archetypeCatalog) {
    ArrayList<MavenArchetype> result = new ArrayList<MavenArchetype>(archetypeCatalog.getArchetypes().size());
    for (Archetype each : archetypeCatalog.getArchetypes()) {
      result.add(MavenModelConverter.convertArchetype(each));
    }
    return result;
  }


  /**
   * adapted from {@link DefaultMaven#getLifecycleParticipants(Collection)}
   */
  private Collection<AbstractMavenLifecycleParticipant> getLifecycleParticipants(Collection<MavenProject> projects) {
    Collection<AbstractMavenLifecycleParticipant> lifecycleListeners = new LinkedHashSet<AbstractMavenLifecycleParticipant>();

    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      try {
        lifecycleListeners.addAll(getContainer().lookupList(AbstractMavenLifecycleParticipant.class));
      }
      catch (ComponentLookupException e) {
        // this is just silly, lookupList should return an empty list!
        warn("Failed to lookup lifecycle participants", e);
      }

      Collection<ClassLoader> scannedRealms = new HashSet<ClassLoader>();

      for (MavenProject project : projects) {
        ClassLoader projectRealm = project.getClassRealm();

        if (projectRealm != null && scannedRealms.add(projectRealm)) {
          Thread.currentThread().setContextClassLoader(projectRealm);

          try {
            lifecycleListeners.addAll(getContainer().lookupList(AbstractMavenLifecycleParticipant.class));
          }
          catch (ComponentLookupException e) {
            // this is just silly, lookupList should return an empty list!
            warn("Failed to lookup lifecycle participants", e);
          }
        }
      }
    }
    finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }

    return lifecycleListeners;
  }

  @NotNull
  @Override
  public LongRunningTaskStatus getLongRunningTaskStatus(@NotNull String longRunningTaskId, MavenToken token) {
    MavenServerUtil.checkToken(token);

    LongRunningTask task = myLongRunningTasks.get(longRunningTaskId);

    if (null == task) return new LongRunningTaskStatus(0, 0);

    return new LongRunningTaskStatus(task.getTotalRequests(), task.getFinishedRequests());
  }

  @Override
  public boolean cancelLongRunningTask(@NotNull String longRunningTaskId, MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);

    LongRunningTask task = myLongRunningTasks.get(longRunningTaskId);

    if (null == task) return false;

    task.cancel();
    return true;
  }

  protected class LongRunningTask implements AutoCloseable {
    @NotNull private final String myId;
    private final AtomicInteger myFinishedRequests = new AtomicInteger(0);
    private final AtomicInteger myTotalRequests;
    private final AtomicBoolean isCanceled = new AtomicBoolean(false);

    protected LongRunningTask(@NotNull String id, int totalRequests) {
      myId = id;
      myTotalRequests = new AtomicInteger(totalRequests);

      myLongRunningTasks.put(myId, this);
    }

    public void incrementFinishedRequests() {
      myFinishedRequests.incrementAndGet();
    }

    private int getFinishedRequests() {
      return myFinishedRequests.get();
    }

    private int getTotalRequests() {
      return myTotalRequests.get();
    }

    public void updateTotalRequests(int newValue) {
      myTotalRequests.set(newValue);
    }

    private void cancel() {
      isCanceled.set(true);
    }

    public boolean isCanceled() {
      return isCanceled.get();
    }

    @Override
    public void close() {
      myLongRunningTasks.remove(myId);
    }
  }

}
