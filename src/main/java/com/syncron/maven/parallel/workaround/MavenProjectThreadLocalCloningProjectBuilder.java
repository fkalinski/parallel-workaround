package com.syncron.maven.parallel.workaround;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.component.annotations.Component;

@Component(role = ProjectBuilder.class)
public class MavenProjectThreadLocalCloningProjectBuilder extends DefaultProjectBuilder {
	@Override
	public ProjectBuildingResult build(File projectFile, ProjectBuildingRequest request)
			throws ProjectBuildingException {
		return replaceProject(super.build(projectFile, request));
	}

	@Override
	public ProjectBuildingResult build(Artifact projectArtifact, ProjectBuildingRequest request)
			throws ProjectBuildingException {
		return replaceProject(super.build(projectArtifact, request));
	}

	@Override
	public ProjectBuildingResult build(Artifact projectArtifact, boolean allowStubModel, ProjectBuildingRequest request)
			throws ProjectBuildingException {
		return replaceProject(super.build(projectArtifact, allowStubModel, request));
	}

	@Override
	public ProjectBuildingResult build(ModelSource modelSource, ProjectBuildingRequest request)
			throws ProjectBuildingException {
		return replaceProject(super.build(modelSource, request));
	}

	@Override
	public List<ProjectBuildingResult> build(List<File> pomFiles, boolean recursive, ProjectBuildingRequest request)
			throws ProjectBuildingException {
		List<ProjectBuildingResult> results = super.build(pomFiles, recursive, request);
		List<ProjectBuildingResult> newResults = new ArrayList<>(results.size());
		for (ProjectBuildingResult result : results) {
			newResults.add(replaceProject(result));
		}
		return newResults;
	}

	public ProjectBuildingResult replaceProject(final ProjectBuildingResult projectBuildingResult) {
		return new MavenProjectReplacingProjectBuildingResult(projectBuildingResult);
	}

	/**
	 * The MavenProject descendant, copying all the data from parent MavenProject, and holding dependency artifacts
	 * in ThreadLocal fields.
	 */
	private static class ThreadLocalArtifactsMavenProject extends MavenProject {

		private static final Map<MavenProject, MavenProject> mapping = new HashMap<>();
		private final MavenProject project;

		private boolean init;
		private Set<Artifact> resolvedArtifacts;
		private ThreadLocal<ArtifactFilter> artifactFilterThreadLocal;
		private ThreadLocal<Set<Artifact>> artifactsThreadLocal;
		private ThreadLocal<Map<String, Artifact>> artifactMapThreadLocal;

		public ThreadLocalArtifactsMavenProject(MavenProject project) {
			super(project);

			// not handled in copying constructor
			setProjectBuildingRequest(project.getProjectBuildingRequest());
			setCollectedProjects(project.getCollectedProjects());

			// DefaultProjectBuilder sets it to the same list as artifacts
			setResolvedArtifacts(Collections.unmodifiableSet(project.getArtifacts()));

			this.project = project;
			mapping.put(project, this);
			setParent(mapping.get(project.getParent()));
		}

		// not handled in copying constructor and no plain setter
		@Override
		public Map<String, List<String>> getInjectedProfileIds() {
			return project.getInjectedProfileIds();
		}

		public void init() {
			if (!init) {
				artifactsThreadLocal = new ThreadLocal<>();
				artifactFilterThreadLocal = new ThreadLocal<>();
				artifactMapThreadLocal = new ThreadLocal<>();
				init = true;
			}
		}

		// method called by super constructor, should initialize the new fields lazily here
		@Override
		public void setArtifacts(Set<Artifact> artifacts) {
			init();
			this.artifactsThreadLocal.set(artifacts);
			this.artifactMapThreadLocal.remove();
		}

		@Override
		public Set<Artifact> getArtifacts() {
			init();
			if (artifactsThreadLocal.get() == null) {
				if (artifactFilterThreadLocal.get() == null || resolvedArtifacts == null) {
					artifactsThreadLocal.set(new LinkedHashSet<>());
				} else {
					artifactsThreadLocal.set(new LinkedHashSet<>(resolvedArtifacts.size() * 2));
					for (Artifact artifact : resolvedArtifacts) {
						if (artifactFilterThreadLocal.get().include(artifact)) {
							artifactsThreadLocal.get().add(artifact);
						}
					}
				}
			}
			return artifactsThreadLocal.get();
		}

		@Override
		public Map<String, Artifact> getArtifactMap() {
			init();
			if (artifactMapThreadLocal.get() == null) {
				artifactMapThreadLocal.set(ArtifactUtils.artifactMapByVersionlessId(getArtifacts()));
			}
			return artifactMapThreadLocal.get();
		}

		@Override
		public void setResolvedArtifacts(Set<Artifact> artifacts) {
			init();
			this.resolvedArtifacts = (artifacts != null) ? artifacts : Collections.<Artifact>emptySet();
			this.artifactsThreadLocal.remove();
			this.artifactMapThreadLocal.remove();
		}

		@Override
		public void setArtifactFilter(ArtifactFilter artifactFilter) {
			init();
			this.artifactFilterThreadLocal.set(artifactFilter);
			this.artifactsThreadLocal.remove();
			this.artifactMapThreadLocal.remove();
		}
	}

	/**
	 * As there is no plug-in point to replace creation of MavenProject with another class, result of project
	 * build is wrapped, and a new MavenProject descendant is returned.
	 */
	private static class MavenProjectReplacingProjectBuildingResult implements ProjectBuildingResult {
		protected final MavenProject project;
		private final ProjectBuildingResult projectBuildingResult;

		public MavenProjectReplacingProjectBuildingResult(ProjectBuildingResult projectBuildingResult) {
			this.projectBuildingResult = projectBuildingResult;
			project = new ThreadLocalArtifactsMavenProject(projectBuildingResult.getProject());
		}

		public String getProjectId() {
			return projectBuildingResult.getProjectId();
		}

		public File getPomFile() {
			return projectBuildingResult.getPomFile();
		}

		public MavenProject getProject() {
			return project;
		}

		public List<ModelProblem> getProblems() {
			return projectBuildingResult.getProblems();
		}

		public DependencyResolutionResult getDependencyResolutionResult() {
			return projectBuildingResult.getDependencyResolutionResult();
		}
	}
}
